import type { ResearchReport } from '../agent-research/types'

export interface ShadowRun {
  id: number
  runKey: string
  attempt: number
  status: 'QUEUED' | 'RUNNING' | 'FROZEN' | 'FAILED' | 'INTERRUPTED'
  triggerMode: 'SCHEDULED' | 'MANUAL' | 'HISTORICAL_REPLAY'
  tradeDate: string
  researchSlot: string
  researchAsOf: string
  signalTime?: string
  paperExecutionTime?: string
  modelProvider: string
  model: string
  promptVersion: string
  datasetFingerprint?: string
  strategyFingerprint?: string
  researchFingerprint?: string
  errorCode?: string
  completedAt?: string
}

export interface PaperPosition {
  security: { symbol: string; exchange: string }
  quantity: number
  availableQuantity: number
  averageCost: number
  lastPrice: number
  lastBuyDate?: string
}

export interface PaperPortfolio {
  portfolioCode: string
  initialCash: number
  cash: number
  realizedPnl: number
  totalFees: number
  stateVersion: number
  positions: PaperPosition[]
}

export interface PortfolioSnapshot {
  snapshotDate: string
  snapshotTime: string
  cash: number
  marketValue: number
  totalEquity: number
  realizedPnl: number
  unrealizedPnl: number
  totalFees: number
  totalReturn: number
  positionCount: number
}

export interface ShadowOverview {
  uiVersion: string
  runtimeVersion: string
  schedulerVersion: string
  runs: ShadowRun[]
  portfolio: PaperPortfolio
  latestPortfolioSnapshot?: PortfolioSnapshot
  researchOnly: boolean
  brokerConnected: boolean
  realTradingEnabled: boolean
}

export interface PaperOrder {
  id: number
  side: 'BUY' | 'SELL'
  security: { symbol: string; exchange: string }
  signalTime: string
  earliestExecutionTime: string
  targetWeight: number
  status: 'PENDING' | 'FILLED' | 'REJECTED'
  rejectionReason?: string
}

export interface PaperFill {
  id: number
  executionDate: string
  executionTime: string
  security: { symbol: string; exchange: string }
  side: 'BUY' | 'SELL'
  executionPrice: number
  quantity: number
  commission: number
  stampDuty: number
  slippageCost: number
  realizedPnl: number
}

export interface ShadowRunDetail {
  run: ShadowRun
  snapshot?: {
    snapshotFingerprint: string
    frozenAt: string
    report: ResearchReport
    recommendation: {
      decisionCode: string
      rankedStrategies: string[]
      rankedSecurities: string[]
      preferredStrategy: string
      riskLevel: string
      confidence: number
      suggestedGrossExposure: number
      supportingEvidenceIds: string[]
      limitations: string[]
      researchOnly: boolean
    }
  }
  orders: PaperOrder[]
  fills: PaperFill[]
  outcomes: Array<{
    horizonCode: 'D1' | 'D5' | 'D20'
    evaluationDate: string
    observation: {
      decisionCode: string
      equalWeightReturn: number
      emptyRecommendation: boolean
      noFutureDataLeakage: boolean
    }
  }>
}
