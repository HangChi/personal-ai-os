import { eq, check } from './assert.mjs'

/**
 * 关键词高亮分段（阶段 18，contracts C-07）。
 *
 * 重点边界：空词 / 特殊字符（不做正则）/ 多词 / 重叠命中 / 内容守恒。
 */

const { highlightSegments } = await import('@/lib/highlight-text')

function hits(segments) {
  return segments.filter((s) => s.hit).map((s) => s.text)
}

function plain(segments) {
  return segments.map((s) => s.text).join('')
}

// ---- 基本命中 ----

{
  const segments = highlightSegments('支持哪些隔离级别？', '隔离')
  eq('命中词被切出', hits(segments).join(','), '隔离')
  eq('原文守恒', plain(segments), '支持哪些隔离级别？')
}

{
  const segments = highlightSegments('Hello World', 'world')
  eq('大小写不敏感', hits(segments).join(','), 'World')
}

// ---- 空 / 全空白 keyword ----

eq('空 keyword 单段不命中', highlightSegments('abc', '').length, 1)
check('空 keyword 不命中', !highlightSegments('abc', '')[0].hit)
eq('全空白 keyword 同上', highlightSegments('abc', '   ').length, 1)
eq('空文本', highlightSegments('', 'x').length, 1)

// ---- 特殊字符按字面匹配（不编译正则）----

{
  const segments = highlightSegments('a*b(c)[d] 文本', 'b(')
  eq('正则特殊字符字面匹配', hits(segments).join(','), 'b(')
  check('含特殊字符不出错', plain(highlightSegments('a*b', '*b')) === 'a*b')
}

{
  const segments = highlightSegments('价格是 100% 折扣', '100%')
  eq('百分号字面匹配', hits(segments).join(','), '100%')
}

// ---- 多词：任一命中即高亮 ----

{
  const segments = highlightSegments('猫追老鼠，狗追猫', '狗 老鼠')
  eq('多词都命中', hits(segments).join(','), '老鼠,狗')
}

// ---- 重叠命中合并 ----

{
  const segments = highlightSegments('abcabc', 'abc bc')
  eq('重叠区间合并', hits(segments).join(','), 'abc,abc')
  eq('重叠后仍守恒', plain(segments), 'abcabc')
}

// ---- 多次出现 ----

{
  const segments = highlightSegments('周报周报周报', '周报')
  eq('多次出现全部命中', hits(segments).join(','), '周报,周报,周报')
}

// ---- 不变量：任意输入下原文守恒 ----

check(
  '内容守恒（随机样例）',
  (() => {
    const cases = [
      ['完全没命中', 'zzz'],
      ['<script>alert(1)</script>', 'script'],
      ['abc', 'abcd'],
      ['中文标题：周报', '周报'],
    ]
    return cases.every(([text, kw]) => plain(highlightSegments(text, kw)) === text)
  })(),
)
