import { api } from '../api'
import type { EvaluationOverview } from './types'

export const getAgentEvaluationOverview = () =>
  api.get('/agent-team/evaluation') as Promise<EvaluationOverview>
