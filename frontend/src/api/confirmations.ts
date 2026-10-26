import { requestData } from '@/api/http'
import type { ConfirmationExecutionResult, TaskConfirmation } from '@/types/api'

/**
 * 人工确认接口客户端。
 *
 * 这些接口**只供人类使用**：Agent 的工具集合里没有任何能完成确认的能力。
 * 因此这里的每一个调用都是一次「人做出的决定」。
 */

export function listConfirmations(conversationId?: string, status = 'PENDING'): Promise<TaskConfirmation[]> {
  const params: Record<string, string> = { status }
  if (conversationId) {
    params.conversationId = conversationId
  }
  return requestData<TaskConfirmation[]>({ url: '/agent/confirmations', method: 'get', params })
}

export function confirmOperation(id: string, token: string): Promise<ConfirmationExecutionResult> {
  return requestData<ConfirmationExecutionResult>({
    url: `/agent/confirmations/${id}/confirm`,
    method: 'post',
    data: { token },
  })
}

export function cancelOperation(id: string, token: string): Promise<void> {
  return requestData<void>({
    url: `/agent/confirmations/${id}/cancel`,
    method: 'post',
    data: { token },
  })
}
