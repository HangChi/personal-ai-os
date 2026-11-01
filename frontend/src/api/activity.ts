import { requestData } from '@/api/http'
import type { PageDto, ToolInvocation, ToolInvocationOutcome } from '@/types/api'

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
