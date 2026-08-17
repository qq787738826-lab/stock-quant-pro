import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  displayHistoricalGrade,
  displayHistoricalLabel,
  displayHistoricalWindow,
  displayHistoryStatus,
  historicalBySecurity,
} from '../src/research-selection/historical.ts'

test('历史研究标签、等级、窗口和覆盖状态使用明确中文', () => {
  assert.equal(displayHistoricalLabel('POST_HOC_RESEARCH'), '事后历史研究')
  assert.equal(displayHistoricalLabel('PIT_PARTIAL'), '时点资格部分满足')
  assert.equal(displayHistoricalGrade('A'), 'A级 · 历史相对稳定')
  assert.equal(displayHistoricalGrade('B'), 'B级 · 可观察')
  assert.equal(displayHistoricalGrade('C'), 'C级 · 稳定性或证据不足')
  assert.equal(displayHistoricalWindow('CURRENT_60'), '当前60日窗口')
  assert.equal(displayHistoricalWindow('ROLLING_20_3'),
    '60日内滚动20日窗口 3')
  assert.equal(displayHistoryStatus({
    requestedSessions: 120,
    status: 'INSUFFICIENT_HISTORY',
    availableSessions: 60,
    rangeStart: '2026-05-22',
    rangeEnd: '2026-08-14',
    missingSessions: 60,
  }), '历史覆盖不足（缺 60 日）')
})

test('历史稳定性按完整证券身份映射且不混淆交易所', () => {
  const history = {
    securities: [
      { security: { symbol: '600000', exchange: 'SSE' }, score: 61, grade: 'B' },
      { security: { symbol: '000001', exchange: 'SZSE' }, score: 52, grade: 'C' },
    ],
  }
  const values = historicalBySecurity(history)
  assert.equal(values.size, 2)
  assert.equal(values.get('600000:SSE')?.grade, 'B')
  assert.equal(values.get('000001:SZSE')?.score, 52)
})

test('立即选股结果明确分离当前研究、历史稳定性和Live Shadow', async () => {
  const page = await readFile(new URL(
    '../src/views/ResearchSelectionWorkbench.vue', import.meta.url), 'utf8')
  assert.equal(page.includes('<h4>当前研究</h4>'), true)
  assert.equal(page.includes('<h4>历史稳定性</h4>'), true)
  assert.equal(page.includes('<h4>Live Shadow验证</h4>'), true)
  assert.equal(page.includes('INSUFFICIENT_HISTORY'), true)
  assert.equal(page.includes('不代表历史实时影子业绩'), true)
  assert.equal(page.includes('<AgentConclusionPanel'), true)
})
