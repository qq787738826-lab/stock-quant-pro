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
const presentationSource = read('src/research-preview/presentation.ts')
const viewSource = read('src/views/ResearchPreviewWorkbench.vue')
const chartSource = read('src/components/research-preview/AgentMetricsChart.vue')
const overviewSource = read('src/components/research-preview/ResearchOverviewPanel.vue')
const agentSource = read('src/components/research-preview/AgentRunSection.vue')
const evidenceSource = read('src/components/research-preview/EvidenceLineagePanel.vue')
const historySource = read('src/components/research-preview/HistoryComparisonPanel.vue')
const reportSource = read('src/components/research-preview/ResearchReportPanel.vue')
const auditSource = read('src/components/research-preview/TechnicalAuditDetails.vue')
const candidateSource = read('src/components/research-preview/CandidatePoolPanel.vue')
const taskOutcomeSource = read('src/components/research-preview/TaskOutcomePanel.vue')
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

const requiredSections = [
  ['overview', '研究总览'],
  ['agents', '六智能体'],
  ['evidence', '证据与审计'],
  ['history', '历史对比'],
  ['report', '综合报告'],
]
for (const [name, label] of requiredSections) {
  if (!viewSource.includes(`label="${label}" name="${name}"`)) {
    fail(`missing research preview section: ${name}/${label}`)
  }
}
if (!viewSource.includes('v-model="activeSection"')) fail('research preview tabs do not bind active section')
if (!stateSource.includes("return typeof value === 'string'") || !stateSource.includes(": 'overview'")) {
  fail('invalid section does not safely fall back to overview')
}
if (!stateSource.includes("const section = ref<PreviewSection>(safeSection(route.query.section))")) {
  fail('default/query research section initialization is missing')
}
if (!viewSource.includes('<ResearchOverviewPanel')) fail('ResearchOverviewPanel is missing from first section')

const expectedActions = new Map([
  ['REJECTED_BY_VETO', '因正式风险否决停止研究'],
  ['BLOCKED_BY_DATA_QUALITY', '等待数据质量修复'],
  ['INSUFFICIENT_DATA', '暂不形成研究结论'],
  ['RESEARCH_ONLY', '仅作研究记录'],
  ['WATCH', '继续观察'],
  ['PASS_TO_MANUAL_REVIEW', '进入人工研究复核'],
])
for (const [decision, action] of expectedActions) {
  if (!presentationSource.includes(`${decision}: '${action}'`)) {
    fail(`missing deterministic research action mapping: ${decision}`)
  }
}
if (overviewSource.includes('数据可靠性') || presentationSource.includes('数据可靠性')) {
  fail('ambiguous data reliability label remains in the research overview or report builder')
}
for (const label of ['数据质量门禁', '研究证据完整性']) {
  if (!overviewSource.includes(label)) fail(`overview is missing semantic field: ${label}`)
  if (!presentationSource.includes(label)) fail(`report builder is missing semantic field: ${label}`)
}
for (const functionName of ['dataQualityGateLabel', 'researchEvidenceCompletenessLabel']) {
  if (!presentationSource.includes(`export function ${functionName}`)) {
    fail(`missing deterministic presentation function: ${functionName}`)
  }
}
if (
  !presentationSource.includes("dataQuality.gateStatus === 'PASS') return '通过'")
  || !presentationSource.includes("bundle.decision?.decision === 'INSUFFICIENT_DATA') return '不足'")
) {
  fail('DEMO01 data-quality gate / research-evidence semantics are not frozen')
}
for (const forbiddenText of ['买入', '卖出', '加仓', '减仓', '目标价', '预计收益']) {
  if (sourceText.includes(forbiddenText)) fail(`research preview contains prohibited action wording: ${forbiddenText}`)
}

