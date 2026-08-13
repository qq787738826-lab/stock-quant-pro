import { api } from '../api'
import type { SelectionResult, SelectionSummary } from './types'

export const startSelection = (primaryWindow = 20) =>
  api.post('/research-selection/runs', { primaryWindow }) as Promise<{
    run: SelectionSummary; userVisibleStage: string; accepted: boolean
  }>
export const getSelectionRun = (id: number) =>
  api.get(`/research-selection/runs/${id}`) as Promise<SelectionSummary | SelectionResult>
export const getSelectionHistory = (limit = 20) =>
  api.get(`/research-selection/runs?limit=${limit}`) as Promise<SelectionSummary[]>
export const getLatestSelection = () =>
  api.get('/research-selection/latest') as Promise<SelectionResult | undefined>
export const getResearchUniverse = () =>
  api.get('/research-selection/universe') as Promise<any>
