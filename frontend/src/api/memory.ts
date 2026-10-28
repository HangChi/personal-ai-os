import { requestData } from '@/api/http'
import type { Memory } from '@/types/api'

/**
 * 长期记忆接口客户端。
 *
 * 后端契约见 `specs/008-long-term-memory/contracts/memory-api.md`。
 */

/** 全部记忆，按最近修改倒序 */
export function listMemories(): Promise<Memory[]> {
  return requestData<Memory[]>({ url: '/memories', method: 'get' })
}

/**
 * 新增记忆。
 *
 * 内容与既有条目相同时后端**不会**新增，直接返回既有条目（幂等），
 * 因此调用方不应假设「调用一次必定多一条」，拿返回值比对即可。
 */
export function createMemory(content: string): Promise<Memory> {
  return requestData<Memory>({ url: '/memories', method: 'post', data: { content } })
}

/** 编辑记忆内容 */
export function updateMemory(id: string, content: string): Promise<Memory> {
  return requestData<Memory>({ url: `/memories/${id}`, method: 'patch', data: { content } })
}

/**
 * 置顶 / 取消置顶（V26）。
 *
 * 常驻条目无条件注入、优先占预算 —— 这是「这条必须始终生效」的表达方式，
 * 与「相关才注入」不同：后者会在对话没提到它时漏掉硬约束。
 */
export function pinMemory(id: string, pinned: boolean): Promise<Memory> {
  return requestData<Memory>({ url: `/memories/${id}/pin`, method: 'patch', data: { pinned } })
}

/** 删除记忆 */
export function removeMemory(id: string): Promise<void> {
  return requestData<void>({ url: `/memories/${id}`, method: 'delete' })
}
