import { app, BrowserWindow, shell } from 'electron';
import path from 'node:path';
let win = null;
async function create() { win = new BrowserWindow({ width: 1440, height: 900, minWidth: 1100, minHeight: 700, backgroundColor: '#09111e', webPreferences: { contextIsolation: true, sandbox: true, nodeIntegration: false } }); win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; }); const dev = !app.isPackaged; await win.loadURL(dev ? 'http://localhost:5173' : `file://${path.join(process.resourcesPath, 'web', 'index.html')}`); }
app.whenReady().then(create);
app.on('window-all-closed', () => { if (process.platform !== 'darwin')
    app.quit(); });