if (!auditSource.includes('<details>') || !auditSource.includes('技术审计详情')) {
  fail('technical audit fields are not grouped in collapsed details')
}
if (!agentSource.includes('<details class="technical-details">')) {
  fail('Agent technical details are not collapsed by default')
}
if (!evidenceSource.includes('class="evidence-item"') || !evidenceSource.includes('<details v-for=')) {
  fail('evidence entries are not collapsed by default')
}
if (!historySource.includes('<details class="comparison-section">')) {
  fail('history comparison is not collapsed by default')
}
if (!reportSource.includes('class="structured-report"')) fail('structured research report is missing')
if (!reportSource.includes('<details class="raw-report">')) {
  fail('raw report text is not an optional collapsed detail')
}
for (const heading of [
  '研究结论',
  '数据资格与限制',
  '六智能体摘要',
  '主要风险',
  '结构化原因',
  '证据索引',
  '技术审计摘要',
  '免责声明',
]) {
  if (!reportSource.includes(heading)) fail(`structured report section is missing: ${heading}`)
}
const expectedRiskTones = new Map([
  ['INFO', 'info'],
  ['WARN', 'warning'],
  ['HIGH', 'danger'],
  ['CRITICAL', 'danger'],
  ['FORMAL_VETO', 'formal-veto'],
])
for (const [level, tone] of expectedRiskTones) {
  if (!presentationSource.includes(`${level}: '${tone}'`)) {
    fail(`missing deterministic risk tone mapping: ${level} -> ${tone}`)
  }
}
if (!presentationSource.includes("?? 'neutral'")) {
  fail('unknown risk level does not fall back to neutral')
}
if (/INFO:\s*'(?:danger|formal-veto)'/.test(presentationSource)) {
  fail('INFO risk is incorrectly mapped to a severe risk tone')
}
if (
  !reportSource.includes(":class=\"['risk-item', `tone-${risk.tone}`]\"")
  || !reportSource.includes('.risk-item.tone-info')
  || !reportSource.includes('.risk-item.tone-warning')
  || !reportSource.includes('.risk-item.tone-danger')
  || !reportSource.includes('.risk-item.tone-formal-veto')
  || !reportSource.includes('.risk-item.tone-neutral')
) {
  fail('structured report risk tone classes are incomplete')
}
if (
  !candidateSource.includes(':class="{ selected: candidate.symbol === selectedSymbol }"')
  || !candidateSource.includes(':aria-current=')
) {
  fail('candidate selected state or keyboard-readable current state is missing')
}
const overviewHeaderBlock = overviewSource.match(/\.overview-header\s*\{([^}]*)\}/s)?.[1] ?? ''
if (
  !/display:\s*grid;/.test(overviewHeaderBlock)
  || !/grid-template-columns:\s*minmax\(0,\s*1fr\)\s+minmax\(260px,\s*360px\);/.test(overviewHeaderBlock)
) {
  fail('overview header does not use the stable two-column grid')
}
if (
  !overviewSource.includes('.security-identity, .qualification-summary { min-width: 0; }')
  || !overviewSource.includes('@media (max-width: 1400px)')
  || !overviewSource.includes('.overview-header { grid-template-columns: 1fr; gap: 14px; }')
) {
  fail('overview header does not provide the required min-width and responsive single-column fallback')
}
if (/position\s*:\s*absolute/i.test(overviewSource)) {
  fail('overview uses forbidden absolute positioning')
}
if (/margin(?:-[a-z]+)?\s*:\s*-\d/i.test(overviewSource)) {
  fail('overview uses a forbidden negative margin')
}
if (/(^|[;\s])height\s*:/m.test(overviewHeaderBlock)) {
  fail('overview header uses a fixed height')
}
if (/overflow\s*:\s*hidden/i.test(overviewSource)) {
  fail('overview hides overflowing text instead of allowing natural layout')
}
const responsiveMarkers = [
  [viewSource, 'overflow-x: hidden', 'page-level horizontal overflow containment'],
  [viewSource, '@media (max-width: 1100px)', 'workbench medium viewport breakpoint'],
  [viewSource, '@media (max-width: 760px)', 'workbench narrow viewport breakpoint'],
  [overviewSource, '@media (max-width: 1500px)', 'overview wide viewport breakpoint'],
  [overviewSource, '@media (max-width: 1100px)', 'overview medium viewport breakpoint'],
  [agentSource, 'grid-template-columns: repeat(3, minmax(0, 1fr))', 'three-column Agent layout'],
  [agentSource, '@media (max-width: 1450px)', 'two-column Agent breakpoint'],
  [agentSource, '@media (max-width: 820px)', 'single-column Agent breakpoint'],
  [candidateSource, 'max-height: 390px; overflow: auto', 'candidate internal scrolling'],
  [evidenceSource, 'max-height: 300px', 'evidence JSON height containment'],
  [reportSource, 'max-height: 340px', 'raw report height containment'],
  [taskOutcomeSource, '@media (max-width: 1250px)', 'risk summary responsive breakpoint'],
]
for (const [source, marker, description] of responsiveMarkers) {
  if (!source.includes(marker)) fail(`missing responsive marker: ${description}`)
}
for (const repetitiveFile of [
  'src/components/research-preview/CandidatePoolPanel.vue',
  'src/components/research-preview/EvidenceLineagePanel.vue',
  'src/components/research-preview/HistoryComparisonPanel.vue',
  'src/components/research-preview/ResearchReportPanel.vue',
  'src/components/research-preview/TaskOutcomePanel.vue',
]) {
  if (/>\s*TEST_DEMO_EXPLICIT\s*</.test(read(repetitiveFile))) {
    fail(`full demo identity is repeated outside approved primary areas: ${repetitiveFile}`)
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
const demo01 = (fixture.bundles ?? []).find((bundle) => bundle.task?.symbol === 'DEMO01')
const demo02 = (fixture.bundles ?? []).find((bundle) => bundle.task?.symbol === 'DEMO02')
if (!demo01 || demo01.decision?.decision !== 'INSUFFICIENT_DATA' || (demo01.vetoes ?? []).length !== 0) {
  fail('DEMO01 no longer represents data insufficiency without formal veto')
}
const demo01DataQuality = (demo01?.runs ?? []).find((run) => run.agentCode === 'DATA_QUALITY')
const demo01Backtest = (demo01?.runs ?? []).find((run) => run.agentCode === 'STRATEGY_BACKTEST')
if (
  demo01DataQuality?.status !== 'COMPLETED'
  || demo01DataQuality?.gateStatus !== 'PASS'
  || demo01Backtest?.status !== 'INSUFFICIENT_DATA'
) {
  fail('DEMO01 no longer maps to data-quality gate PASS and research-evidence insufficiency')
}
if (
  !demo02
  || demo02.decision?.decision !== 'REJECTED_BY_VETO'
  || !(demo02.vetoes ?? []).some((veto) => veto.agentCode === 'POSITION_RISK')
) {
  fail('DEMO02 no longer contains a POSITION_RISK formal veto')
}

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
console.log(`PASS five sections, overview fallback, selected candidate state`)
console.log(`PASS deterministic research actions and prohibited action wording absent`)
console.log(`PASS data-quality gate and research-evidence completeness semantics`)
console.log(`PASS stable overview grid with responsive single-column fallback`)
console.log(`PASS Agent/evidence/comparison/audit details collapsed by default`)
console.log(`PASS structured report with optional raw text`)
console.log(`PASS INFO/WARN/HIGH/CRITICAL/FORMAL_VETO risk tone mapping`)
console.log(`PASS 1920/1440/1366 responsive containment markers`)
console.log(`PASS ECharts resize and dispose lifecycle markers`)
console.log(`PASS DEMO01 insufficiency and DEMO02 POSITION_RISK veto`)
console.log(`PASS demo tasks: ${(fixture.bundles ?? []).length}; exact ordered agents: 6 each`)
console.log(`PASS demo evidenceId unique: ${allEvidenceIds.length}`)
console.log(`PASS demo fixture SHA-256: ${fixtureHash}`)
console.log(`PASS route/menu/disclaimer/qualification markers`)
console.log(`PASS FREE_PRODUCT_PREVIEW_GATE=BLOCKED`)
