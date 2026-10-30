import type {
  ProductionGateway,
  ProductionStatus,
  StageListener,
} from './contracts.js';
import {DesktopLifecycleError} from './errors.js';

export interface LifecycleOptions {
  healthTimeoutMs: number;
  shutdownTimeoutMs: number;
  pollIntervalMs: number;
}

const DEFAULT_OPTIONS: LifecycleOptions = {
  healthTimeoutMs: 120_000,
  shutdownTimeoutMs: 30_000,
  pollIntervalMs: 500,
};

export interface StartupResult {
  reusedExistingBackend: boolean;
}

function isCompositeRunning(status: ProductionStatus): boolean {
  return status.backendState === 'RUNNING'
    && status.fastApiState === 'RUNNING'
    && status.javaState === 'RUNNING'
    && status.backendPortOpen
    && status.fastApiPortOpen
    && status.autoRecovery === 'ARMED';
}

function isCompositeStopped(status: ProductionStatus): boolean {
  return status.backendState === 'STOPPED'
    && status.fastApiState === 'STOPPED'
    && status.javaState === 'STOPPED'
    && !status.backendPortOpen
    && !status.fastApiPortOpen
    && status.autoRecovery === 'DISARMED';
}

export class DesktopLifecycle {
  readonly #gateway: ProductionGateway;
  readonly #options: LifecycleOptions;
  #startupAttempt?: Promise<StartupResult>;
  #shutdownAttempt?: Promise<void>;

  constructor(
    gateway: ProductionGateway,
    options: Partial<LifecycleOptions> = {},
  ) {
    this.#gateway = gateway;
    this.#options = {...DEFAULT_OPTIONS, ...options};
  }

  ensureStarted(onStage: StageListener): Promise<StartupResult> {
    if (this.#startupAttempt) {
      return this.#startupAttempt;
    }
    const attempt = this.#performStartup(onStage);
    this.#startupAttempt = attempt;
    void attempt.catch(() => {
      if (this.#startupAttempt === attempt) {
        this.#startupAttempt = undefined;
      }
    });
    return attempt;
  }

  shutdown(onStage: StageListener): Promise<void> {
    if (this.#shutdownAttempt) {
      return this.#shutdownAttempt;
    }
    const attempt = this.#performShutdown(onStage);
    this.#shutdownAttempt = attempt;
    void attempt.catch(() => {
      if (this.#shutdownAttempt === attempt) {
        this.#shutdownAttempt = undefined;
      }
    });
    return attempt;
  }

  async #performStartup(onStage: StageListener): Promise<StartupResult> {
    onStage('checking_service');
    let current = await this.#gateway.status();
    const reusedExistingBackend = isCompositeRunning(current);
    if (!reusedExistingBackend) {
      onStage('starting_backend');
      current = await this.#gateway.start();
      if (!isCompositeRunning(current)) {
        throw new DesktopLifecycleError(
          'DESKTOP_LIFECYCLE_CONTRACT_INVALID',
        );
      }
    }

    onStage('connecting_database');
    await this.#waitForHealthy();
    onStage('loading_client');
    return {reusedExistingBackend};
  }

  async #waitForHealthy(): Promise<void> {
    const deadline = this.#gateway.now() + this.#options.healthTimeoutMs;
    let lastError: unknown;
    while (this.#gateway.now() <= deadline) {
      try {
        const health = await this.#gateway.health();
        if (health.status === 'HEALTHY'
            && health.fastApiStatus === 'HEALTHY') {
          return;
        }
        if (health.status === 'BLOCKED') {
          if (health.fastApiStatus === 'BLOCKED') {
            throw new DesktopLifecycleError(health.fastApiReason);
          }
          throw new DesktopLifecycleError('DESKTOP_BACKEND_BLOCKED');
        }
      } catch (error) {
        if (error instanceof DesktopLifecycleError
            && (error.code === 'DESKTOP_BACKEND_VERSION_MISMATCH'
              || error.code === 'DESKTOP_BACKEND_BLOCKED'
              || error.code.startsWith('FASTAPI_HEALTH_'))) {
          throw error;
        }
        lastError = error;
      }
      await this.#gateway.wait(this.#options.pollIntervalMs);
    }
    if (lastError instanceof DesktopLifecycleError
        && lastError.code === 'DESKTOP_BACKEND_VERSION_MISMATCH') {
      throw lastError;
    }
    throw new DesktopLifecycleError('DESKTOP_BACKEND_HEALTH_TIMEOUT');
  }

  async #performShutdown(onStage: StageListener): Promise<void> {
    onStage('verifying_stopped');
    let current: ProductionStatus | undefined;
    try {
      current = await this.#gateway.status();
    } catch {
      // A failed preflight does not prove that shutdown is safe. Continue with
      // the formal STOP contract and require a subsequent status proof.
    }

    if (current && isCompositeStopped(current)) {
      await this.#waitForPortsClosed(onStage);
      return;
    }

    onStage('stopping_backend');
    let stopError: unknown;
    try {
      await this.#gateway.stop();
    } catch (error) {
      stopError = error;
    }

    const status = await this.#waitForStopped().catch(error => {
      throw stopError ?? error;
    });
    if (!isCompositeStopped(status)) {
      throw stopError
        ?? new DesktopLifecycleError('DESKTOP_BACKEND_STOP_UNCONFIRMED');
    }

    await this.#waitForPortsClosed(onStage);
  }

  async #waitForPortsClosed(onStage: StageListener): Promise<void> {
    onStage('closing_port');
    const deadline = this.#gateway.now() + this.#options.shutdownTimeoutMs;
    let last = {backendPortOpen: true, fastApiPortOpen: true};
    while (this.#gateway.now() <= deadline) {
      last = await this.#gateway.ports();
      if (!last.backendPortOpen && !last.fastApiPortOpen) {
        return;
      }
      await this.#gateway.wait(this.#options.pollIntervalMs);
    }
    if (last.fastApiPortOpen) {
      throw new DesktopLifecycleError('DESKTOP_FASTAPI_PORT_STILL_OPEN');
    }
    throw new DesktopLifecycleError('DESKTOP_BACKEND_PORT_STILL_OPEN');
  }

  async #waitForStopped(): Promise<ProductionStatus> {
    const deadline = this.#gateway.now() + this.#options.shutdownTimeoutMs;
    let lastError: unknown;
    while (this.#gateway.now() <= deadline) {
      try {
        const status = await this.#gateway.status();
        if (isCompositeStopped(status)) {
          return status;
        }
      } catch (error) {
        lastError = error;
      }
      await this.#gateway.wait(this.#options.pollIntervalMs);
    }
    if (lastError instanceof DesktopLifecycleError) {
      throw lastError;
    }
    throw new DesktopLifecycleError('DESKTOP_BACKEND_STOP_UNCONFIRMED');
  }
}
