import { execFile } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { DesktopLifecycleError, isSafeCode, } from './errors.js';
const defaultExecutor = (executable, args, options) => new Promise(resolve => {
    execFile(executable, [...args], {
        cwd: options.cwd,
        encoding: 'utf8',
        windowsHide: true,
        timeout: options.timeout,
        maxBuffer: 256 * 1024,
        shell: false,
    }, (error, stdout) => {
        const processError = error;
        resolve({
            exitCode: processError === null
                ? 0
                : typeof processError.code === 'number'
                    ? processError.code
                    : 20,
            stdout: typeof stdout === 'string' ? stdout : '',
            timedOut: processError?.killed === true
                || processError?.code === 'ETIMEDOUT',
        });
    });
});
export function parseLauncherOutput(stdout) {
    const result = new Map();
    if (stdout.length > 256 * 1024) {
        throw new DesktopLifecycleError('DESKTOP_LIFECYCLE_CONTRACT_INVALID');
    }
    for (const rawLine of stdout.split(/\r?\n/u)) {
        const line = rawLine.trim();
        const match = /^([A-Z][A-Z0-9_]{2,127})=(.{0,2048})$/u.exec(line);
        if (match?.[1] && match[2] !== undefined) {
            result.set(match[1], match[2]);
        }
    }
    return result;
}
function normalizeBackendState(value) {
    if (value === 'RUNNING' || value === 'ALREADY_RUNNING') {
        return 'RUNNING';
    }
    if (value === 'STOPPED' || value === 'ALREADY_STOPPED') {
        return 'STOPPED';
    }
    throw new DesktopLifecycleError('DESKTOP_LIFECYCLE_CONTRACT_INVALID');
}
function normalizeAutoRecovery(value) {
    if (value === 'ARMED' || value === 'DISARMED' || value === 'EXHAUSTED') {
        return value;
    }
    throw new DesktopLifecycleError('DESKTOP_LIFECYCLE_CONTRACT_INVALID');
}
function statusFromOutput(values) {
    return {
        backendState: normalizeBackendState(values.get('STOCK_QUANT_BACKEND_STATUS')),
        autoRecovery: normalizeAutoRecovery(values.get('STOCK_QUANT_PRODUCTION_AUTO_RECOVERY')),
    };
}
function reasonFromOutput(values) {
    for (const key of [
        'STOCK_QUANT_STARTUP_REASON',
        'STOCK_QUANT_HOST_BROKER_REASON',
    ]) {
        const value = values.get(key);
        if (isSafeCode(value)) {
            return value;
        }
    }
    return 'M6_HOST_BROKER_OPERATION_FAILED';
}
export class PowerShellProductionGateway {
    #binding;
    #executor;
    #powerShellPath;
    constructor(binding, executor = defaultExecutor, powerShellPath) {
        this.#binding = binding;
        this.#executor = executor;
        const systemRoot = process.env.SystemRoot;
        this.#powerShellPath = powerShellPath ?? (systemRoot
            ? path.join(systemRoot, 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
            : '');
    }
    status() {
        return this.#run('Status', 90);
    }
    start() {
        return this.#run('Start', 600);
    }
    stop() {
        return this.#run('Stop', 120);
    }
    async #run(action, timeoutSeconds) {
        if (process.platform !== 'win32'
            || !this.#powerShellPath
            || !fs.existsSync(this.#powerShellPath)) {
            throw new DesktopLifecycleError('DESKTOP_POWERSHELL_UNAVAILABLE');
        }
        const args = [
            '-NoLogo',
            '-NoProfile',
            '-NonInteractive',
            '-ExecutionPolicy', 'Bypass',
            '-File', this.#binding.launcherPath,
            '-Action', action,
            '-NoBrowser',
            '-TimeoutSeconds', String(timeoutSeconds),
        ];
        const result = await this.#executor(this.#powerShellPath, args, {
            cwd: this.#binding.repositoryRoot,
            timeout: (timeoutSeconds + 15) * 1000,
        });
        if (result.timedOut) {
            throw new DesktopLifecycleError('DESKTOP_LIFECYCLE_TIMEOUT');
        }
        const values = parseLauncherOutput(result.stdout);
        if (result.exitCode !== 0) {
            throw new DesktopLifecycleError(reasonFromOutput(values));
        }
        const status = statusFromOutput(values);
        if (action === 'Start'
            && (status.backendState !== 'RUNNING'
                || status.autoRecovery !== 'ARMED')) {
            throw new DesktopLifecycleError('DESKTOP_LIFECYCLE_CONTRACT_INVALID');
        }
        if (action === 'Stop'
            && (status.backendState !== 'STOPPED'
                || status.autoRecovery !== 'DISARMED')) {
            throw new DesktopLifecycleError('DESKTOP_LIFECYCLE_CONTRACT_INVALID');
        }
        return status;
    }
    health() {
        return new Promise((resolve, reject) => {
            const request = http.get({
                protocol: 'http:',
                hostname: '127.0.0.1',
                port: 8080,
                path: '/api/system/health',
                timeout: 3_000,
                headers: { Accept: 'application/json' },
            }, response => {
                if (response.statusCode !== 200) {
                    response.resume();
                    reject(new DesktopLifecycleError('DESKTOP_BACKEND_BLOCKED'));
                    return;
                }
                let body = '';
                response.setEncoding('utf8');
                response.on('data', chunk => {
                    body += chunk;
                    if (body.length > 64 * 1024) {
                        request.destroy();
                    }
                });
                response.on('end', () => {
                    try {
                        const payload = JSON.parse(body);
                        const status = payload.data?.status;
                        const gitCommit = payload.data?.gitCommit;
                        if (payload.success !== true
                            || (status !== 'HEALTHY'
                                && status !== 'DEGRADED'
                                && status !== 'BLOCKED')
                            || typeof gitCommit !== 'string') {
                            throw new Error('invalid health payload');
                        }
                        if (gitCommit !== this.#binding.gitCommit) {
                            reject(new DesktopLifecycleError('DESKTOP_BACKEND_VERSION_MISMATCH'));
                            return;
                        }
                        resolve({ status, gitCommit });
                    }
                    catch (error) {
                        reject(error instanceof DesktopLifecycleError
                            ? error
                            : new DesktopLifecycleError('DESKTOP_BACKEND_BLOCKED'));
                    }
                });
            });
            request.on('timeout', () => request.destroy());
            request.on('error', () => reject(new DesktopLifecycleError('DESKTOP_BACKEND_HEALTH_TIMEOUT')));
        });
    }
    isBackendPortOpen() {
        return new Promise(resolve => {
            const socket = net.createConnection({ host: '127.0.0.1', port: 8080 });
            let settled = false;
            const finish = (open) => {
                if (settled) {
                    return;
                }
                settled = true;
                socket.destroy();
                resolve(open);
            };
            socket.setTimeout(750);
            socket.once('connect', () => finish(true));
            socket.once('timeout', () => finish(false));
            socket.once('error', () => finish(false));
        });
    }
    wait(milliseconds) {
        return new Promise(resolve => setTimeout(resolve, milliseconds));
    }
    now() {
        return Date.now();
    }
}
