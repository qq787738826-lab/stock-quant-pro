export type AgentRole = 'RESEARCH_COORDINATOR' | 'DATA_ANALYST' |
  'MARKET_TECHNICAL' | 'STRATEGY_RESEARCH' | 'RISK' | 'PORTFOLIO' |
  'CRITIC_REVIEW'

export interface MetricScore {
  metric: string
  score: number
  weight: number
  numerator: number
  denominator: number
  rationale: string
}

export interface AgentScorecard {
  versionKey: string
  role: AgentRole
  weightedScore: number
  lifecycleDecision: 'RETAIN' | 'WATCH' | 'DEMOTE' | 'REPLACE'
  reportSampleCount: number
  findingSampleCount: number
  metrics: MetricScore[]
  failureModes: string[]
}

export interface VersionEvaluation {
  versionKey: string
  scorecards: AgentScorecard[]
  offlineEvalPassed: number
  offlineEvalTotal: number
  historicalReplaySamples: number
  modelCalls: number
  totalTokens: number
  accountedCost: number
  costCurrency: string
  overallScore: number
  status: 'PASS' | 'FAIL' | 'INSUFFICIENT_SAMPLE'
  failureModes: string[]
  calibration: {
    status: 'PASS' | 'FAIL' | 'INSUFFICIENT_SAMPLE'
    eligibleSampleCount: number
    abstentionCount: number
    brierScore: number
    expectedCalibrationError: number
  }
}

export interface EvaluationOverview {
  systemVersion: string
  frozenShadowSamples: number
  realShadowStatus: 'PASS' | 'FAIL' | 'INSUFFICIENT_SAMPLE'
  registeredVersions: Array<{
    versionKey: string
    kind: 'CHAMPION' | 'CHALLENGER'
    parentVersionKey: string
    runtimeVersion: string
    toolVersion: string
    strategyVersion: string
    modelProvider: string
    model: string
    promptVersions: Record<AgentRole, string>
    registeredAt: string
    fingerprint: string
  }>
  researchOnly: boolean
  brokerConnected: boolean
  realTradingEnabled: boolean
  latestReport?: {
    currentChampionVersionKey: string
    versionEvaluations: VersionEvaluation[]
    comparison: {
      challengerVersionKey: string
      decision: string
      scoreDelta: number
      costRatio: number
      latencyRatio: number
      reasons: string[]
      promotionAllowed: boolean
    }
    frozenShadowRunCount: number
    eligibleOutcomeCount: number
    paperTotalReturn: number
    paperMaximumDrawdown: number
    realShadowStatus: string
    generatedAt: string
  }
}
