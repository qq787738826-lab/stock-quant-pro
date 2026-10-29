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
    const reusedExistingBackend = current.backendState === 'RUNNING'
      && current.autoRecovery === 'ARMED';
    if (!reusedExistingBackend) {
      onStage('starting_backend');
      current = await this.#gateway.start();
      if (current.backendState !== 'RUNNING'
          || current.autoRecovery !== 'ARMED') {
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
        if (health.status === 'HEALTHY') {
          return;
        }
        if (health.status === 'BLOCKED') {
          throw new DesktopLifecycleError('DESKTOP_BACKEND_BLOCKED');
        }
      } catch (error) {
        if (error instanceof DesktopLifecycleError
            && (error.code === 'DESKTOP_BACKEND_VERSION_MISMATCH'
              || error.code === 'DESKTOP_BACKEND_BLOCKED')) {
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

    if (current?.backendState === 'STOPPED'
        && current.autoRecovery === 'DISARMED') {
      await this.#waitForPortClosed(onStage);
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
    if (status.backendState !== 'STOPPED'
        || status.autoRecovery !== 'DISARMED') {
      throw stopError
        ?? new DesktopLifecycleError('DESKTOP_BACKEND_STOP_UNCONFIRMED');
    }

    await this.#waitForPortClosed(onStage);
  }

  async #waitForPortClosed(onStage: StageListener): Promise<void> {
    onStage('closing_port');
    const deadline = this.#gateway.now() + this.#options.shutdownTimeoutMs;
    while (this.#gateway.now() <= deadline) {
      if (!await this.#gateway.isBackendPortOpen()) {
        return;
      }
      await this.#gateway.wait(this.#options.pollIntervalMs);
    }
    throw new DesktopLifecycleError('DESKTOP_BACKEND_PORT_STILL_OPEN');
  }

  async #waitForStopped(): Promise<ProductionStatus> {
    const deadline = this.#gateway.now() + this.#options.shutdownTimeoutMs;
    let lastError: unknown;
    while (this.#gateway.now() <= deadline) {
      try {
        const status = await this.#gateway.status();
        if (status.backendState === 'STOPPED'
            && status.autoRecovery === 'DISARMED') {
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
