/**
 * 关键词高亮分段（阶段 18，contracts C-07）。
 *
 * 返回片段数组而不是「正则替换 HTML」：用户输入直接来自 URL query
 * （`/search?q=...`），把输入拼进 v-html 等于给 XSS 开口子；
 * 片段数组 + 文本插值天然免疫（命中片段在模板里包 <mark>）。
 *
 * 多词（空白切分）任一命中即高亮；重叠命中取先出现者；大小写不敏感。
 */

export interface HighlightSegment {
  text: string
  hit: boolean
}

export function highlightSegments(text: string, keyword: string): HighlightSegment[] {
  const terms = (keyword ?? '')
    .split(/\s+/)
    .map((term) => term.trim())
    .filter((term) => term.length > 0)

  if (terms.length === 0 || text.length === 0) {
    return [{ text, hit: false }]
  }

  // 收集所有命中区间（小写化后比较，输出仍用原文切片）
  const lowerText = text.toLowerCase()
  const ranges: Array<[number, number]> = []
  for (const term of terms) {
    const lowerTerm = term.toLowerCase()
    let from = 0
    for (;;) {
      const index = lowerText.indexOf(lowerTerm, from)
      if (index < 0) {
        break
      }
      ranges.push([index, index + lowerTerm.length])
      from = index + lowerTerm.length
    }
  }

  if (ranges.length === 0) {
    return [{ text, hit: false }]
  }

  // 按起点排序后合并**真重叠**的区间（「ab」同时命中「abc」与「b」的情形）。
  // 注意用严格小于：首尾相接的区间（如「周报周报」的两次命中）不合并——
  // 它们各自独立高亮，视觉上与合并无异，但保持片段边界更可预期。
  ranges.sort((a, b) => a[0] - b[0] || a[1] - b[1])
  const merged: Array<[number, number]> = []
  for (const range of ranges) {
    const last = merged[merged.length - 1]
    if (last && range[0] < last[1]) {
      last[1] = Math.max(last[1], range[1])
    } else {
      merged.push([...range])
    }
  }

  // 区间 → 片段（区间之间的原文段 hit=false）
  const segments: HighlightSegment[] = []
  let cursor = 0
  for (const [start, end] of merged) {
    if (start > cursor) {
      segments.push({ text: text.slice(cursor, start), hit: false })
    }
    segments.push({ text: text.slice(start, end), hit: true })
    cursor = end
  }
  if (cursor < text.length) {
    segments.push({ text: text.slice(cursor), hit: false })
  }
  return segments
}
