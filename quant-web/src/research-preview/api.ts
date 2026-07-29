import { api } from '../api'
import {
  getAgentDecision,
  getAgentEvidence,
  getAgentRuns,
  getAgentTask,
  getAgentTaskHistory,
  getAgentVetoes,
} from '../agent-team/api'
import type { AgentTask, PageResult } from '../agent-team/types'
import type {
  PreviewTaskBundle,
  ScanResultSnapshot,
  ScanTaskSnapshot,
} from './types'

export const getScanHistory = (limit = 50) =>
  api.get('/scans/history', { params: { limit } }) as Promise<ScanTaskSnapshot[]>

export const getLatestOfficialScanTask = () =>
  api.get('/scans/latest-official-task') as Promise<ScanTaskSnapshot>

export const getLatestScanTask = () =>
  api.get('/scans/latest-task') as Promise<ScanTaskSnapshot>

export const getScanTask = (taskId: number) =>
  api.get(`/scans/${taskId}`) as Promise<ScanTaskSnapshot>

export const getScanResults = (taskId: number, limit = 200, eligibleOnly = false) =>
  api.get(`/scans/${taskId}/results`, {
    params: { limit, eligibleOnly },
  }) as Promise<ScanResultSnapshot[]>

export const getPreviewAgentHistory = (page = 0, size = 100): Promise<PageResult<AgentTask>> =>
  getAgentTaskHistory(page, size)

export async function getPreviewTaskBundle(taskId: number): Promise<PreviewTaskBundle> {
  const [task, runs, evidence, decision, vetoes] = await Promise.all([
    getAgentTask(taskId),
    getAgentRuns(taskId),
    getAgentEvidence(taskId),
    getAgentDecision(taskId),
    getAgentVetoes(taskId),
  ])
  return {
    task,
    runs: runs ?? [],
    evidence: evidence ?? [],
    decision,
    vetoes: vetoes ?? [],
    qualification: 'RESEARCH_HISTORICAL_UNVERIFIED',
    synthetic: false,
  }
}
