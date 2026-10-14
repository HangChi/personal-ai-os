/**
 * `@/api/http` 的测试替身。
 *
 * 只实现被测模块真正用到的导出（requestData）。行为由各测试文件经
 * `handlers` 注入：键是请求 url，值是返回 Promise 的函数。
 * 没有注册 handler 的请求会 reject——测试忘打桩时立刻可见，
 * 不会静默拿到 undefined 让断言跑到奇怪的地方。
 */
export const handlers = new Map()

export function requestData(config) {
  const handler = handlers.get(config.url)
  if (!handler) {
    return Promise.reject(new Error(`测试替身没有为 ${config.url} 注册 handler`))
  }
  return handler(config)
}

export const http = {}

export class ApiBusinessError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'ApiBusinessError'
    this.code = code
  }
}

/** 便捷构造：与服务端信封一致的业务错误 */
export function businessError(code, message) {
  return new ApiBusinessError(code, message)
}
