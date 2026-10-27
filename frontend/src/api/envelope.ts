import axios from 'axios'

import { API_SUCCESS_CODE, type ApiResponse } from '@/types/api'

/**
 * 业务错误：统一响应体里的 code 非 0（可能是 HTTP 200，也可能是 4xx / 5xx）。
 *
 * 调用方可用 `instanceof ApiBusinessError` 区分「业务失败」与「网络/服务不可用」。
 */
export class ApiBusinessError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiBusinessError'
    this.code = code
  }
}

/**
 * 从统一响应体解读业务错误；信封完好且 code 非 0 时返回错误对象，否则返回 null。
 *
 * 抽成纯函数（不碰 axios 实例、不碰 `import.meta.env`）是为了可在 Node 里直接测试 ——
 * 这段分类逻辑曾经把「任务不存在」说成「连不上后端」（d0df5de 修掉的 bug），
 * 必须有自动化测试守住，不能只靠浏览器手工点。
 */
export function toBusinessError(body: unknown): ApiBusinessError | null {
  if (!body || typeof body !== 'object') {
    return null
  }
  const envelope = body as Partial<ApiResponse<unknown>>
  if (typeof envelope.code === 'number' && envelope.code !== API_SUCCESS_CODE && typeof envelope.message === 'string') {
    return new ApiBusinessError(envelope.code, envelope.message)
  }
  return null
}

/**
 * 把 axios 的失败解释成可读错误。
 *
 * 判定顺序（顺序本身是契约）：
 * 1. 失败响应带着统一信封（4xx / 5xx 都会有）→ 用服务端给的精确原因；
 * 2. 是 axios 错误但没有信封 → 真正的连接层问题（后端未启动、端口不通、超时）；
 * 3. 不是 axios 错误 → 未知错误。
 *
 * 早期实现一律报「无法连接后端服务」，把服务端给出的精确原因丢掉了 ——
 * 用户看到「任务不存在」被说成「连不上后端」，排错方向被彻底带偏。
 */
export function interpretAxiosError(error: unknown): Error {
  if (axios.isAxiosError(error)) {
    const businessError = toBusinessError(error.response?.data)
    if (businessError) {
      return businessError
    }
    // 连接层的三种失败要分开说（审查 P2）：5xx / 超时 / 断网是三件不同的事，
    // 混成一句「无法连接」会让用户误以为后端没起。英文原文只进 console 供排错，
    // 不再拼进用户可见的文案。
    const status = error.response?.status
    if (status !== undefined && status >= 500) {
      console.error('服务端错误：', error)
      return new Error('服务器开小差了，请稍后重试')
    }
    if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
      console.error('请求超时：', error)
      return new Error('请求超时，请稍后重试')
    }
    console.error('网络请求失败：', error)
    return new Error('无法连接后端服务，请确认服务已启动')
  }
  return new Error('未知错误')
}
