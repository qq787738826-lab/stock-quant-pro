import assert from 'node:assert/strict'
import test from 'node:test'
import {
  parseLauncherOutput,
  PowerShellProductionGateway,
} from '../dist-electron/production-gateway.js'
import {DesktopLifecycleError} from '../dist-electron/errors.js'

const binding = {
  repositoryRoot: 'D:\\StockQuantFixture',
  gitCommit: 'a'.repeat(40),
  launcherPath: 'D:\\StockQuantFixture\\start-stock-quant-pro.ps1',
}

test('launcher parser accepts only bounded uppercase contract fields', () => {
  const fields = parseLauncherOutput([
    'ignored secret-looking text',
    'STOCK_QUANT_BACKEND_STATUS=STOPPED',
    'STOCK_QUANT_PRODUCTION_AUTO_RECOVERY=DISARMED',
  ].join('\n'))
  assert.equal(fields.get('STOCK_QUANT_BACKEND_STATUS'), 'STOPPED')
  assert.equal(fields.has('ignored secret-looking text'), false)
})

test('gateway invokes only the fixed lifecycle script arguments', async () => {
  const calls = []
  const executor = async (executable, args, options) => {
    calls.push({executable, args, options})
    return {
      exitCode: 0,
      timedOut: false,
      stdout: [
        'STOCK_QUANT_BACKEND_STATUS=STOPPED',
        'STOCK_QUANT_PRODUCTION_AUTO_RECOVERY=DISARMED',
        'STOCK_QUANT_PRODUCTION_STATUS=STOPPED',
      ].join('\n'),
    }
  }
  const gateway = new PowerShellProductionGateway(
    binding,
    executor,
    process.execPath,
  )
  const status = await gateway.status()

  assert.deepEqual(status, {
    backendState: 'STOPPED', autoRecovery: 'DISARMED',
  })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].args.includes('-File'), true)
  assert.equal(calls[0].args.includes(binding.launcherPath), true)
  assert.equal(calls[0].args.includes('-Action'), true)
  assert.equal(calls[0].args.includes('Status'), true)
  assert.equal(calls[0].args.includes('-NoBrowser'), true)
  assert.equal(calls[0].args.includes('-Command'), false)
})

test('gateway exposes only a safe failure code from rejected output', async () => {
  const executor = async () => ({
    exitCode: 20,
    timedOut: false,
    stdout: [
      'arbitrary output that must not reach the UI',
      'STOCK_QUANT_STARTUP_REASON=M6_DATABASE_NOT_LISTENING',
    ].join('\n'),
  })
  const gateway = new PowerShellProductionGateway(
    binding,
    executor,
    process.execPath,
  )
  await assert.rejects(
    gateway.start(),
    error => error instanceof DesktopLifecycleError
      && error.code === 'M6_DATABASE_NOT_LISTENING',
  )
})
