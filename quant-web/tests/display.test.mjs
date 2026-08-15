import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  DISPLAY_MAPPING_COUNT,
  displayAgentRole,
  displayDecision,
  displayReason,
  displayRisk,
  displayStatus,
  displayStrategy,
  displayTrigger,
  formatCurrency,
  formatDateTime,
} from '../src/localization/display.ts'

test('核心状态、风险和研究结论显示为简体中文', () => {
  const statuses = {
    READY: '就绪', HEALTHY: '正常', DEGRADED: '部分可用',
    ACTION_REQUIRED: '需要处理', ACTIVE: '运行中', IDLE: '空闲',
    FROZEN: '已冻结', COMPLETED: '已完成', FAILED: '失败',
    QUEUED: '排队中', RUNNING: '运行中', WATCH: '观察',
    RETAIN: '保留', DEMOTE: '降级', REPLACE: '替换',
  }
  for (const [source, expected] of Object.entries(statuses)) {
    assert.equal(displayStatus(source), expected)
  }
  const risks = { LOW: '低风险', MODERATE: '中等风险', HIGH: '高风险' }
  for (const [source, expected] of Object.entries(risks)) {
    assert.equal(displayRisk(source), expected)
  }
  const decisions = {
    RESEARCH_PREFERENCE: '存在研究偏好',
    INSUFFICIENT_EVIDENCE: '证据不足',
    INSUFFICIENT_SAMPLE: '样本不足',
    UNKNOWN: '未知',
  }
  for (const [source, expected] of Object.entries(decisions)) {
    assert.equal(displayDecision(source), expected)
  }
})

test('七个智能体、策略和触发来源有稳定中文名称', () => {
  const roles = {
    RESEARCH_COORDINATOR: '研究协调智能体',
    DATA_ANALYST: '数据分析智能体',
    MARKET_TECHNICAL: '市场技术智能体',
    STRATEGY_RESEARCH: '策略研究智能体',
    RISK: '风险智能体',
    PORTFOLIO: '组合智能体',
    CRITIC_REVIEW: '批判审查智能体',
  }
  for (const [source, expected] of Object.entries(roles)) {
    assert.equal(displayAgentRole(source), expected)
  }
  assert.equal(displayStrategy('MEAN_REVERSION_V1'), '均值回归')
  assert.equal(displayTrigger('SCHEDULED_SHADOW'), '自动影子研究')
})

test('普通时间、金额和脱敏错误原因适合中文用户阅读', () => {
  assert.equal(formatDateTime('2026-08-17T09:20:00Z'), '2026-08-17 17:20')
  assert.equal(formatCurrency(1000000, 0), '¥1,000,000')
  assert.equal(displayReason('M4_SCHEDULER_BROKER_SUBMIT_REJECTED'), '自动影子研究提交失败')
  assert.equal(displayReason('SOME_TUSHARE_RUNTIME_ERROR'), '市场数据服务异常')
  assert.ok(DISPLAY_MAPPING_COUNT >= 100)
})

test('核心普通用户页面不再使用英文主导航或直接渲染核心枚举', async () => {
  const files = [
    '../src/App.vue',
    '../src/views/Dashboard.vue',
    '../src/views/ResearchSelectionWorkbench.vue',
    '../src/views/ShadowResearchWorkbench.vue',
    '../src/views/AgentEvaluationWorkbench.vue',
    '../src/views/AgentResearchWorkbench.vue',
  ]
  const sources = await Promise.all(files.map(file => readFile(new URL(file, import.meta.url), 'utf8')))
  const merged = sources.join('\n')
  for (const literal of ["['/agent-research', 'Research']", "['/backtest', 'Strategy']",
    "['/shadow-research', 'Shadow']", "['/agent-evaluation', 'Evaluation']",
    '<span>Paper Equity</span>', '<span>Paper Return</span>', '<h1>Agent Evaluation</h1>']) {
    assert.equal(merged.includes(literal), false, `仍存在英文普通界面文案: ${literal}`)
  }
  for (const expression of ['{{ item.status }}', '{{ agent.agentRole }}',
    '{{ candidate.riskLevel }}', '{{ detail.run.researchAsOf }}']) {
    assert.equal(merged.includes(expression), false, `仍直接渲染内部值: ${expression}`)
  }
})
