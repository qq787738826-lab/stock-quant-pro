import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { DesktopLifecycleError } from './errors.js';
const COMMIT = /^[0-9a-f]{40}$/;
const ARTIFACT = path.join('quant-server', 'target', 'quant-server-1.3.1-research-production.jar');
const PROOF = `${ARTIFACT}.f1f-b2-proof.properties`;
const LAUNCHER = path.join('quant-server', 'scripts', 'start-stock-quant-pro.ps1');
function isFile(value) {
    try {
        return fs.statSync(value).isFile();
    }
    catch {
        return false;
    }
}
function readCurrentCommit(repositoryRoot) {
    try {
        const value = execFileSync('git', ['-C', repositoryRoot, 'rev-parse', 'HEAD'], { encoding: 'utf8', windowsHide: true, timeout: 10_000 }).trim();
        if (!COMMIT.test(value)) {
            throw new Error('invalid commit');
        }
        return value;
    }
    catch {
        throw new DesktopLifecycleError('DESKTOP_RUNTIME_BINDING_INVALID');
    }
}
function ancestors(value) {
    let current = path.resolve(value);
    try {
        if (fs.statSync(current).isFile()) {
            current = path.dirname(current);
        }
    }
    catch {
        current = path.dirname(current);
    }
    const values = [];
    while (true) {
        values.push(current);
        const parent = path.dirname(current);
        if (parent === current) {
            return values;
        }
        current = parent;
    }
}
function readPackagedConfig(appPath) {
    const configPath = path.join(appPath, 'dist-electron', 'runtime-config.json');
    if (!isFile(configPath)) {
        throw new DesktopLifecycleError('DESKTOP_RUNTIME_BINDING_MISSING');
    }
    try {
        const value = JSON.parse(fs.readFileSync(configPath, 'utf8'));
        if (value.schemaVersion !== 'STOCK_QUANT_DESKTOP_RUNTIME_V1'
            || typeof value.repositoryRoot !== 'string'
            || !path.isAbsolute(value.repositoryRoot)
            || typeof value.gitCommit !== 'string'
            || !COMMIT.test(value.gitCommit)) {
            throw new Error('invalid runtime config');
        }
        return value;
    }
    catch (error) {
        if (error instanceof DesktopLifecycleError) {
            throw error;
        }
        throw new DesktopLifecycleError('DESKTOP_RUNTIME_BINDING_INVALID');
    }
}
function validateRoot(candidate, expectedCommit) {
    const root = path.resolve(candidate);
    if (root.split(/[\\/]+/u).some(part => part === '.ai')) {
        throw new DesktopLifecycleError('STOCK_QUANT_REPOSITORY_NOT_FOUND');
    }
    if (!fs.existsSync(path.join(root, '.git'))) {
        throw new DesktopLifecycleError('STOCK_QUANT_REPOSITORY_NOT_FOUND');
    }
    const launcherPath = path.join(root, LAUNCHER);
    if (!isFile(launcherPath)) {
        throw new DesktopLifecycleError('DESKTOP_LAUNCHER_MISSING');
    }
    if (!isFile(path.join(root, ARTIFACT)) || !isFile(path.join(root, PROOF))) {
        throw new DesktopLifecycleError('DESKTOP_FORMAL_ARTIFACT_MISSING');
    }
    const gitCommit = readCurrentCommit(root);
    if (expectedCommit !== undefined && gitCommit !== expectedCommit) {
        throw new DesktopLifecycleError('DESKTOP_RUNTIME_BINDING_INVALID');
    }
    return { repositoryRoot: root, gitCommit, launcherPath };
}
export function discoverRepository(options) {
    const packagedConfig = options.packaged
        ? readPackagedConfig(options.appPath)
        : undefined;
    const candidates = new Set();
    if (options.configuredRepositoryRoot) {
        candidates.add(options.configuredRepositoryRoot);
    }
    if (packagedConfig) {
        candidates.add(packagedConfig.repositoryRoot);
    }
    if (!options.packaged) {
        for (const seed of [
            options.appPath,
            options.executablePath,
            options.currentWorkingDirectory,
        ]) {
            for (const candidate of ancestors(seed)) {
                candidates.add(candidate);
            }
        }
    }
    let mostUsefulError;
    for (const candidate of candidates) {
        try {
            return validateRoot(candidate, packagedConfig?.gitCommit);
        }
        catch (error) {
            if (error instanceof DesktopLifecycleError
                && error.code !== 'DESKTOP_LAUNCHER_MISSING') {
                mostUsefulError = error;
            }
        }
    }
    throw mostUsefulError
        ?? new DesktopLifecycleError('STOCK_QUANT_REPOSITORY_NOT_FOUND');
}
