import { api } from '../api'
import type {
  AgentRun,
  AgentTask,
  CreateAgentTaskRequest,
  CreatedTask,
  Evidence,
  FinalDecision,
  FormalVeto,
  PageResult,
} from './types'

export const createAgentTask = (request: CreateAgentTaskRequest) =>
  api.post('/agent-tasks', request) as Promise<CreatedTask>

export const getAgentTask = (taskId: number) =>
  api.get(`/agent-tasks/${taskId}`) as Promise<AgentTask>

export const getAgentRuns = (taskId: number) =>
  api.get(`/agent-tasks/${taskId}/runs`) as Promise<AgentRun[]>

export const getAgentEvidence = (taskId: number) =>
  api.get(`/agent-tasks/${taskId}/evidence`) as Promise<Evidence[]>

export const getAgentDecision = (taskId: number) =>
  api.get(`/agent-tasks/${taskId}/decision`) as Promise<FinalDecision | null>

export const getAgentVetoes = (taskId: number) =>
  api.get(`/agent-tasks/${taskId}/vetoes`) as Promise<FormalVeto[]>

export const getAgentTaskHistory = (page = 0, size = 100) =>
  api.get('/agent-tasks/history', { params: { page, size } }) as Promise<PageResult<AgentTask>>
