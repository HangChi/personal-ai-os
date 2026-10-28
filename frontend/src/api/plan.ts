import { requestData } from '@/api/http'
import type { AgentPlan } from '@/types/api'

/**
 * 执行计划接口（阶段 22）。
 *
 * 返回「最近一个计划」而不是「进行中的那个」：任务结束后面板仍要显示结果
 * （哪一步失败、实际调用了什么），若只返回进行中的，计划一完成面板就空白了。
 */

/** 会话最近一个执行计划；从未规划过时为 null */
export function getPlan(conversationId: string): Promise<AgentPlan | null> {
  return requestData<AgentPlan | null>({ url: `/conversations/${conversationId}/plan`, method: 'get' })
}
