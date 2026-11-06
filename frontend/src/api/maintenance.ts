import { requestData } from '@/api/http'
import type { BoundedList } from '@/api/knowledge-captures'
export interface Heartbeat {
  instanceId: string
  backend: string
  role: string
  status: string
  startedAt: string
  lastSeenAt: string
}
export interface MaintenanceCheck {
  id: string
  kind: string
  status: string
  digest: string
  createdAt: string
  counts?: Record<string, number>
  issues?: string[]
  truncated?: boolean
  source?: string
  independentVerification?: boolean
  tool?: string
  summary?: string
  outcome?: string
  completedAt?: string
  artifactDigest?: string | null
}
export interface MaintenanceItem {
  id: string
  status: string
  updatedAt: string
  attempts?: number
  name?: string
  kind?: string
  title?: string
  conversationId?: string
  workflowId?: string
  error?: string
}
export interface MaintenanceSnapshot {
  generatedAt: string
  heartbeats: BoundedList<Heartbeat>
  failedIndexJobs: BoundedList<MaintenanceItem>
  failedDeliveries: BoundedList<MaintenanceItem>
  failedWorkflows: BoundedList<MaintenanceItem>
  unfinishedRuns: BoundedList<MaintenanceItem>
  checks: BoundedList<MaintenanceCheck>
}
export interface DrillReport {
  tool: string
  summary: string
  outcome: string
  completedAt: string
  artifactDigest?: string
}
export const maintenanceSnapshot = (): Promise<MaintenanceSnapshot> => requestData({ url: '/maintenance' })
export const verifyBackup = (data: ArrayBuffer): Promise<MaintenanceCheck> =>
  requestData({
    url: '/maintenance/verify',
    method: 'post',
    data,
    headers: { 'Content-Type': 'application/json' },
    transformRequest: [(value: unknown) => value],
    timeout: 30000,
  })
export const recordDrill = (data: DrillReport): Promise<MaintenanceCheck> =>
  requestData({ url: '/maintenance/drill-reports', method: 'post', data, timeout: 30000 })
