import axios, { type AxiosRequestConfig, type AxiosResponse } from 'axios'

import { API_SUCCESS_CODE, type ApiResponse } from '@/types/api'

/** 后端 API 基路径；开发期由 Vite 代理转发到 http://localhost:8080 */
const baseURL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

/** 全局 Axios 实例：统一 baseURL、超时与响应处理 */
export const http = axios.create({
  baseURL,
  timeout: 10_000,
  // 刻意**不**在这里写死 Content-Type：
  // axios 会为普通对象自动设置 application/json，而写死它会让 FormData 上传带上错误的
  // Content-Type（缺少 multipart 的 boundary），服务端直接解析失败。
  // 上传场景需要由浏览器/axios 自行计算带 boundary 的头。
})

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
    if (axios.isAxiosError(error)) {
      // 失败响应（4xx / 5xx）**同样带着统一信封**，里面是服务端给出的精确原因。
      // 若不加区分地一律报「无法连接后端服务」，用户看到的会是一句与事实不符的话 ——
      // 例如「任务不存在」被说成「连不上后端」，排错方向被彻底带偏。
      const body = error.response?.data as ApiResponse<unknown> | undefined
      if (body && typeof body.code === 'number' && typeof body.message === 'string') {
        return Promise.reject(new ApiBusinessError(body.code, body.message))
      }
      // 真正没有响应体的才是连接层问题（后端未启动、端口不通、超时）
      return Promise.reject(new Error(`无法连接后端服务：${error.message}`))
    }
    return Promise.reject(new Error('无法连接后端服务：未知错误'))
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
