import { api } from '../api'
import type { ShadowOverview, ShadowRunDetail } from './types'

export const getShadowResearchOverview = (limit = 30) =>
  api.get(`/agent-team/shadow-research?limit=${limit}`) as Promise<ShadowOverview>

export const getShadowResearchRun = (runId: number) =>
  api.get(`/agent-team/shadow-research/runs/${runId}`) as Promise<ShadowRunDetail>
