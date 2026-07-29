import { createHash } from 'node:crypto'
import {
  existsSync,
  readFileSync,
  readdirSync,
  statSync,
} from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const webRoot = resolve(scriptDir, '..')
const repoRoot = resolve(webRoot, '..')
const failures = []

function fail(message) {
  failures.push(message)
}

function read(relativePath) {
  const absolutePath = resolve(webRoot, relativePath)
  if (!existsSync(absolutePath)) {
    fail(`missing file: ${relativePath}`)
    return ''
  }
  return readFileSync(absolutePath, 'utf8')
}

function filesRecursively(directory) {
  return readdirSync(directory).flatMap((entry) => {
    const absolute = join(directory, entry)
    return statSync(absolute).isDirectory() ? filesRecursively(absolute) : [absolute]
  })
}

const previewRoot = resolve(webRoot, 'src/research-preview')
const componentRoot = resolve(webRoot, 'src/components/research-preview')
const viewPath = resolve(webRoot, 'src/views/ResearchPreviewWorkbench.vue')
const previewFiles = [
  ...filesRecursively(previewRoot),
  ...filesRecursively(componentRoot),
  viewPath,
].filter((file) => /\.(?:ts|vue|json)$/.test(file))

const sourceText = previewFiles
  .filter((file) => !file.endsWith('.json'))
  .map((file) => readFileSync(file, 'utf8'))
  .join('\n')

