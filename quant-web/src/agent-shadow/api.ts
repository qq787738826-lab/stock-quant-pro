import { api } from '../api'
import type {
  CreateShadowBatchRequest,
  CreateShadowReviewRequest,
  ShadowBatch,
  ShadowFeatureStatus,
  ShadowItem,
  ShadowMetrics,
  ShadowReview,
} from './types'

export const getShadowStatus = () =>
  api.get('/agent-team/shadow/status') as Promise<ShadowFeatureStatus>

export const createShadowBatch = (request: CreateShadowBatchRequest) =>
  api.post('/agent-team/shadow/batches', request) as Promise<ShadowBatch>

export const getShadowBatches = (limit = 50) =>
  api.get('/agent-team/shadow/batches', { params: { limit } }) as Promise<ShadowBatch[]>

export const getShadowBatch = (batchId: number) =>
  api.get(`/agent-team/shadow/batches/${batchId}`) as Promise<ShadowBatch>

export const getShadowItems = (batchId: number) =>
  api.get(`/agent-team/shadow/batches/${batchId}/items`) as Promise<ShadowItem[]>

export const cancelShadowBatch = (batchId: number) =>
  api.post(`/agent-team/shadow/batches/${batchId}/cancel`) as Promise<ShadowBatch>

export const getShadowMetrics = () =>
  api.get('/agent-team/shadow/metrics') as Promise<ShadowMetrics>

export const getShadowDrift = () =>
  api.get('/agent-team/shadow/drift') as Promise<ShadowItem[]>

export const getShadowReviews = (itemId: number) =>
  api.get(`/agent-team/shadow/items/${itemId}/reviews`) as Promise<ShadowReview[]>

export const createShadowReview = (
  itemId: number,
  request: CreateShadowReviewRequest,
) =>
  api.post(`/agent-team/shadow/items/${itemId}/reviews`, request) as Promise<ShadowReview>
