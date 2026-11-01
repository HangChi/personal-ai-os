let cachedToken: string | null = null
let pending: Promise<string> | null = null

export function invalidateCsrfToken(): void {
  cachedToken = null
}

export async function csrfToken(baseUrl: string): Promise<string> {
  if (cachedToken) return cachedToken
  if (!pending) {
    pending = fetch(`${baseUrl}/auth/csrf`, { credentials: 'same-origin' })
      .then(async (response) => {
        if (!response.ok) throw new Error(`CSRF token request failed (${response.status})`)
        const body = (await response.json()) as { data?: { token?: string } }
        const token = body.data?.token
        // 本地默认关闭 CSRF 时服务端明确返回空令牌；写请求仍可正常工作。
        cachedToken = token ?? ''
        return cachedToken
      })
      .finally(() => {
        pending = null
      })
  }
  return pending
}
