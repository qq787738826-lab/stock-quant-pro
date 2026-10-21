import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import { displayReason, displayStatus } from '../src/localization/display.ts'

const source = await readFile(new URL(
  '../src/views/DataCenter.vue', import.meta.url), 'utf8')

test('数据页面同时保留旧更新并展示独立正式行情同步入口', () => {
  assert.equal(source.includes('<h3>正式行情同步</h3>'), true)
  assert.equal(source.includes('手动同步到最新交易日'), true)
  assert.equal(source.includes('行情增量更新'), true)
  assert.equal(source.includes("api.post('/data/updates'"), true)
  assert.equal(source.includes("'/data/maintenance/sync-latest'"), true)
  assert.equal(source.includes("api.get('/data/maintenance/status')"), true)
})

test('正式同步只POST一次并以两秒GET轮询恢复状态', () => {
  assert.equal(source.match(/\/data\/maintenance\/sync-latest/g)?.length, 1)
  assert.equal(source.includes(
    'window.setInterval(pollMaintenance, 2000)'), true)
  assert.equal(source.includes('stopMaintenancePolling()'), true)
  assert.equal(source.includes(
    ':disabled="maintenanceSubmitting || maintenanceRunning"'), true)
  assert.equal(source.includes(
    '// Keep the original POST error. Never retry the POST automatically.'), true)
})

test('正式同步状态和原因使用稳定中文文案', () => {
  assert.equal(displayStatus('NOT_RUN'), '尚未运行')
  assert.equal(displayStatus('IN_PROGRESS'), '进行中')
  assert.equal(displayReason(
    'AUTOMATIC_MARKET_DATA_MAINTENANCE_WINDOW_INVALID'),
  '当前不在手动同步时间窗口，请在交易日 16:25–17:10 执行。')
  assert.equal(displayReason(
    'AUTOMATIC_MARKET_DATA_MAINTENANCE_PARTIAL_DATE_PRESENT'),
  '检测到部分交易日数据，已停止自动修复')
  assert.equal(displayReason('TUSHARE_CONNECT_TIMEOUT_NO_RESPONSE'),
    '行情数据服务连接超时，本次未自动重试')
  assert.equal(source.includes('行情已同步到最新完整交易日'), true)
  assert.equal(source.includes('正在同步行情，请稍候'), true)
  assert.equal(source.includes('同步失败：${displayReason(value.reason)}'), true)
})
