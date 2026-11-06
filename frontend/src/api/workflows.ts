import { requestData } from '@/api/http'
import type { BoundedList } from '@/api/knowledge-captures'
export interface Workflow {
  id: string
  name: string
  kind: 'MORNING_BRIEF' | 'WEEKLY_REVIEW' | 'KNOWLEDGE_REVIEW'
  timezone: string
  localTime: string
  weekday: number
  enabled: boolean
  maxRunsPerDay: number
  maxItems: number
  maxOutputChars: number
  nextRunAt: string
}
export type WorkflowChange = Partial<Omit<Workflow, 'id' | 'nextRunAt'>>
export interface WorkflowExecution {
  id: string
  workflowId: string
  scheduledFor: string
  source: string
  status: string
  attempts: number
  modelCalls: number
  report: string | null
  error: string | null
  runId: string | null
  conversationId: string | null
  history: { attempt: number; status: string; startedAt: string; finishedAt: string | null; error: string | null }[]
}
export const workflows = (): Promise<BoundedList<Workflow>> => requestData({ url: '/workflows' })
export const saveWorkflow = (id: string | null, data: WorkflowChange): Promise<Workflow> =>
  requestData({ url: id ? `/workflows/${id}` : '/workflows', method: id ? 'patch' : 'post', data })
export const runWorkflow = (id: string): Promise<WorkflowExecution> =>
  requestData({ url: `/workflows/${id}/run`, method: 'post' })
export const workflowHistory = (id: string): Promise<BoundedList<WorkflowExecution>> =>
  requestData({ url: `/workflows/${id}/runs` })
export const retryWorkflow = (id: string): Promise<WorkflowExecution> =>
  requestData({ url: `/workflows/runs/${id}/retry`, method: 'post' })
