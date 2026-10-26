import { requestData } from '@/api/http'
import type { McpStatus, PageDto, ToolInvocation, ToolInvocationOutcome } from '@/types/api'

/**
 * 观测类接口客户端（阶段 11）。
 *
 * 全部只读：审计与 MCP 状态都没有写入口。审计不提供删除是刻意的 ——
 * 能被删掉的审计不能作为「助手到底做了什么」的证据。
 */

/** 审计查询参数；不传则取默认（第 1 页、每页 20 条、不筛选） */
export interface ToolInvocationQuery {
  page?: number
  size?: number
  outcome?: ToolInvocationOutcome
  conversationId?: string
}

/** 工具调用记录（分页） */
export function listToolInvocations(params: ToolInvocationQuery = {}): Promise<PageDto<ToolInvocation>> {
  return requestData<PageDto<ToolInvocation>>({
    url: '/agent/tool-invocations',
    method: 'get',
    params,
  })
}

/**
 * MCP 接入状态。
 *
 * 未启用时后端返回 `{ enabled: false, servers: [] }` 而不是 404 ——
 * 调用方据此区分「没开这个功能」与「接口挂了」。
 */
export function fetchMcpStatus(): Promise<McpStatus> {
  return requestData<McpStatus>({ url: '/mcp/servers', method: 'get' })
}
