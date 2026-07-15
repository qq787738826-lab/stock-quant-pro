import type { AgentCode, AgentRun, Evidence, JsonValue, TaskStatus } from './types'

export const AGENT_ORDER: readonly AgentCode[] = [
  'DATA_QUALITY',
  'MARKET_REGIME',
  'TECHNICAL_ANALYSIS',
  'STRATEGY_BACKTEST',
  'ANNOUNCEMENT_RISK',
  'POSITION_RISK',
]

export const AGENT_NAMES: Record<AgentCode, string> = {
  DATA_QUALITY: '数据质量',
  MARKET_REGIME: '市场环境',
  TECHNICAL_ANALYSIS: '技术分析',
  STRATEGY_BACKTEST: '策略回测',
  ANNOUNCEMENT_RISK: '公告风险',
  POSITION_RISK: '仓位风险',
}

const TERMINAL_STATUSES = new Set<TaskStatus>([
  'COMPLETED',
  'PARTIAL',
  'FAILED',
  'CANCELLED',
])

export function isTerminalTaskStatus(status: TaskStatus): boolean {
  return TERMINAL_STATUSES.has(status)
}

export function orderAgentRuns(runs: AgentRun[] | null | undefined): AgentRun[] {
  const byCode = new Map((runs ?? []).map((run) => [run.agentCode, run]))
  return AGENT_ORDER.flatMap((code) => {
    const run = byCode.get(code)
    return run ? [run] : []
  })
}

export function uniqueEvidence(evidence: Evidence[] | null | undefined): {
  items: Evidence[]
  duplicateIds: string[]
} {
  const seen = new Set<string>()
  const duplicateIds = new Set<string>()
  const items: Evidence[] = []
  for (const item of evidence ?? []) {
    if (seen.has(item.evidenceId)) {
      duplicateIds.add(item.evidenceId)
      continue
    }
    seen.add(item.evidenceId)
    items.push(item)
  }
  return { items, duplicateIds: [...duplicateIds] }
}

export function localIsoDate(now = new Date()): string {
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function formatJson(value: JsonValue): string {
  return JSON.stringify(value, null, 2)
}
