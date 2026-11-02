import { requestData } from '@/api/http'
import type { KnowledgeSource } from '@/types/api'
export interface KnowledgeSpace {
  id: string
  name: string
}
export interface DocumentSource {
  documentId: string
  title: string
  source: KnowledgeSource
}
export interface SourceConflict {
  id: string
  leftId: string
  rightId: string
  leftChunk: number
  rightChunk: number
  leftQuote: string
  rightQuote: string
  reason: string
  status: string
  resolution: string | null
  winnerId: string | null
  resolutionReason: string | null
}
const root = '/knowledge/governance'
export const listSpaces = () => requestData<KnowledgeSpace[]>({ url: `${root}/spaces`, method: 'get' })
export const createSpace = (name: string) =>
  requestData<KnowledgeSpace>({ url: `${root}/spaces`, method: 'post', data: { name } })
export const listSources = () => requestData<DocumentSource[]>({ url: `${root}/sources`, method: 'get' })
export const updateSource = (id: string, source: KnowledgeSource) =>
  requestData<KnowledgeSource>({ url: `${root}/sources/${id}`, method: 'put', data: source })
export const retryReview = (id: string) => requestData({ url: `${root}/sources/${id}/review`, method: 'post' })
export const listConflicts = () => requestData<SourceConflict[]>({ url: `${root}/conflicts`, method: 'get' })
export const resolveConflict = (id: string, decision: string, winnerId: string | null, reason: string) =>
  requestData({ url: `${root}/conflicts/${id}/resolve`, method: 'post', data: { decision, winnerId, reason } })
