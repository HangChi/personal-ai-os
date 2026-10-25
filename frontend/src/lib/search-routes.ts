/**
 * kind → 模块路由（阶段 17）。
 *
 * <p><b>为什么这个映射在前端</b>：后端返回的是业务概念 {@code kind}，不是路由。
 * 后端要知道前端路由表，两边就会各自漂移；而 {@code kind} 比路由稳定得多。
 *
 * <p>本模块是纯函数，可在 Node 里直接断言（见 test/search.test.mjs）。
 */

/** 每个 kind 对应的模块首页路由 */
const ROUTES: Record<string, string> = {
  task: '/tasks',
  session: '/chat',
  event: '/calendar',
  memory: '/memory',
  document: '/knowledge',
}

/** 未知 kind 时的兜底路由（总览页），而不是抛异常 —— 搜索失败不该让页面崩 */
const FALLBACK_ROUTE = '/'

/**
 * 取某个命中条目对应的路由。
 *
 * <p>刻意不带条目 id：让五个页面都实现「按 id 滚动高亮」的工作量与本阶段主体相当，
 * 而全局搜索的职责是**定位到哪个模块**。
 */
export function routeForHit(kind: string): string {
  return ROUTES[kind] ?? FALLBACK_ROUTE
}

/** 是否认识这个 kind（未知 kind 在界面上仍可点击，只是跳总览） */
export function isKnownKind(kind: string): boolean {
  return Object.prototype.hasOwnProperty.call(ROUTES, kind)
}
