import { requestData } from '@/api/http'

/** 当前登录状态（对应后端 CurrentUserDto） */
export interface CurrentUser {
  /** 未登录时为 null */
  username: string | null
  /** 是否启用了访问控制；false 时前端不显示登录页 */
  authEnabled: boolean
}

/**
 * 认证接口客户端（阶段 15）。
 *
 * 注意 `fetchCurrentUser` 在未登录时返回 **200 + username: null**，不是 401 ——
 * 应用启动时用它判断「要不要显示登录页」，如果做成 401 会被 401 拦截器
 * 当成「会话过期」而触发跳转，形成循环。
 */
export function fetchCurrentUser(): Promise<CurrentUser> {
  return requestData<CurrentUser>({ url: '/auth/me', method: 'get' })
}

export function login(username: string, password: string): Promise<CurrentUser> {
  return requestData<CurrentUser>({
    url: '/auth/login',
    method: 'post',
    data: { username, password },
  })
}

export function logout(): Promise<void> {
  return requestData<void>({ url: '/auth/logout', method: 'post' })
}
