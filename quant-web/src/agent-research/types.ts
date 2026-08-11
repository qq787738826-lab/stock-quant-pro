export type ClaimType = 'FACT' | 'INFERENCE' | 'HYPOTHESIS' | 'RECOMMENDATION' | 'UNKNOWN'

export interface ReportSummary {
  taskId: string
  status: 'SUCCEEDED' | 'INSUFFICIENT_EVIDENCE' | 'FAILED_VALIDATION'
  completedAt: string
  securityCount: number
  strategyCount: number
  preferredStrategy: string
  riskLevel: string
  confidence: number
  agentRuns: number
  toolCalls: number
  researchFingerprint: string
}

export interface Evidence {
  evidenceId: string
  sourceTool: string
  sourceFingerprint: string
  observedAt: string
  statement: string
}

export interface AgentFinding {
  findingId: string
  agentRole: string
  claimType: ClaimType
  statement: string
  evidenceIds: string[]
  confidence: number
}

export interface AgentRun {
  runId: string
  agentRole: string
  phase: string
  status: string
  promptVersion: string
  modelProvider: string
  model: string
  requestedTools: string[]
  findings: AgentFinding[]
  issueCodes: string[]
  reworkRequested: boolean
  revised: boolean
  usage: { inputTokens: number; outputTokens: number; estimatedCostUsd: number }
}

export interface StrategyExperiment {
  strategyCode: string
  strategyVersion: string
  parameters: Record<string, string>
  backtestFingerprint: string
  finalEquity: number
  totalReturn: number
  sharpeRatio: number
  maxDrawdown: number
  turnover: number
  excessReturn: number
  fillCount: number
  accountingInvariant: boolean
  lookAheadGuard: boolean
  outOfSampleEvaluated: boolean
  trainReturn: number
  testReturn: number
  walkForwardFolds: number
  overfittingFlag: boolean
}

export interface ResearchReport {
  reportVersion: string
  runtimeVersion: string
  teamVersion: string
  toolGatewayVersion: string
  status: string
  task: {
    taskId: string
    objective: string
    securities: Array<{ symbol: string; exchange: string }>
    rangeStart: string
    rangeEnd: string
    knowledgeCutoff: string
  }
  dataset: {
    datasetVersion: string
    datasetFingerprint: string
    knowledgeMode: string
    securityCount: number
    openSessionCount: number
    dailyBarCount: number
    typedFactReadback: boolean
    systemKnowledgeReadback: boolean
    dataQualityPassed: boolean
    noFutureDataLeakage: boolean
    formulaOnlyQfq: boolean
    providerPitVerified: boolean
  }
  strategyExperiments: { experiments: StrategyExperiment[]; ranking: string[]; fingerprint: string }
  risk: { overallLevel: string; accountingPassed: boolean; lookAheadPassed: boolean; concentrationControlled: boolean }
  portfolio: {
    rankedStrategies: string[]
    preferredStrategy: string
    preferredRisk: string
    suggestedGrossExposure: number
    confidenceCap: number
    limitations: string[]
  }
  evidence: Evidence[]
  toolCalls: Array<{ callId: string; toolCode: string; requestedBy: string; status: string }>
  agentRuns: AgentRun[]
  criticReview: {
    issues: string[]
    challengedFindingIds: string[]
    reworkRequested: boolean
    correctionApplied: boolean
    reworkRounds: number
  }
  finalDecision: {
    code: string
    preferredStrategy: string
    riskLevel: string
    confidence: number
    supportingEvidenceIds: string[]
    unknowns: string[]
    researchOnly: boolean
  }
  researchFingerprint: string
  startedAt: string
  completedAt: string
  rounds: number
  toolCallCount: number
  modelCallCount: number
  totalModelUsage: { inputTokens: number; outputTokens: number; estimatedCostUsd: number }
  deterministic: boolean
  researchOnly: boolean
  providerCalled: boolean
  shadowStarted: boolean
  tradingStarted: boolean
}
