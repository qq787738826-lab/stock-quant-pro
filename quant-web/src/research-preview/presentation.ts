import {
  AGENT_NAMES,
  AGENT_ORDER,
  FINAL_DECISION_NAMES,
  uniqueEvidence,
} from '../agent-team/presentation'
import type {
  AgentCode,
  AgentRun,
  Finding,
  JsonValue,
} from '../agent-team/types'
import type {
  AgentDisplaySlot,
  ComparisonRow,
  PreviewCandidate,
  PreviewIssue,
  PreviewQualification,
  PreviewTaskBundle,
  ReasonCodeEntry,
  RunErrorView,
  ScanResultSnapshot,
  TaskComparison,
} from './types'

const OBJECT_TAG = '[object Object]'

function isObject(value: unknown): value is Record<string, unknown> {
  return Object.prototype.toString.call(value) === OBJECT_TAG
}

function stringValue(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function numberValue(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function booleanValue(value: unknown): boolean | null {
  return typeof value === 'boolean' ? value : null
}

export function parseStoredJson(value: unknown): unknown {
  if (value == null) return null
  if (isObject(value) && 'value' in value) return parseStoredJson(value.value)
  if (typeof value !== 'string') return value
  try {
    return JSON.parse(value)
  } catch {
    return value
  }
}

export function normalizeScanCandidate(
  row: ScanResultSnapshot,
  historySymbols: ReadonlySet<string>,
  qualification: PreviewQualification,
): PreviewCandidate | null {
  const symbol = stringValue(row.symbol)
  if (!symbol) return null
  const parsedMetrics = parseStoredJson(row.metrics)
  const parsedReasons = parseStoredJson(row.filter_reasons)
  const metrics = isObject(parsedMetrics)
    ? Object.fromEntries(
      Object.entries(parsedMetrics).filter((entry): entry is [string, JsonValue] =>
        isJsonValue(entry[1]),
      ),
    )
    : {}
  const filterReasons = Array.isArray(parsedReasons)
    ? parsedReasons.map(stringValue).filter((item): item is string => item != null)
    : []
  return {
    rank: numberValue(row.rank_no),
    symbol,
    name: stringValue(row.name) ?? '暂无',
    score: numberValue(row.score),
    eligible: booleanValue(row.eligible),
    riskLevel: stringValue(row.risk_level),
    signalLevel: stringValue(row.signal_level),
    tradeDate: stringValue(row.trade_date),
    metrics,
    filterReasons,
    hasAgentResult: historySymbols.has(symbol),
    qualification,
    synthetic: false,
  }
}

function isJsonValue(value: unknown): value is JsonValue {
  if (value == null || typeof value === 'string' || typeof value === 'boolean') return true
  if (typeof value === 'number') return Number.isFinite(value)
  if (Array.isArray(value)) return value.every(isJsonValue)
  return isObject(value) && Object.values(value).every(isJsonValue)
}

export function buildAgentSlots(runs: AgentRun[] | null | undefined): AgentDisplaySlot[] {
  const byCode = new Map<AgentCode, AgentRun>()
  for (const run of runs ?? []) {
    if (!byCode.has(run.agentCode)) byCode.set(run.agentCode, run)
  }
  return AGENT_ORDER.map((agentCode) => ({
    agentCode,
    run: byCode.get(agentCode) ?? null,
  }))
}

export function runFindings(run: AgentRun | null): Finding[] {
  if (!run || !isObject(run.outputJson)) return []
  const raw = run.outputJson.findings
  if (!Array.isArray(raw)) return []
  return raw.flatMap((item) => {
    if (!isObject(item)) return []
    const findingId = stringValue(item.findingId)
    const code = stringValue(item.code)
    const severity = stringValue(item.severity)
    const title = stringValue(item.title)
    const detail = stringValue(item.detail)
    if (
      !findingId
      || !code
      || !title
      || !detail
      || !severity
      || !['INFO', 'WARN', 'HIGH', 'CRITICAL'].includes(severity)
    ) return []
    const evidenceIds = Array.isArray(item.evidenceIds)
      ? item.evidenceIds.map(stringValue).filter((value): value is string => value != null)
      : []
    return [{
      findingId,
      code,
      severity: severity as Finding['severity'],
      title,
      detail,
      evidenceIds,
    }]
  })
}

export function runErrors(run: AgentRun | null): RunErrorView[] {
  if (!run || !isObject(run.outputJson)) return []
  const raw = run.outputJson.errors
  if (!Array.isArray(raw)) return []
  return raw.flatMap((item) => {
    if (!isObject(item)) return []
    const code = stringValue(item.code)
    if (!code) return []
    return [{
      code,
      message: stringValue(item.message) ?? '暂无错误详情',
    }]
  })
}

function explicitReasonCodes(value: unknown, result: Set<string>): void {
  if (Array.isArray(value)) {
    value.forEach((item) => explicitReasonCodes(item, result))
    return
  }
  if (!isObject(value)) return
  for (const [key, child] of Object.entries(value)) {
    if (key === 'reasonCode') {
      const code = stringValue(child)
      if (code) result.add(code)
    } else {
      explicitReasonCodes(child, result)
    }
  }
}

export function extractReasonCodes(
  bundle: PreviewTaskBundle | null,
  previewIssues: PreviewIssue[] = [],
): ReasonCodeEntry[] {
  const entries: ReasonCodeEntry[] = []
  const seen = new Set<string>()
  const add = (entry: ReasonCodeEntry) => {
    const key = `${entry.source}|${entry.agentCode ?? ''}|${entry.code}`
    if (seen.has(key)) return
    seen.add(key)
    entries.push(entry)
  }
  previewIssues.forEach((issue) => add({
    code: issue.code,
    source: 'PREVIEW_UI',
    agentCode: null,
    detail: issue.detail,
  }))
  if (!bundle) return entries

  for (const run of bundle.runs) {
    for (const finding of runFindings(run)) {
      add({
        code: finding.code,
        source: 'AGENT_FINDING',
        agentCode: run.agentCode,
        detail: finding.detail,
      })
    }
    for (const error of runErrors(run)) {
      add({
        code: error.code,
        source: 'AGENT_ERROR',
        agentCode: run.agentCode,
        detail: error.message,
      })
    }
    const explicit = new Set<string>()
    explicitReasonCodes(run.outputJson, explicit)
    for (const code of explicit) {
      add({
        code,
        source: 'AGENT_ERROR',
        agentCode: run.agentCode,
        detail: null,
      })
    }
    if (run.errorMessage) {
      add({
        code: 'RUN_ERROR',
        source: 'RUN_ERROR',
        agentCode: run.agentCode,
        detail: run.errorMessage,
      })
    }
  }
  for (const finding of bundle.decision?.findings ?? []) {
    add({
      code: finding.code,
      source: 'AGENT_FINDING',
      agentCode: null,
      detail: finding.detail,
    })
  }
  for (const veto of bundle.vetoes) {
    add({
      code: veto.vetoCode,
      source: 'FORMAL_VETO',
      agentCode: veto.agentCode,
      detail: veto.reason,
    })
  }
  if (bundle.task.errorMessage) {
    add({
      code: 'TASK_ERROR',
      source: 'TASK_ERROR',
      agentCode: null,
      detail: bundle.task.errorMessage,
    })
  }
  return entries
}

export function displayValue(value: unknown): string {
  if (value == null || value === '') return '暂无'
  if (typeof value === 'boolean') return value ? '是' : '否'
  return String(value)
}

export function displayTime(value: string | null | undefined): string {
  if (!value) return '暂无'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

export function displayMetric(metrics: Record<string, JsonValue>, key: string): string {
  return displayValue(metrics[key])
}

export function backtestDisplayState(bundle: PreviewTaskBundle | null): string {
  const run = bundle?.runs.find((item) => item.agentCode === 'STRATEGY_BACKTEST')
  if (bundle?.synthetic) return 'TEST_DEMO_EXPLICIT'
  if (!run || run.status === 'INSUFFICIENT_DATA' || run.status === 'FAILED' || run.status === 'SKIPPED') {
    return 'UNAVAILABLE_WITH_REASON'
  }
  return 'AVAILABLE_EXISTING_RESULT'
}

export function buildResearchReport(
  bundle: PreviewTaskBundle | null,
  issues: PreviewIssue[],
): string {
  if (!bundle) {
    return [
      'UI_PRESENTATION_ONLY',
      '当前没有已加载的研究结果。',
      ...issues.map((issue) => `${issue.code}: ${issue.detail}`),
      '本页面用于研究产品形态验证，不构成投资建议、收益承诺或自动交易指令。',
    ].join('\n')
  }
  const task = bundle.task
  const reasons = extractReasonCodes(bundle, issues)
  const lines = [
    'UI_PRESENTATION_ONLY',
    `数据资格：${bundle.qualification}`,
    `合成数据：${bundle.synthetic ? '是' : '否'}`,
    `任务：${task.id} / ${task.symbol} / ${task.tradeDate}`,
    `规则版本：${task.ruleVersion}`,
    `任务状态：${task.status}`,
    `总控结论：${bundle.decision ? `${FINAL_DECISION_NAMES[bundle.decision.decision]} (${bundle.decision.decision})` : '暂无'}`,
    `总控摘要：${bundle.decision?.summary ?? '暂无'}`,
    '六个专业智能体：',
  ]
  for (const slot of buildAgentSlots(bundle.runs)) {
    lines.push(
      `- ${AGENT_NAMES[slot.agentCode]} (${slot.agentCode})：${slot.run?.status ?? '暂无'}；${slot.run?.summary ?? '暂无摘要'}`,
    )
  }
  lines.push('结构化原因：')
  if (reasons.length) {
    reasons.forEach((reason) => {
      lines.push(`- [${reason.source}] ${reason.code}${reason.agentCode ? ` / ${reason.agentCode}` : ''}`)
    })
  } else {
    lines.push('- 暂无')
  }
  lines.push('本报告只重排已持久化结果或显式固定演示内容，不新增评分、预测或权威结论。')
  lines.push('本页面用于研究产品形态验证，不构成投资建议、收益承诺或自动交易指令。')
  return lines.join('\n')
}

function comparisonRow(
  key: string,
  label: string,
  left: unknown,
  right: unknown,
): ComparisonRow {
  const leftText = displayValue(left)
  const rightText = displayValue(right)
  return { key, label, left: leftText, right: rightText, differs: leftText !== rightText }
}

export function compareTaskBundles(
  left: PreviewTaskBundle | null,
  right: PreviewTaskBundle | null,
): TaskComparison | null {
  if (!left || !right) return null
  const warnings: string[] = []
  if (left.synthetic !== right.synthetic || left.qualification !== right.qualification) {
    return {
      warnings: ['Demo与本地研究结果禁止进入同一对比。'],
      rows: [],
      agentRows: [],
    }
  }
  if (left.task.symbol !== right.task.symbol) warnings.push('股票不同，不应直接解释差异。')
  if (left.task.tradeDate !== right.task.tradeDate) warnings.push('交易日期不同，不应直接解释差异。')
  if (left.task.ruleVersion !== right.task.ruleVersion) warnings.push('规则版本不同，不应直接解释差异。')

  const leftEvidence = uniqueEvidence(left.evidence).items
  const rightEvidence = uniqueEvidence(right.evidence).items
  const leftReasons = extractReasonCodes(left).map((item) => item.code).sort().join(', ')
  const rightReasons = extractReasonCodes(right).map((item) => item.code).sort().join(', ')
  const rows = [
    comparisonRow('symbol', '股票', left.task.symbol, right.task.symbol),
    comparisonRow('tradeDate', '交易日期', left.task.tradeDate, right.task.tradeDate),
    comparisonRow('taskId', 'taskId', left.task.id, right.task.id),
    comparisonRow('ruleVersion', 'ruleVersion', left.task.ruleVersion, right.task.ruleVersion),
    comparisonRow('contextHash', 'contextHash', left.task.contextHash, right.task.contextHash),
    comparisonRow('taskStatus', '任务状态', left.task.status, right.task.status),
    comparisonRow('finalDecision', '总控结论', left.decision?.decision, right.decision?.decision),
    comparisonRow('score', '总控评分', left.decision?.score, right.decision?.score),
    comparisonRow('confidence', '总控置信度', left.decision?.confidence, right.decision?.confidence),
    comparisonRow('gateStatus', '总控门禁', left.decision?.gateStatus, right.decision?.gateStatus),
    comparisonRow('vetoed', '是否正式否决', left.decision?.vetoed, right.decision?.vetoed),
    comparisonRow('evidenceCount', '证据数量', leftEvidence.length, rightEvidence.length),
    comparisonRow('vetoCount', '正式否决数量', left.vetoes.length, right.vetoes.length),
    comparisonRow('reasonCodes', 'reasonCode集合', leftReasons || null, rightReasons || null),
  ]
  const leftByCode = new Map(left.runs.map((run) => [run.agentCode, run]))
  const rightByCode = new Map(right.runs.map((run) => [run.agentCode, run]))
  const agentRows = AGENT_ORDER.map((agentCode) => {
    const leftRun = leftByCode.get(agentCode)
    const rightRun = rightByCode.get(agentCode)
    return {
      agentCode,
      statusLeft: displayValue(leftRun?.status),
      statusRight: displayValue(rightRun?.status),
      scoreLeft: displayValue(leftRun?.score),
      scoreRight: displayValue(rightRun?.score),
      confidenceLeft: displayValue(leftRun?.confidence),
      confidenceRight: displayValue(rightRun?.confidence),
    }
  })
  return { warnings, rows, agentRows }
}
