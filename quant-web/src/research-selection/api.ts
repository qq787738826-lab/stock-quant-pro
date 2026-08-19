import { api } from '../api'
import type { SelectionResult, SelectionSummary, UniverseMemberPage, UniverseView } from './types'

export const MAINBOARD_PLANNING_TIMEOUT_MS = 120_000

export const startSelection = (primaryWindow = 20) =>
  api.post('/research-selection/runs', { primaryWindow }, {
    timeout: MAINBOARD_PLANNING_TIMEOUT_MS,
  }) as Promise<{
    run: SelectionSummary; userVisibleStage: string; accepted: boolean
  }>
export const getSelectionRun = (id: number) =>
  api.get(`/research-selection/runs/${id}`) as Promise<SelectionSummary | SelectionResult>
export const getSelectionHistory = (limit = 20) =>
  api.get(`/research-selection/runs?limit=${limit}`) as Promise<SelectionSummary[]>
export const getLatestSelection = () =>
  api.get('/research-selection/latest') as Promise<SelectionResult | undefined>
export const getResearchUniverse = () =>
  api.get('/research-selection/universe', {
    timeout: MAINBOARD_PLANNING_TIMEOUT_MS,
  }) as Promise<UniverseView>
export const getSelectionMembers = (id: number, page = 0, size = 50,
  eligibility = '') => api.get(`/research-selection/runs/${id}/members?page=${page}&size=${size}${eligibility ? `&eligibility=${encodeURIComponent(eligibility)}` : ''}`) as Promise<UniverseMemberPage>
