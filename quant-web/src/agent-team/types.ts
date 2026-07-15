export type JsonValue =
  | string
  | number
  | boolean
  | null
  | JsonValue[]
  | { [key: string]: JsonValue }

export type AgentCode =
  | 'DATA_QUALITY'
  | 'MARKET_REGIME'
  | 'TECHNICAL_ANALYSIS'
  | 'STRATEGY_BACKTEST'
  | 'ANNOUNCEMENT_RISK'
  | 'POSITION_RISK'

export type TaskStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'PARTIAL'
  | 'FAILED'
  | 'CANCELLED'

export type RunStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'PARTIAL'
  | 'INSUFFICIENT_DATA'
  | 'FAILED'
  | 'SKIPPED'

export type GateStatus = 'PASS' | 'WARN' | 'BLOCKED' | 'NOT_APPLICABLE'
export type RunDecision = 'PASS' | 'WARN' | 'REJECT' | 'NOT_APPLICABLE'
export type ExecutionMode = 'LOCAL_RULES'
export type TriggerType = 'MANUAL' | 'SCAN_CANDIDATE' | 'SCHEDULED' | 'RETRY'
export type Severity = 'INFO' | 'WARN' | 'HIGH' | 'CRITICAL'
export type EvidenceCategory =
  | 'MARKET_DATA'
  | 'MARKET_BREADTH'
  | 'TECHNICAL_INDICATOR'
  | 'SCAN_RESULT'
  | 'BACKTEST_RESULT'
  | 'SECURITY_EVENT'
  | 'PORTFOLIO_STATE'
  | 'DATA_QUALITY'
  | 'QUERY_RESULT'
export type EvidenceSourceType =
  | 'DATABASE'
  | 'LOCAL_CACHE'
  | 'CONFIGURED_PROVIDER'
  | 'JAVA_ENGINE'
  | 'PYTHON_RULE_ENGINE'

export interface CreateAgentTaskRequest {
  symbol: string
  tradeDate: string
  ruleVersion: string
  executionMode: ExecutionMode
  triggerType: TriggerType
  forceRefresh: boolean
}

export interface AgentTask {
  id: number
  symbol: string
  tradeDate: string
  status: TaskStatus
  contextSchemaVersion: string
  contextSnapshot: JsonValue
  contextGeneratedAt: string
  contextHash: string
  ruleVersion: string
  executionMode: ExecutionMode
  triggerType: TriggerType
  requestedBy: string | null
  forceRefresh: boolean
  startedAt: string | null
  finishedAt: string | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface CreatedTask {
  task: AgentTask
  newlyCreated: boolean
}

export interface AgentRun {
  id: number
  taskId: number
  agentCode: AgentCode
  attemptNo: number
  status: RunStatus
  gateStatus: GateStatus | null
  decision: RunDecision | null
  score: number | null
  confidence: number | null
  veto: boolean
  summary: string | null
  outputJson: JsonValue | null
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface Evidence {
  evidenceId: string
  category: EvidenceCategory
  sourceType: EvidenceSourceType
  sourceName: string
  sourceRef: string | null
  symbol: string | null
  tradeDate: string
  observedAt: string
  collectedAt: string
  fields: JsonValue
  contentHash: string
}

export interface Finding {
  findingId: string
  code: string
  severity: Severity
  title: string
  detail: string
  evidenceIds: string[]
}

export type FinalDecisionCode =
  | 'REJECTED_BY_VETO'
  | 'BLOCKED_BY_DATA_QUALITY'
  | 'INSUFFICIENT_DATA'
  | 'RESEARCH_ONLY'
  | 'WATCH'
  | 'PASS_TO_MANUAL_REVIEW'

export interface FinalDecision {
  schemaVersion: string
  taskId: number
  decision: FinalDecisionCode
  gateStatus: GateStatus
  vetoed: boolean
  score: number
  confidence: number
  summary: string
  findings: Finding[] | null
  sourceRunIds: number[] | null
  vetoIds: string[] | null
  contextHash: string
  tradeDate: string
  ruleVersion: string
  executionMode: ExecutionMode
  generatedAt: string
}

export interface FormalVeto {
  vetoId: string
  taskId: number
  runId: number
  agentCode: AgentCode
  vetoCode: string
  reason: string
  evidenceIds: string[] | null
  createdAt: string
}
