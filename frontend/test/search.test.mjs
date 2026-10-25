import { eq, check } from './assert.mjs'

/**
 * 全局搜索（阶段 17）。
 *
 * 两个纯函数：kind → 路由映射、以及「是否认识这个 kind」。
 * 跳转规则是契约的一部分 —— 后端加新模块时，这里漏一项的后果是
 * 「搜得到但点进去跳到总览」，而这种问题在界面上很难第一时间发现。
 */

const { routeForHit, isKnownKind } = await import('@/lib/search-routes')

{
  eq('任务 → 任务页', routeForHit('task'), '/tasks')
  eq('会话 → 对话页', routeForHit('session'), '/chat')
  eq('日程 → 日程页', routeForHit('event'), '/calendar')
  eq('记忆 → 记忆页', routeForHit('memory'), '/memory')
  eq('文档 → 知识库页', routeForHit('document'), '/knowledge')
}

{
  eq('未知 kind 兜底到总览', routeForHit('something-new'), '/')
  eq('空串兜底到总览', routeForHit(''), '/')
  check('未知 kind 不被认为认识', !isKnownKind('something-new'))
  check('已知 kind 被认识', isKnownKind('task'))
}
