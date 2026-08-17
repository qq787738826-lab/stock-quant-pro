import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  buildAgentConclusionCards,
  buildAgentExecutionTrace,
  INTERNAL_ONLY_AGENT_PHASES,
} from '../src/agent-research/presentation.ts'

function run(agentRole, phase, options = {}) {
  return {
    runId: `AR_${phase}`,
    agentRole,
    phase,
    status: options.status ?? 'COMPLETED',
    promptVersion: 'M3_PROMPT_CATALOG_V3',
    modelProvider: 'BAILIAN',
    model: 'qwen3.7-plus',
    requestedTools: options.tools ?? ['RESEARCH_DATASET'],
    findings: options.findings ?? [{
      findingId: `F_${phase}`,
      agentRole,
      claimType: 'FACT',
      statement: `${phase}最终结论`,
      evidenceIds: ['E_1'],
      confidence: 0.7,
    }],
    issueCodes: [],
    reworkRequested: false,
    revised: options.revised ?? false,
    usage: { inputTokens: 100, outputTokens: 20, estimatedCost: 0.01, costCurrency: 'CNY' },
  }
}

const completeRuns = [
  run('RESEARCH_COORDINATOR', 'PLAN', { findings: [] }),
  run('DATA_ANALYST', 'DATA_TOOL_SELECTION', { findings: [] }),
  run('DATA_ANALYST', 'DATA_QUALITY'),
  run('MARKET_TECHNICAL', 'TECHNICAL_TOOL_SELECTION', { findings: [] }),
  run('MARKET_TECHNICAL', 'TECHNICAL_ANALYSIS'),
  run('STRATEGY_RESEARCH', 'STRATEGY_TOOL_SELECTION', { findings: [] }),
  run('STRATEGY_RESEARCH', 'STRATEGY_EXPERIMENTS'),
  run('RISK', 'RISK_TOOL_SELECTION', { findings: [] }),
  run('RISK', 'RISK_ASSESSMENT'),
  run('PORTFOLIO', 'PORTFOLIO_SYNTHESIS'),
  run('CRITIC_REVIEW', 'CRITIC_CHALLENGE'),
  run('PORTFOLIO', 'PORTFOLIO_REVISION', { status: 'REVISED', revised: true }),
  run('RESEARCH_COORDINATOR', 'FINAL_SYNTHESIS'),
]

test('主区域严格聚合为七角色最终阶段且不包含内部工具选择阶段', () => {
  const cards = buildAgentConclusionCards(completeRuns)
  assert.equal(cards.length, 7)
  assert.deepEqual(cards.map(card => card.agentRole), [
    'RESEARCH_COORDINATOR', 'DATA_ANALYST', 'MARKET_TECHNICAL',
    'STRATEGY_RESEARCH', 'RISK', 'PORTFOLIO', 'CRITIC_REVIEW',
  ])
  assert.equal(new Set(cards.map(card => card.agentRole)).size, 7)
  assert.deepEqual(cards.map(card => card.phase), [
    'FINAL_SYNTHESIS', 'DATA_QUALITY', 'TECHNICAL_ANALYSIS',
    'STRATEGY_EXPERIMENTS', 'RISK_ASSESSMENT',
    'PORTFOLIO_REVISION', 'CRITIC_CHALLENGE',
  ])
  assert.equal(cards.some(card => INTERNAL_ONLY_AGENT_PHASES.includes(card.phase)), false)
  assert.equal(cards.every(card => card.run?.findings.length > 0), true)
})

test('组合修订不存在时稳定回退到组合综合', () => {
  const cards = buildAgentConclusionCards(
    completeRuns.filter(item => item.phase !== 'PORTFOLIO_REVISION'))
  assert.equal(cards.find(card => card.agentRole === 'PORTFOLIO')?.phase,
    'PORTFOLIO_SYNTHESIS')
})

test('折叠执行轨迹保留全部模型阶段、工具、令牌和修订标记', () => {
  const trace = buildAgentExecutionTrace(completeRuns)
  assert.equal(trace.length, 13)
  assert.equal(trace[0].phase, 'PLAN')
  assert.equal(trace[0].requestedTools[0], 'RESEARCH_DATASET')
  assert.equal(trace[0].tokenTotal, 120)
  assert.equal(trace.find(item => item.phase === 'PORTFOLIO_REVISION')?.revised, true)
})

test('立即选股页面使用聚合组件而非直接遍历原始AgentRun', async () => {
  const page = await readFile(new URL('../src/views/ResearchSelectionWorkbench.vue', import.meta.url), 'utf8')
  const panel = await readFile(new URL('../src/components/AgentConclusionPanel.vue', import.meta.url), 'utf8')
  assert.equal(page.includes('v-for="agent in result.agentReport.agentRuns"'), false)
  assert.equal(page.includes('<AgentConclusionPanel'), true)
  assert.equal(panel.includes('data-agent-conclusion-card'), true)
  assert.equal(panel.includes('<summary>内部执行轨迹'), true)
  assert.equal(panel.includes('本轮没有可证据化的新结论'), false)
})
