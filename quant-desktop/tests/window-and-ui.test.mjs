import assert from 'node:assert/strict'
import test from 'node:test'
import {renderDesktopView} from '../dist-electron/ui.js'
import {
  activateExistingWindow,
  classifyNavigation,
} from '../dist-electron/window-policy.js'

test('startup page contains the four required dark startup stages and no script', () => {
  const html = renderDesktopView({kind: 'startup', stage: 'starting_backend'})
  for (const label of [
    '正在检查服务', '正在启动后端', '正在连接数据库', '正在加载客户端',
  ]) {
    assert.match(html, new RegExp(label))
  }
  assert.match(html, /Stock Quant Pro/)
  assert.doesNotMatch(html, /<script/i)
})

test('startup failure offers retry and exit with a sanitized reason', () => {
  const html = renderDesktopView({
    kind: 'failure',
    phase: 'startup',
    failure: {code: 'M6_DATABASE_NOT_LISTENING', message: '数据库未启动。'},
    canReturnToClient: false,
  })
  assert.match(html, /Stock Quant Pro 启动失败/)
  assert.match(html, /重新尝试/)
  assert.match(html, /退出/)
  assert.match(html, /M6_DATABASE_NOT_LISTENING/)
})

test('navigation keeps local UI internal and sends only real web links external', () => {
  assert.deepEqual(classifyNavigation('http://127.0.0.1:8080/research'), {
    kind: 'internal',
  })
  assert.deepEqual(classifyNavigation('https://example.com/report'), {
    kind: 'external',
  })
  assert.deepEqual(classifyNavigation('file:///C:/Windows/System32/cmd.exe'), {
    kind: 'blocked',
  })
  assert.deepEqual(classifyNavigation('stock-quant-desktop:retry'), {
    kind: 'action', action: 'retry',
  })
})

test('second-instance activation restores and focuses only the existing window', () => {
  const calls = []
  const window = {
    isDestroyed: () => false,
    isMinimized: () => true,
    restore: () => calls.push('restore'),
    show: () => calls.push('show'),
    focus: () => calls.push('focus'),
  }
  activateExistingWindow(window)
  assert.deepEqual(calls, ['restore', 'show', 'focus'])
})
