import type {
  AgentCode,
  AgentRun,
  AgentTask,
  Evidence,
  Finding,
  FinalDecision,
  FormalVeto,
  JsonValue,
  PageResult,
  Severity,
} from '../agent-team/types'

export type PreviewMode = 'EXISTING_RESEARCH_SNAPSHOT' | 'TEST_DEMO_EXPLICIT'

export type PreviewSection =
  | 'overview'
  | 'agents'
  | 'evidence'
  | 'history'
  | 'report'

export type PreviewQualification =
  | 'RESEARCH_HISTORICAL_UNVERIFIED'
  | 'TEST_DEMO_EXPLICIT'

export type PreviewReasonSource =
  | 'AGENT_FINDING'
  | 'AGENT_ERROR'
  | 'FORMAL_VETO'
  | 'TASK_ERROR'
  | 'RUN_ERROR'
  | 'PREVIEW_UI'

export interface PreviewIssue {
  code: string
  detail: string
}

export interface ScanTaskSnapshot {
  id?: number
  status?: string
  scan_type?: string
  official?: boolean
  requested_limit?: number
  batch_size?: number
  result_limit?: number
  total_symbols?: number
  processed_symbols?: number
  success_symbols?: number
  failed_symbols?: number
  selected_count?: number
  trade_date?: string
  duration_ms?: number
  message?: string
  created_at?: string
  started_at?: string
  finished_at?: string
}

export interface ScanResultSnapshot {
  task_id?: number
  rank_no?: number
  symbol?: string
  name?: string
  trade_date?: string
  score?: number | null
  eligible?: boolean | null
  risk_level?: string | null
  signal_level?: string | null
  data_source?: string | null
  metrics?: unknown
  filter_reasons?: unknown
}

export interface PreviewCandidate {
  rank: number | null
  symbol: string
  name: string
  score: number | null
  eligible: boolean | null
  riskLevel: string | null
  signalLevel: string | null
  tradeDate: string | null
  metrics: Record<string, JsonValue>
  filterReasons: string[]
  hasAgentResult: boolean
  qualification: PreviewQualification
  synthetic: boolean
}

export interface PreviewTaskBundle {
  task: AgentTask
  runs: AgentRun[]
  evidence: Evidence[]
  decision: FinalDecision | null
  vetoes: FormalVeto[]
  qualification: PreviewQualification
  synthetic: boolean
}

export interface AgentDisplaySlot {
  agentCode: AgentCode
  run: AgentRun | null
}

export interface RunErrorView {
  code: string
  message: string
}

export interface ReasonCodeEntry {
  code: string
  source: PreviewReasonSource
  agentCode: AgentCode | null
  detail: string | null
}

export interface ComparisonRow {
  key: string
  label: string
  left: string
  right: string
  differs: boolean
}

export interface TaskComparison {
  warnings: string[]
  rows: ComparisonRow[]
  agentRows: Array<{
    agentCode: AgentCode
    statusLeft: string
    statusRight: string
    scoreLeft: string
    scoreRight: string
    confidenceLeft: string
    confidenceRight: string
  }>
}

export interface ReportField {
  label: string
  value: string
}

export interface StructuredAgentSummary {
  agentCode: AgentCode
  name: string
  status: string
  gateStatus: string
  score: string
  confidence: string
  summary: string
}

export type StructuredRiskLevel = Severity | 'FORMAL_VETO'

export type StructuredRiskTone =
  | 'info'
  | 'warning'
  | 'danger'
  | 'formal-veto'
  | 'neutral'

export interface StructuredRiskItem {
  code: string
  level: StructuredRiskLevel
  tone: StructuredRiskTone
  title: string
  detail: string
  source: string
}

export interface StructuredEvidenceIndex {
  evidenceId: string
  category: string
  sourceType: string
}

export interface StructuredResearchReport {
  conclusion: ReportField[]
  conclusionSummary: string
  qualification: ReportField[]
  limitations: string[]
  agents: StructuredAgentSummary[]
  risks: StructuredRiskItem[]
  reasons: ReasonCodeEntry[]
  evidence: StructuredEvidenceIndex[]
  audit: ReportField[]
  disclaimer: string
}

export interface DemoPreviewFixture {
  schemaVersion: 'RESEARCH_PREVIEW_DEMO_V1'
  mode: 'TEST_DEMO_EXPLICIT'
  qualification: 'TEST_DEMO_EXPLICIT'
  synthetic: true
  scanTask: ScanTaskSnapshot
  candidates: PreviewCandidate[]
  bundles: PreviewTaskBundle[]
}

export interface ResearchPreviewState {
  mode: PreviewMode
  section: PreviewSection
  qualification: PreviewQualification
  synthetic: boolean
  scanHistory: ScanTaskSnapshot[]
  selectedScanTask: ScanTaskSnapshot | null
  candidates: PreviewCandidate[]
  history: PageResult<AgentTask>
  activeBundle: PreviewTaskBundle | null
  comparisonLeft: PreviewTaskBundle | null
  comparisonRight: PreviewTaskBundle | null
  issues: PreviewIssue[]
}

export type RunFinding = Finding
