import { app, BrowserWindow, Menu, shell, } from 'electron';
import path from 'node:path';
import { safeFailure } from './errors.js';
import { DesktopLifecycle } from './lifecycle.js';
import { PowerShellProductionGateway } from './production-gateway.js';
import { discoverRepository } from './repository.js';
import { desktopViewDataUrl, } from './ui.js';
import { activateExistingWindow, classifyNavigation, } from './window-policy.js';
const APPLICATION_URL = 'http://127.0.0.1:8080/';
class DesktopApplication {
    #window = null;
    #lifecycle = null;
    #startupPromise = null;
    #shutdownPromise = null;
    #renderQueue = Promise.resolve();
    #lastStartupFailure = null;
    #closing = false;
    #allowQuit = false;
    #clientLoaded = false;
    async run() {
        app.setName('Stock Quant Pro');
        app.setAppUserModelId('com.stockquant.pro');
        Menu.setApplicationMenu(null);
        this.#createWindow();
        await this.startup();
    }
    focus() {
        activateExistingWindow(this.#window);
    }
    handleBeforeQuit(event) {
        if (this.#allowQuit || !this.#window || this.#window.isDestroyed()) {
            return;
        }
        event.preventDefault();
        void this.shutdown();
    }
    startup() {
        if (this.#startupPromise) {
            return this.#startupPromise;
        }
        const attempt = this.#performStartup();
        this.#startupPromise = attempt;
        const settled = () => {
            if (this.#startupPromise === attempt) {
                this.#startupPromise = null;
            }
        };
        void attempt.then(settled, settled);
        return attempt;
    }
    shutdown() {
        if (this.#shutdownPromise) {
            return this.#shutdownPromise;
        }
        this.#closing = true;
        const attempt = this.#performShutdown();
        this.#shutdownPromise = attempt;
        const settled = () => {
            if (this.#shutdownPromise === attempt) {
                this.#shutdownPromise = null;
            }
        };
        void attempt.then(settled, settled);
        return attempt;
    }
    #createWindow() {
        const icon = path.join(app.getAppPath(), 'assets', 'icon.ico');
        const window = new BrowserWindow({
            title: 'Stock Quant Pro',
            width: 1600,
            height: 1000,
            minWidth: 1200,
            minHeight: 750,
            show: false,
            backgroundColor: '#07111c',
            icon,
            webPreferences: {
                nodeIntegration: false,
                contextIsolation: true,
                sandbox: true,
                webSecurity: true,
                allowRunningInsecureContent: false,
                devTools: !app.isPackaged,
            },
        });
        this.#window = window;
        window.once('ready-to-show', () => {
            if (!window.isDestroyed()) {
                window.show();
            }
        });
        window.on('close', event => {
            if (this.#allowQuit) {
                return;
            }
            event.preventDefault();
            void this.shutdown();
        });
        window.webContents.session.setPermissionRequestHandler((_webContents, _permission, callback) => callback(false));
        window.webContents.setWindowOpenHandler(({ url }) => {
            const decision = classifyNavigation(url);
            if (decision.kind === 'internal') {
                void window.loadURL(url);
            }
            else if (decision.kind === 'external') {
                void shell.openExternal(url);
            }
            else if (decision.kind === 'action') {
                this.#handleAction(decision.action);
            }
            return { action: 'deny' };
        });
        window.webContents.on('will-navigate', (event, url) => {
            const decision = classifyNavigation(url);
            if (decision.kind === 'internal') {
                return;
            }
            event.preventDefault();
            if (decision.kind === 'external') {
                void shell.openExternal(url);
            }
            else if (decision.kind === 'action') {
                this.#handleAction(decision.action);
            }
        });
        void this.#render({ kind: 'startup', stage: 'checking_service' });
    }
    async #performStartup() {
        this.#lastStartupFailure = null;
        this.#clientLoaded = false;
        try {
            if (!this.#lifecycle) {
                const binding = discoverRepository({
                    appPath: app.getAppPath(),
                    executablePath: process.execPath,
                    currentWorkingDirectory: process.cwd(),
                    packaged: app.isPackaged,
                    configuredRepositoryRoot: process.env.STOCK_QUANT_REPOSITORY_ROOT,
                });
                this.#lifecycle = new DesktopLifecycle(new PowerShellProductionGateway(binding));
            }
            await this.#lifecycle.ensureStarted(stage => {
                if (!this.#closing) {
                    void this.#render({ kind: 'startup', stage });
                }
            });
            if (this.#closing) {
                return;
            }
            await this.#render({ kind: 'startup', stage: 'loading_client' });
            const window = this.#window;
            if (!window || window.isDestroyed()) {
                return;
            }
            await window.loadURL(APPLICATION_URL);
            this.#clientLoaded = true;
        }
        catch (error) {
            const failure = safeFailure(error);
            this.#lastStartupFailure = failure;
            if (!this.#closing) {
                await this.#render({
                    kind: 'failure',
                    phase: 'startup',
                    failure,
                    canReturnToClient: false,
                });
            }
        }
    }
    async #performShutdown() {
        try {
            await this.#render({ kind: 'shutdown', stage: 'stopping_backend' });
            if (this.#startupPromise) {
                await this.#startupPromise.catch(() => undefined);
            }
            if (this.#lifecycle) {
                await this.#lifecycle.shutdown(stage => {
                    void this.#render({ kind: 'shutdown', stage });
                });
            }
            this.#allowQuit = true;
            const window = this.#window;
            if (window && !window.isDestroyed()) {
                window.destroy();
            }
            app.quit();
        }
        catch (error) {
            this.#closing = false;
            await this.#render({
                kind: 'failure',
                phase: 'shutdown',
                failure: safeFailure(error),
                canReturnToClient: this.#clientLoaded,
            });
        }
    }
    #handleAction(action) {
        if (action === 'retry' && !this.#closing) {
            void this.startup();
            return;
        }
        if (action === 'exit') {
            void this.shutdown();
            return;
        }
        if (action === 'retry-stop') {
            void this.shutdown();
            return;
        }
        if (action === 'return' && this.#clientLoaded && this.#window) {
            void this.#window.loadURL(APPLICATION_URL);
            return;
        }
        if (this.#lastStartupFailure) {
            void this.#render({
                kind: 'failure',
                phase: 'startup',
                failure: this.#lastStartupFailure,
                canReturnToClient: false,
            });
        }
    }
    #render(view) {
        this.#renderQueue = this.#renderQueue
            .catch(() => undefined)
            .then(async () => {
            const window = this.#window;
            if (window && !window.isDestroyed()) {
                await window.loadURL(desktopViewDataUrl(view));
            }
        });
        return this.#renderQueue;
    }
}
const hasSingleInstanceLock = app.requestSingleInstanceLock();
let desktop = null;
if (!hasSingleInstanceLock) {
    app.quit();
}
else {
    app.on('second-instance', () => desktop?.focus());
    app.on('before-quit', event => desktop?.handleBeforeQuit(event));
    app.on('activate', () => desktop?.focus());
    app.on('window-all-closed', () => {
        // The guarded close path destroys the window only after STOPPED and port 8080 closed.
    });
    void app.whenReady().then(async () => {
        desktop = new DesktopApplication();
        await desktop.run();
    });
}
