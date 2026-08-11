import { api } from '../api'
import type { ReportSummary, ResearchReport } from './types'

export const getAgentResearchReports = () =>
  api.get('/agent-research/reports') as Promise<ReportSummary[]>

export const getAgentResearchReport = (taskId: string) =>
  api.get(`/agent-research/reports/${encodeURIComponent(taskId)}`) as Promise<ResearchReport>
