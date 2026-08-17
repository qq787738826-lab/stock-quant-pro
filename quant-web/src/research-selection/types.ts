import type { ResearchReport } from '../agent-research/types'

export interface Security { symbol: string; exchange: string }
export interface QuantitativeScore {
  rank: number; security: Security; name: string; industry: string
  score: number; fiveDayReturn: number; twentyDayReturn: number
  sixtyDayReturn: number; annualizedVolatility: number
  maxDrawdown: number; sharpe: number; meanReversionZ: number
  trend: string; observationCount: number; explanations: string[]
  dataQualityPassed: boolean
}
export interface Candidate {
  rank: number; security: Security; name: string; industry: string
  quantitativeScore: number; recommendation: string; riskLevel: string
  confidence: number; supportingReasons: string[]; opposingReasons: string[]
  preferredStrategy: string; maxDrawdown: number; trend: string
  strategyComparison: Array<{ strategyCode: string; strategyVersion: string
    fingerprint: string; totalReturn: number; maxDrawdown: number
    sharpeRatio: number; turnover: number; excessReturn: number }>
  criticIssues: string[]
}
export interface HistoricalWindowCoverage {
  requestedSessions: number; status: 'AVAILABLE' | 'INSUFFICIENT_HISTORY'
  availableSessions: number; rangeStart: string; rangeEnd: string
  missingSessions: number; reason?: string
}
export interface HistoricalWindowMetrics {
  windowCode: string; sessionCount: number; rangeStart: string; rangeEnd: string
  totalReturn: number; costAdjustedReturn: number; maxDrawdown: number
  annualizedVolatility: number; sharpe: number; turnover: number
  winRate: number; tradeCount: number; bestStrategy: string
  bestStrategyReturn: number; worstStrategy: string; worstStrategyReturn: number
  positiveStrategyCount: number; strategyCount: number
}
export interface WalkForwardSummary {
  available: boolean; reason?: string; trainSessions: number
  testSessions: number; stepSessions: number; foldCount: number
  strategyFoldCount: number; averageOutOfSampleReturn: number
  worstOutOfSampleReturn: number; positiveFoldRatio: number
  maximumOutOfSampleDrawdown: number; tradeCount: number
  strictlyIsolated: boolean; noFutureDataLeakage: boolean
}
export interface HistoricalStability {
  security: Security; availableSessions: number; score: number
  grade: 'A' | 'B' | 'C'; dataCompletenessComponent: number
  multiWindowConsistencyComponent: number; outOfSampleComponent: number
  riskComponent: number; costAndSampleComponent: number
  multiWindowConsistency: number; multiStrategyConsistency: number
  bestWindow: string; bestWindowReturn: number; worstWindow: string
  worstWindowReturn: number; walkForward: WalkForwardSummary
  windows: HistoricalWindowMetrics[]; liveShadowSamples: number
  supportingEvidence: string[]; limitations: string[]
  noFutureDataLeakage: boolean
}
export interface HistoricalResearch {
  version: string; researchLabel: 'POST_HOC_RESEARCH' | 'HISTORICAL_RESEARCH_ONLY'
  pitQualification: 'PIT_PARTIAL'; availableSessions: number
  rangeStart: string; rangeEnd: string
  windowCoverage: HistoricalWindowCoverage[]; missingTradeDates: string[]
  securities: HistoricalStability[]; gradeDistribution: Record<'A' | 'B' | 'C', number>
  calendarCompleteThroughAnchor: boolean; knownAtQualified: boolean
  dataQualityPassed: boolean; noFutureDataLeakage: boolean
  datasetFingerprint: string
}
export interface SelectionSummary {
  runId: number; publicRunId: string; status: string; triggerMode: string
  researchAsOf: string; anchorTradeDate?: string; universeSize: number
  shortlistSize: number; candidateCount: number; decisionCode: string
  failureCategory?: string; failureReason?: string; createdAt: string
  completedAt?: string
}
export interface SelectionResult {
  contractVersion: string; runId: number; publicRunId: string; status: string
  triggerMode: string; researchAsOf: string; anchorTradeDate: string
  dataCoverage: { rangeStart: string; rangeEnd: string; requiredOpenSessions: number
    actualOpenSessions: number; securityCount: number; completeSecurityCount: number
    typedFactReadback: boolean; systemKnowledgeReadback: boolean
    formulaOnlyQfq: boolean; noFutureDataLeakage: boolean }
  historicalResearch?: HistoricalResearch
  ranking: QuantitativeScore[]; shortlist: QuantitativeScore[]
  candidates: Candidate[]; emptyResult: boolean; decisionCode: string
  agentReport: ResearchReport; shadowRunId?: number; paperEnabled: boolean
  realTradingEnabled: boolean; historicalLiveShadow: boolean
  timings: { dataPreparationMillis: number; quantitativeScanMillis: number
    strategyAnalysisMillis: number; agentResearchMillis: number; totalMillis: number }
  usage: { tushareProviderRequests: number; retryCount: number; modelCalls: number
    modelProviderRequests: number; inputTokens: number; outputTokens: number
    reasoningTokens: number; totalTokens: number; conservativeCostCny: number }
  lineage: { researchUniverseVersion: string; primaryWindow: number
    auxiliaryWindow: number; rankingVersion: string; modelProvider: string
    model: string; strategyVersion: string; historicalStabilityVersion?: string
    gitCommit: string; historicalDatasetFingerprint?: string }
}
