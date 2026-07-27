import type {
  AgentCode,
  FinalDecisionCode,
  GateStatus,
  JsonValue,
  TaskStatus,
} from '../agent-team/types'

export type ShadowBatchStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'PARTIAL'
  | 'FAILED'
  | 'CANCELLED'

export type ShadowSelectionMode = 'EXPLICIT' | 'AUTO'
export type ShadowOutcomeClass =
  | 'DETERMINED'
  | 'INSUFFICIENT'
  | 'FAILED'
  | 'CANCELLED'

export type ShadowReviewLabel =
  | 'EXPECTED'
  | 'UNEXPECTED'
  | 'DATA_ISSUE'
  | 'RULE_ISSUE'
  | 'FALSE_POSITIVE'
  | 'FALSE_NEGATIVE'
  | 'NEEDS_FOLLOW_UP'

export interface ShadowFeatureStatus {
  enabled: boolean
  schedulerEnabled: boolean
  ruleVersion: string
  zone: string
  safeWindowStart: string
  safeWindowEnd: string
  maxSymbols: number
  maxConcurrency: number
  itemTimeout: string
  pollInterval: string
  activeBatch: boolean
}

export interface ShadowBatch {
  id: number
  contractVersion: string
  status: ShadowBatchStatus
  triggerMode: 'MANUAL' | 'SCHEDULED'
  tradeDate: string
  ruleVersion: string
  selectionMode: ShadowSelectionMode
  selectionHash: string
  configuredMaxSymbols: number
  selectedCount: number
  launchedCount: number
  terminalCount: number
  determinedCount: number
  insufficientCount: number
  failedCount: number
  vetoCount: number
  dataQualityBlockedCount: number
  cacheHitCount: number
  cancellationRequested: boolean
  configuration: JsonValue
  errorMessage: string | null
  startedAt: string | null
  finishedAt: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface ShadowItem {
  id: number
  batchId: number
  selectionOrder: number
  symbol: string
  selectionSource:
    | 'EXPLICIT'
    | 'CURRENT_POSITION'
    | 'LATEST_SCAN_CANDIDATE'
  selectionSourceRef: string
  agentTaskId: number | null
  taskNewlyCreated: boolean
  cacheHit: boolean
  taskStatus: TaskStatus | null
  finalDecision: FinalDecisionCode | null
  gateStatus: GateStatus | null
  score: number | null
  confidence: number | null
  vetoed: boolean | null
  outcomeClass: ShadowOutcomeClass | null
  primaryReasonCode: string | null
  reasonCodes: string[] | null
  runSnapshot: {
    contractVersion?: string
    runs?: Array<{
      agentCode: AgentCode
      status: string
      gateStatus: string
      decision: string
      score: number | null
      confidence: number | null
      veto: boolean
      errors: Array<{ code: string; message?: string }>
    }>
  } | null
  contextHash: string | null
  durationMs: number | null
  previousItemId: number | null
  contextChanged: boolean | null
  decisionChanged: boolean | null
  scoreDelta: number | null
  confidenceDelta: number | null
  changedAgents: JsonValue | null
  errorMessage: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ShadowReview {
  id: number
  batchId: number
  itemId: number
  reviewContractVersion: string
  label: ShadowReviewLabel
  note: string
  reviewer: string
  supersedesReviewId: number | null
  createdAt: string
}

export interface ShadowMetrics {
  contractVersion: string
  batchCount: number
  itemCount: number
  outcomeDistribution: Record<string, number>
  finalDecisionDistribution: Record<string, number>
  dataQualityBlockedCount: number
  vetoCount: number
  cacheHitCount: number
  cacheHitRate: number
  primaryReasonCodeDistribution: Record<string, number>
  agentRunStatusDistribution: Record<string, Record<string, number>>
  agentErrorDistribution: Record<string, Record<string, number>>
  p50DurationMs: number | null
  p95DurationMs: number | null
  contextChangeRate: number
  decisionChangeRate: number
  averageAbsoluteScoreChange: number | null
  averageAbsoluteConfidenceChange: number | null
  reviewLabelDistribution: Record<string, number>
  unreviewedItemCount: number
}

export interface CreateShadowBatchRequest {
  tradeDate: string
  selectionMode: ShadowSelectionMode
  explicitSymbols: string[]
  maxSymbols: number
  createdBy: string
}

export interface CreateShadowReviewRequest {
  label: ShadowReviewLabel
  note: string
  reviewer: string
  supersedesReviewId: number | null
}
