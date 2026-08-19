import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const api = await readFile(new URL(
  '../src/research-selection/api.ts', import.meta.url), 'utf8')
const page = await readFile(new URL(
  '../src/views/ResearchSelectionWorkbench.vue', import.meta.url), 'utf8')

test('全主板规划接口使用受控长超时而不改变全局API超时', () => {
  assert.match(api, /MAINBOARD_PLANNING_TIMEOUT_MS = 120_000/)
  assert.match(api, /research-selection\/universe'[\s\S]*timeout: MAINBOARD_PLANNING_TIMEOUT_MS/)
  assert.match(api, /research-selection\/runs'[\s\S]*timeout: MAINBOARD_PLANNING_TIMEOUT_MS/)
})

test('Universe与历史结果独立加载且单接口失败不会清空整页', () => {
  assert.equal(page.includes('Promise.all(['), false)
  assert.equal(page.includes('Promise.allSettled(['), true)
  assert.equal(page.includes('loadHistoryAndResult()'), true)
  assert.equal(page.includes('loadUniversePanel()'), true)
  assert.equal(page.includes('universeLoadError'), true)
  assert.equal(page.includes('historyLoadError'), true)
  assert.equal(page.includes('resultLoadError'), true)
})

test('启动响应超时会核对历史而不是提示泛化失败或诱导重复提交', () => {
  assert.equal(page.includes('研究启动失败，系统未产生真实交易。'), false)
  assert.equal(page.includes('正在从历史记录核对，请勿重复提交'), true)
  assert.match(page, /isRequestTimeout\(cause\)\) await loadHistoryAndResult\(\)/)
})
