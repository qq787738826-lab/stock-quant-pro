import type {
  HistoricalResearch,
  HistoricalStability,
  HistoricalWindowCoverage,
  Security,
} from './types'

export const securityKey = (security: Security) =>
  `${security.symbol}:${security.exchange}`

export function historicalBySecurity(history?: HistoricalResearch) {
  return new Map<string, HistoricalStability>(
    (history?.securities ?? []).map(value => [securityKey(value.security), value]))
}

export function displayHistoryStatus(value: HistoricalWindowCoverage) {
  return value.status === 'AVAILABLE'
    ? `完整 ${value.requestedSessions} 日`
    : `历史覆盖不足（缺 ${value.missingSessions} 日）`
}

export function displayHistoricalLabel(value?: string) {
  if (value === 'POST_HOC_RESEARCH') return '事后历史研究'
  if (value === 'HISTORICAL_RESEARCH_ONLY') return '仅限历史研究'
  if (value === 'PIT_PARTIAL') return '时点资格部分满足'
  return '历史研究'
}

export function displayHistoricalGrade(value?: string) {
  if (value === 'A') return 'A级 · 历史相对稳定'
  if (value === 'B') return 'B级 · 可观察'
  return 'C级 · 稳定性或证据不足'
}

export function displayHistoricalWindow(value?: string) {
  const current = String(value || '').match(/^CURRENT_(20|60|120|250)$/)
  if (current) return `当前${current[1]}日窗口`
  const rolling = String(value || '').match(/^ROLLING_20_(\d+)$/)
  if (rolling) return `60日内滚动20日窗口 ${rolling[1]}`
  return value || '无可用窗口'
}
