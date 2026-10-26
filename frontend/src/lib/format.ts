/**
 * 展示层格式化函数（2026-10-26 收敛）。
 *
 * <p>这些函数原先散落在 TasksView / KnowledgeView 里各写一份，行为还不完全一致
 * （一个 null 返回破折号、另一个直接 new Date(null)）。纯函数放在 lib 下
 * 可以脱离组件测试，也保证同一份数据在不同页面显示成同一个样子。
 */

/**
 * 日期时间展示：空值与非法时间都显示破折号。
 *
 * <p>非法时间不代表「没有时间」，但用户看到「Invalid Date」只会更困惑；
 * 破折号至少不自相矛盾。
 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false })
}

/** 文件大小展示（B / KB / MB，保留合适的位数） */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

/**
 * 检索命中来源徽标（混合检索）。
 *
 * <p>三种情况文案不同，不能拼在一起：
 * - {@code BOTH}：两条通道都命中，可信度最高
 * - 仅关键词命中：{@code score} 为 null（没做过嵌入），不能显示「相似度 —」
 * - 仅向量命中：正常显示相似度
 */
export function hitBadge(hit: { score: number | null; matchedBy?: string }): {
  text: string
  type: 'success' | 'warning' | 'info'
} {
  if (hit.matchedBy === 'BOTH') {
    return { text: '向量+关键词', type: 'success' }
  }
  if (hit.score === null || hit.score === undefined) {
    return { text: '关键词命中', type: 'warning' }
  }
  return { text: `相似度 ${hit.score.toFixed(3)}`, type: 'info' }
}
