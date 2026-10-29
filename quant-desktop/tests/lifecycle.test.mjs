import assert from 'node:assert/strict'
import test from 'node:test'
import {DesktopLifecycle} from '../dist-electron/lifecycle.js'
import {DesktopLifecycleError} from '../dist-electron/errors.js'

class FakeGateway {
  constructor({statuses = [], starts = [], stops = [], health = [], ports = []} = {}) {
    this.statuses = [...statuses]
    this.starts = [...starts]
    this.stops = [...stops]
    this.healthValues = [...health]
    this.ports = [...ports]
    this.clock = 0
    this.calls = {status: 0, start: 0, stop: 0, health: 0, port: 0}
  }

  async status() {
    this.calls.status++
    return next(this.statuses)
  }

  async start() {
    this.calls.start++
    return next(this.starts)
  }

  async stop() {
    this.calls.stop++
    return next(this.stops)
  }

  async health() {
    this.calls.health++
    return next(this.healthValues)
  }

  async isBackendPortOpen() {
    this.calls.port++
    return next(this.ports)
  }

  async wait(milliseconds) {
    this.clock += milliseconds
  }

  now() {
    return this.clock
  }
}

function next(values) {
  if (values.length === 0) {
    throw new DesktopLifecycleError('DESKTOP_LIFECYCLE_CONTRACT_INVALID')
  }
  const value = values.length === 1 ? values[0] : values.shift()
  if (value instanceof Error) {
    throw value
  }
  return value
}

const running = {backendState: 'RUNNING', autoRecovery: 'ARMED'}
const stopped = {backendState: 'STOPPED', autoRecovery: 'DISARMED'}
const healthy = {status: 'HEALTHY', gitCommit: 'a'.repeat(40)}

test('STOPPED desktop startup uses formal START then waits for HEALTHY', async () => {
  const gateway = new FakeGateway({
    statuses: [stopped],
    starts: [running],
    health: [healthy],
  })
  const lifecycle = new DesktopLifecycle(gateway)
  const stages = []
  const result = await lifecycle.ensureStarted(stage => stages.push(stage))

  assert.equal(result.reusedExistingBackend, false)
  assert.deepEqual(gateway.calls, {
    status: 1, start: 1, stop: 0, health: 1, port: 0,
  })
  assert.deepEqual(stages, [
    'checking_service', 'starting_backend',
    'connecting_database', 'loading_client',
  ])
})

test('HEALTHY desktop startup reuses the existing Backend without START', async () => {
  const gateway = new FakeGateway({statuses: [running], health: [healthy]})
  const lifecycle = new DesktopLifecycle(gateway)
  const result = await lifecycle.ensureStarted(() => {})

  assert.equal(result.reusedExistingBackend, true)
  assert.equal(gateway.calls.start, 0)
  assert.equal(gateway.calls.health, 1)
})

test('shutdown runs once, confirms DISARMED, then confirms port 8080 closed', async () => {
  const gateway = new FakeGateway({
    statuses: [running, stopped],
    stops: [stopped],
    ports: [false],
  })
  const lifecycle = new DesktopLifecycle(gateway)
  const stages = []
  await Promise.all([
    lifecycle.shutdown(stage => stages.push(stage)),
    lifecycle.shutdown(stage => stages.push(stage)),
  ])

  assert.equal(gateway.calls.stop, 1)
  assert.equal(gateway.calls.status, 2)
  assert.equal(gateway.calls.port, 1)
  assert.deepEqual(stages, [
    'verifying_stopped', 'stopping_backend', 'closing_port',
  ])
})

test('failed startup can exit without another STOP when formally stopped', async () => {
  const gateway = new FakeGateway({statuses: [stopped], ports: [false]})
  const lifecycle = new DesktopLifecycle(gateway)
  const stages = []
  await lifecycle.shutdown(stage => stages.push(stage))

  assert.equal(gateway.calls.status, 1)
  assert.equal(gateway.calls.stop, 0)
  assert.equal(gateway.calls.port, 1)
  assert.deepEqual(stages, ['verifying_stopped', 'closing_port'])
})

test('a transient STOP client failure is accepted only after formal STOPPED proof', async () => {
  const gateway = new FakeGateway({
    statuses: [running, stopped],
    stops: [new DesktopLifecycleError('M6_HOST_BROKER_OPERATION_FAILED')],
    ports: [false],
  })
  const lifecycle = new DesktopLifecycle(gateway)
  await lifecycle.shutdown(() => {})
  assert.equal(gateway.calls.stop, 1)
  assert.equal(gateway.calls.status, 2)
})

test('STOP failure never substitutes a process kill and remains failed when unconfirmed', async () => {
  const failure = new DesktopLifecycleError('M6_HOST_BROKER_OPERATION_FAILED')
  const gateway = new FakeGateway({
    statuses: [running, running],
    stops: [failure],
  })
  const lifecycle = new DesktopLifecycle(gateway, {
    shutdownTimeoutMs: 0,
    pollIntervalMs: 1,
  })
  await assert.rejects(
    lifecycle.shutdown(() => {}),
    error => error === failure,
  )
  assert.equal(gateway.calls.stop, 1)
})

test('database and Broker failures propagate only their safe reason codes', async () => {
  for (const code of [
    'M6_DATABASE_NOT_LISTENING',
    'M6_HOST_BROKER_OPERATION_FAILED',
  ]) {
    const gateway = new FakeGateway({
      statuses: [new DesktopLifecycleError(code)],
    })
    const lifecycle = new DesktopLifecycle(gateway)
    await assert.rejects(
      lifecycle.ensureStarted(() => {}),
      error => error instanceof DesktopLifecycleError && error.code === code,
    )
    assert.equal(gateway.calls.start, 0)
  }
})

test('startup does not guess readiness and fails after bounded health polling', async () => {
  const gateway = new FakeGateway({
    statuses: [stopped],
    starts: [running],
    health: [{status: 'DEGRADED', gitCommit: 'a'.repeat(40)}],
  })
  const lifecycle = new DesktopLifecycle(gateway, {
    healthTimeoutMs: 2,
    pollIntervalMs: 1,
  })
  await assert.rejects(
    lifecycle.ensureStarted(() => {}),
    error => error instanceof DesktopLifecycleError
      && error.code === 'DESKTOP_BACKEND_HEALTH_TIMEOUT',
  )
  assert.ok(gateway.calls.health >= 2)
})
