import { requestData } from '@/api/http'
export interface Capture {
  id: string
  url: string
  documentId: string | null
  currentVersion: number
  pendingJobId: string | null
  status: string
  lastError: string | null
  lastCheckedAt: string
}
export interface CaptureVersion {
  version: number
  title: string
  content: string
  hash: string
  status: string
  capturedAt: string
}
export interface BoundedList<T> {
  items: T[]
  total: number
}
export const listCaptures = (): Promise<BoundedList<Capture>> => requestData({ url: '/knowledge/captures' })
export const captureUrl = (url: string): Promise<Capture> =>
  requestData({ url: '/knowledge/captures', method: 'post', data: { url }, timeout: 25000 })
export const refreshCapture = (id: string): Promise<Capture> =>
  requestData({ url: `/knowledge/captures/${id}/refresh`, method: 'post', timeout: 25000 })
export const captureVersions = (id: string): Promise<BoundedList<CaptureVersion>> =>
  requestData({ url: `/knowledge/captures/${id}/versions` })