for (const method of ['post', 'put', 'patch', 'delete']) {
  if (new RegExp(`\\bapi\\.${method}\\s*\\(`, 'i').test(sourceText)) {
    fail(`research preview contains forbidden api.${method}`)
  }
}
if (/\bfetch\s*\(/.test(sourceText)) fail('research preview contains native fetch')

for (const forbiddenImport of [
  'createAgentTask',
  'createShadowBatch',
  'createShadowReview',
  'cancelShadowBatch',
  'startScan',
  'startBacktest',
  'syncHistory',
  'refreshAndCheckRisk',
]) {
  if (new RegExp(`\\b${forbiddenImport}\\b`).test(sourceText)) {
    fail(`research preview imports or references forbidden action: ${forbiddenImport}`)
  }
}

const apiSource = read('src/research-preview/api.ts')
const stateSource = read('src/research-preview/useResearchPreview.ts')
const chartSource = read('src/components/research-preview/AgentMetricsChart.vue')
const requiredGetPaths = [
  '/scans/history',
  '/scans/latest-official-task',
  '/scans/latest-task',
  '/scans/${taskId}',
  '/scans/${taskId}/results',
]
for (const endpoint of requiredGetPaths) {
  if (!apiSource.includes(endpoint)) fail(`missing read-only scan endpoint: ${endpoint}`)
}
if (!apiSource.includes('getAgentTaskHistory')) fail('missing GET Agent history integration')
if (!stateSource.includes("mode.value = 'TEST_DEMO_EXPLICIT'")) {
  fail('explicit demo mode transition is missing')
}
if (/catch\s*\([^)]*\)\s*\{[^}]*applyDemo\s*\(/s.test(stateSource)) {
  fail('local API error silently falls back to demo mode')
}
for (const lifecycleMarker of ['ResizeObserver', 'chart?.resize()', 'chart?.dispose()']) {
  if (!chartSource.includes(lifecycleMarker)) {
    fail(`ECharts lifecycle marker is missing: ${lifecycleMarker}`)
  }
}

const fixtureSource = read('src/research-preview/demo.fixture.json')
let fixture
try {
  fixture = JSON.parse(fixtureSource)
} catch (error) {
  fail(`invalid demo fixture JSON: ${error.message}`)
  fixture = {}
}

const expectedAgents = [
  'DATA_QUALITY',
  'MARKET_REGIME',
  'TECHNICAL_ANALYSIS',
  'STRATEGY_BACKTEST',
  'ANNOUNCEMENT_RISK',
  'POSITION_RISK',
]

if (fixture.mode !== 'TEST_DEMO_EXPLICIT') fail('demo mode is not TEST_DEMO_EXPLICIT')
if (fixture.qualification !== 'TEST_DEMO_EXPLICIT') fail('demo qualification is not TEST_DEMO_EXPLICIT')
if (fixture.synthetic !== true) fail('demo synthetic flag is not true')
if (!Array.isArray(fixture.candidates) || fixture.candidates.length < 2) fail('demo candidate pool is incomplete')
for (const candidate of fixture.candidates ?? []) {
  if (!/^DEMO\d{2}$/.test(candidate.symbol ?? '')) fail(`invalid demo symbol: ${candidate.symbol}`)
  if (candidate.synthetic !== true) fail(`demo candidate ${candidate.symbol} is not synthetic`)
  if (candidate.qualification !== 'TEST_DEMO_EXPLICIT') {
    fail(`demo candidate ${candidate.symbol} has invalid qualification`)
  }
}

const allEvidenceIds = []
for (const bundle of fixture.bundles ?? []) {
  if (bundle.synthetic !== true || bundle.qualification !== 'TEST_DEMO_EXPLICIT') {
    fail(`demo task ${bundle.task?.id} is not explicitly synthetic`)
  }
  if (!/^DEMO\d{2}$/.test(bundle.task?.symbol ?? '')) {
    fail(`demo task uses a real or invalid symbol: ${bundle.task?.symbol}`)
  }
  if (!Number.isInteger(bundle.task?.id) || bundle.task.id >= 0) {
    fail(`demo task does not use a negative local placeholder id: ${bundle.task?.id}`)
  }
  const codes = (bundle.runs ?? []).map((run) => run.agentCode)
  if (JSON.stringify(codes) !== JSON.stringify(expectedAgents)) {
    fail(`demo task ${bundle.task?.id} does not contain exact ordered six agents`)
  }
  if ((bundle.runs ?? []).length !== 6) fail(`demo task ${bundle.task?.id} has a seventh or missing agent`)
  for (const run of bundle.runs ?? []) {
    if (run.veto === true && run.agentCode !== 'POSITION_RISK') {
      fail(`non-POSITION_RISK demo veto: ${run.agentCode}`)
    }
  }
  for (const veto of bundle.vetoes ?? []) {
    if (veto.agentCode !== 'POSITION_RISK') fail(`formal demo veto from ${veto.agentCode}`)
  }
  const runIds = (bundle.runs ?? []).map((run) => run.id)
  if (JSON.stringify(bundle.decision?.sourceRunIds ?? []) !== JSON.stringify(runIds)) {
    fail(`demo task ${bundle.task?.id} sourceRunIds do not match six runs`)
  }
  if (/^[0-9a-f]{64}$/.test(bundle.task?.contextHash ?? '')) {
    fail(`demo task ${bundle.task?.id} contextHash masquerades as a real SHA-256`)
  }
  for (const evidence of bundle.evidence ?? []) allEvidenceIds.push(evidence.evidenceId)
}
if (new Set(allEvidenceIds).size !== allEvidenceIds.length) fail('demo evidenceId values are not unique')

const forbiddenMarketKeys = new Set([
  'open',
  'high',
  'low',
  'close',
  'price',
  'volume',
  'amount',
  'turnoverrate',
  'latestclose',
  'buylow',
  'buyhigh',
  'stoploss',
  'target1',
  'target2',
  'totalreturn',
  'annualizedreturn',
  'profit',
])

function inspectFixture(value, path = 'fixture') {
  if (Array.isArray(value)) {
    value.forEach((item, index) => inspectFixture(item, `${path}[${index}]`))
    return
  }
  if (value == null || typeof value !== 'object') return
  for (const [key, child] of Object.entries(value)) {
    const normalized = key.replaceAll('_', '').toLowerCase()
    if (forbiddenMarketKeys.has(normalized)) fail(`demo contains forbidden real-market field key: ${path}.${key}`)
    inspectFixture(child, `${path}.${key}`)
  }
}
inspectFixture(fixture)

if (/(BaoStock|AKShare|Tencent|Sina|Eastmoney|CNINFO|iFinD)/i.test(fixtureSource)) {
  fail('demo fixture contains a Provider name')
}

const routeSource = read('src/router.ts')
if (!routeSource.includes("path: '/research-preview'")) fail('research preview route is missing')
const appSource = read('src/App.vue')
const researchIndex = appSource.indexOf("['/research-preview','研究预览']")
const teamIndex = appSource.indexOf("['/agent-team','智能体团队']")
const shadowIndex = appSource.indexOf("['/agent-shadow','影子观测']")
if (researchIndex < 0 || teamIndex < 0 || shadowIndex < 0) fail('research preview menu entries are incomplete')
if (!(researchIndex < teamIndex && teamIndex < shadowIndex)) fail('AI menu order is not research preview -> agent team -> shadow')

const requiredPresentationText = [
  '本页面用于研究产品形态验证，不构成投资建议、收益承诺或自动交易指令。',
  'EXISTING_RESEARCH_SNAPSHOT',
  'RESEARCH_HISTORICAL_UNVERIFIED',
  'TEST_DEMO_EXPLICIT',
  'SYNTHETIC',
  'NOT_REAL_MARKET_RESULT',
  'PREVIEW_LOCAL_API_UNAVAILABLE',
  'PREVIEW_SCAN_RESULTS_EMPTY',
  'PREVIEW_AGENT_RESULT_NOT_FOUND',
  'UI_PRESENTATION_ONLY',
]
for (const text of requiredPresentationText) {
  if (!sourceText.includes(text)) fail(`missing required presentation marker: ${text}`)
}

const currentState = readFileSync(resolve(repoRoot, 'docs/agent-team/CURRENT_STATE.md'), 'utf8')
if (!currentState.includes('FREE_PRODUCT_PREVIEW_GATE=BLOCKED')) {
  fail('FREE_PRODUCT_PREVIEW_GATE is not BLOCKED in CURRENT_STATE')
}
if (/当前正式状态[^\n]*FREE_PRODUCT_PREVIEW_GATE=PASS/.test(currentState)) {
  fail('FREE_PRODUCT_PREVIEW_GATE was prematurely set to PASS')
}

const fixtureHash = createHash('sha256').update(fixtureSource, 'utf8').digest('hex')
const fixtureHashAgain = createHash('sha256').update(fixtureSource, 'utf8').digest('hex')
if (fixtureHash !== fixtureHashAgain) fail('demo fixture hash is not deterministic')

if (failures.length) {
  for (const failure of failures) console.error(`FAIL ${failure}`)
  process.exit(1)
}

console.log(`PASS research preview files checked: ${previewFiles.length}`)
console.log(`PASS dedicated API is GET-only; native fetch and write actions absent`)
console.log(`PASS local API errors do not silently activate demo mode`)
console.log(`PASS ECharts resize and dispose lifecycle markers`)
console.log(`PASS demo tasks: ${(fixture.bundles ?? []).length}; exact ordered agents: 6 each`)
console.log(`PASS demo evidenceId unique: ${allEvidenceIds.length}`)
console.log(`PASS demo fixture SHA-256: ${fixtureHash}`)
console.log(`PASS route/menu/disclaimer/qualification markers`)
console.log(`PASS FREE_PRODUCT_PREVIEW_GATE=BLOCKED`)
