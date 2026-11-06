import { requestData } from '@/api/http'

export interface ProjectItem {
  id: string
  name: string
  description: string | null
  archived: boolean
  createdAt: string
  updatedAt: string
}
export interface RunSummary {
  id: string
  conversationId: string
  title: string
  status: string
  updatedAt: string
}
export const listProjects = (): Promise<ProjectItem[]> => requestData({ url: '/projects', method: 'get' })
export const saveProject = (
  payload: { name?: string; description?: string; archived?: boolean },
  id?: string,
): Promise<ProjectItem> =>
  requestData({
    url: id ? `/projects/${id}` : '/projects',
    method: id ? 'patch' : 'post',
    data: payload,
    headers: { 'Idempotency-Key': `project-${crypto.randomUUID()}` },
  })
export const unfinishedRuns = (): Promise<{ items: RunSummary[]; total: number }> =>
  requestData({ url: '/workspace/runs', method: 'get' })
