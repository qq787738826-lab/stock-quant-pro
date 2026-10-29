import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'

const desktopRoot = path.resolve(import.meta.dirname, '..')
const repositoryRoot = path.resolve(desktopRoot, '..')

test('Electron renderer is isolated and has no command bridge', async () => {
  const main = await readFile(path.join(desktopRoot, 'src', 'main.ts'), 'utf8')
  const gateway = await readFile(
    path.join(desktopRoot, 'src', 'production-gateway.ts'), 'utf8',
  )
  assert.match(main, /nodeIntegration:\s*false/)
  assert.match(main, /contextIsolation:\s*true/)
  assert.match(main, /sandbox:\s*true/)
  assert.doesNotMatch(main, /preload\s*:/)
  assert.doesNotMatch(main, /ipcMain|ipcRenderer|contextBridge/)
  assert.doesNotMatch(`${main}\n${gateway}`, /taskkill|Stop-Process|java\.exe/i)
  assert.doesNotMatch(gateway, /-Command/)
  assert.match(gateway, /shell:\s*false/)
})

test('single-instance lock is acquired before DesktopApplication is created', async () => {
  const main = await readFile(path.join(desktopRoot, 'src', 'main.ts'), 'utf8')
  const lock = main.indexOf('app.requestSingleInstanceLock()')
  const create = main.indexOf('desktop = new DesktopApplication()')
  assert.ok(lock >= 0 && create > lock)
  assert.match(main, /app\.on\('second-instance'/)
})

test('NSIS configuration creates both desktop and Start Menu shortcuts', async () => {
  const value = JSON.parse(await readFile(
    path.join(desktopRoot, 'package.json'), 'utf8',
  ))
  assert.equal(value.build.productName, 'Stock Quant Pro')
  assert.equal(value.build.win.target, 'nsis')
  assert.equal(value.build.nsis.createDesktopShortcut, 'always')
  assert.equal(value.build.nsis.createStartMenuShortcut, true)
  assert.equal(value.build.nsis.shortcutName, 'Stock Quant Pro')
})

test('formal lifecycle projection stays provider-free and branch-scoped', async () => {
  const invoker = await readFile(path.join(
    repositoryRoot,
    'quant-server', 'scripts', 'host-broker',
    'invoke-stock-quant-host-broker.ps1',
  ), 'utf8')
  const launcher = await readFile(path.join(
    repositoryRoot,
    'quant-server', 'scripts', 'start-stock-quant-pro.ps1',
  ), 'utf8')
  const broker = await readFile(path.join(
    repositoryRoot,
    'quant-server', 'scripts', 'host-broker',
    'stock-quant-host-broker.ps1',
  ), 'utf8')
  const branch = 'codex/1.4.0-v1-windows-desktop-client'
  const invokerSelection = invoker.indexOf("$Operation -eq 'RUN_RESEARCH_SELECTION'")
  const invokerLifecycle = invoker.indexOf(
    "$Operation -in @('START_RESEARCH_PRODUCTION'",
    invokerSelection,
  )
  assert.ok(invokerSelection >= 0 && invokerLifecycle > invokerSelection)
  assert.doesNotMatch(invoker.slice(invokerSelection, invokerLifecycle),
    new RegExp(branch.replaceAll('.', '\\.')))
  assert.match(invoker.slice(invokerLifecycle, invokerLifecycle + 1200),
    new RegExp(branch.replaceAll('.', '\\.')))
  assert.match(invoker, /STOCK_QUANT_BACKEND_STATUS=/)
  assert.match(invoker, /STOCK_QUANT_PRODUCTION_AUTO_RECOVERY=/)
  assert.match(invoker, /STOCK_QUANT_PRODUCTION_PROVIDER_CALLS=0/)
  assert.match(launcher, /STOCK_QUANT_PRODUCTION_STATUS=STOPPED/)
  assert.match(launcher, /status -eq 'BLOCKED'/)
  assert.doesNotMatch(launcher, /taskkill|Stop-Process|java\.exe/i)
  assert.match(broker, /M6_PRODUCTION_PROCESS_IDENTITY_INVALID/)
  assert.match(broker, /\/api\/system\/lifecycle\/stop/)
  assert.doesNotMatch(mainCommandSurface(broker), /RUN_RESEARCH_SELECTION/)
})

test('STOPPED status returns before HTTP health and remains fail-closed otherwise', async () => {
  const launcher = await readFile(path.join(
    repositoryRoot,
    'quant-server', 'scripts', 'start-stock-quant-pro.ps1',
  ), 'utf8')
  const stopped = launcher.indexOf(
    "if ($Action -eq 'Status' -and $backendState -eq 'STOPPED')",
  )
  const health = launcher.indexOf('$health = Get-SystemHealth', stopped)
  const runningGuard = launcher.indexOf(
    "$backendState -notin @('RUNNING', 'ALREADY_RUNNING')",
    stopped,
  )
  assert.ok(stopped >= 0 && runningGuard > stopped && health > runningGuard)
  assert.match(launcher, /STOCK_QUANT_PRODUCTION_AUTO_RECOVERY=/)
  assert.match(launcher, /Get-StockQuantLauncherOutputValue/)
})

function mainCommandSurface(broker) {
  const start = broker.indexOf('function Invoke-ResearchProductionStart')
  const end = broker.indexOf('function Read-SanitizedBrokerResult', start)
  return broker.slice(start, end)
}
