import axios, { type AxiosRequestConfig, type AxiosResponse } from 'axios'

import {
  ApiBusinessError,
  interpretAxiosError,
  toBusinessError,
} from '@/api/envelope'
import { type ApiResponse } from '@/types/api'

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

export { ApiBusinessError }

/**
 * 401 处理器。
 *
 * <p>用「由入口注册」而不是在 http.ts 里直接 import store：那会形成
 * `store → api → http → store` 的循环依赖，Vite 下表现为运行时 undefined。
 */
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler
}

/**
 * 探测类请求的 401 不应触发跳转。
 *
 * <p>`/auth/login` 的 401 是「密码错」，把它当成「会话过期」会让用户在登录页上
 * 被反复刷新；`/auth/me` 理论上不会 401（未登录也返回 200），一并排除更稳。
 */
function isAuthProbe(url: string | undefined): boolean {
  return typeof url === 'string' && (url.includes('/auth/login') || url.includes('/auth/me'))
}

// 把统一响应体中的 code != 0 转成 rejected Promise，避免每个调用点重复判断。
// 分类逻辑在 envelope.ts（纯函数，可在 Node 里测试）；这里只做接线。
http.interceptors.response.use(
  (response: AxiosResponse) => {
    const businessError = toBusinessError(response.data)
    return businessError ? Promise.reject(businessError) : response
  },
  (error: unknown) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      if (!isAuthProbe(error.config?.url)) {
        unauthorizedHandler?.()
      }
    }
    return Promise.reject(interpretAxiosError(error))
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
