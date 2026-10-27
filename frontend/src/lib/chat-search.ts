/**
 * 会话内搜索的纯逻辑。
 *
 * <p>抽出来是为了可测：匹配与游标循环这两件事没有任何 DOM 依赖，
 * 但写错的表现很具体 —— 「明明有一处却说无匹配」「最后一条之后不回到第一条」。
 * 放在组件里只能靠手点。
 */

/** 只要求最小字段，便于测试直接构造，也避免与 ChatMessage 类型强绑 */
export interface Searchable {
  id: number
  content?: string | null
}

/**
 * 返回内容命中关键词的消息 id（按传入顺序）。
 *
 * <p>大小写不敏感、去首尾空白；关键词为空返回空数组（不返回全部 ——
 * 「没输入」不等于「全都匹配」，否则一聚焦就显示「1/50」很吓人）。
 */
export function matchMessageIds(messages: Searchable[], keyword: string): number[] {
  const needle = keyword.trim().toLowerCase()
  if (!needle) {
    return []
  }
  return messages
    .filter((message) => (message.content ?? '').toLowerCase().includes(needle))
    .map((message) => message.id)
}

/**
 * 计算移动 step 之后的游标，首尾循环。
 *
 * <p>循环而不是夹在边界：用户连点「下一处」的预期是回到开头，
 * 而不是按钮突然没反应（那看起来像坏了）。
 *
 * @param current 当前下标（可能超出范围，例如命中数变小之后）
 * @param total   命中总数
 * @param step    +1 下一处 / -1 上一处
 */
export function nextMatchCursor(current: number, total: number, step: number): number {
  if (total <= 0) {
    return 0
  }
  // 取模两次：JavaScript 的 % 对负数返回负值，直接用于下标会取到 undefined
  return (((current + step) % total) + total) % total
}
