import axios, { type AxiosRequestConfig, type AxiosResponse } from 'axios'

import { API_SUCCESS_CODE, type ApiResponse } from '@/types/api'

/** 后端 API 基路径；开发期由 Vite 代理转发到 http://localhost:8080 */
const baseURL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

/** 全局 Axios 实例：统一 baseURL、超时与响应处理 */
export const http = axios.create({
  baseURL,
  timeout: 10_000,
  headers: { 'Content-Type': 'application/json' },
})

/**
 * 业务错误：HTTP 层成功，但统一响应体里的 code 非 0。
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

// 把统一响应体中的 code != 0 转成 rejected Promise，避免每个调用点重复判断
http.interceptors.response.use(
  (response: AxiosResponse) => {
    const body = response.data as ApiResponse<unknown> | undefined
    if (body && typeof body.code === 'number' && body.code !== API_SUCCESS_CODE) {
      return Promise.reject(new ApiBusinessError(body.code, body.message))
    }
    return response
  },
  (error: unknown) => {
    const message = axios.isAxiosError(error)
      ? `无法连接后端服务：${error.message}`
      : '无法连接后端服务：未知错误'
    return Promise.reject(new Error(message))
  },
)

/**
 * 发起请求并解包统一响应，直接返回 data 字段。
 *
 * @param config Axios 请求配置
 * @returns 响应体中的 data
 */
export async function requestData<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiResponse<T>>(config)
  return response.data.data as T
}
