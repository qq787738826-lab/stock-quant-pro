import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const view = await readFile(new URL(
  '../src/views/ResearchSelectionWorkbench.vue', import.meta.url), 'utf8')
const types = await readFile(new URL(
  '../src/research-selection/types.ts', import.meta.url), 'utf8')

test('candidate explanation renders all deterministic selection layers', () => {
  assert.match(view, /为什么入选与研究计划/)
  assert.match(view, /Top10按历史稳定性顺序进入七智能体研究/)
  for (const heading of [
    '入选路径', '当前量化分解', '历史稳定性分解', '四策略比较',
    '七智能体支持 / 反对', 'Critic意见和修正', '最终门槛检查',
    '研究买入计划', '研究卖出计划', '预计持有期限', 'Paper实际执行',
  ]) assert.match(view, new RegExp(heading))
  assert.match(view, /第一只未入选/)
  assert.match(view, /计划价格不等于成交价格/)
})

test('versioned contracts are optional for immutable legacy results', () => {
  assert.match(types, /selectionExplanations\?: SelectionExplanation\[\]/)
  assert.match(types, /researchTradePlans\?: ResearchTradePlan\[\]/)
  assert.match(types, /SELECTION_EXPLANATION_V1/)
  assert.match(types, /RESEARCH_TRADE_PLAN_V1/)
  assert.match(view, /历史版本未保存 Selection Explanation \/ Research Trade Plan/)
})

test('the user surface keeps plan math separate from real trading', () => {
  assert.match(view, /由Java确定性计算/)
  assert.match(view, /真实交易始终关闭/)
  assert.doesNotMatch(view, /submitBrokerOrder|placeRealOrder|券商下单/)
})
