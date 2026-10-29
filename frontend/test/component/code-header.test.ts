import { describe, expect, it, vi, beforeEach } from 'vitest'

import { buildCodeHeader, COPY_DONE_MS } from '@/lib/code-header'

/**
 * 代码块头部条（语言名 + 纯图标复制按钮）。
 *
 * <p>为什么测这个函数而不是整条渲染链：头部条原先内联在
 * `MarkdownRenderer.decorateCodeBlocks` 里，而那条链在 happy-dom 下会断在
 * **DOMPurify 把 `<div class="md-code">` 剥掉**这一步（真实浏览器不会）——
 * 于是任何断言都只能测到环境差异，测不到按钮本身。
 * 把构造逻辑抽成纯 DOM 函数后，下面的断言在任何环境都成立。
 *
 * <p>断言集中在两件「去掉文字后最容易退化、且退化后无人察觉」的事：
 * 图标是否真的画进了按钮、可读名称是否还在。
 */
describe('代码块头部条', () => {
  beforeEach(() => {
    // happy-dom 没有 clipboard，按需注入；每个用例单独设，避免相互污染
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn(() => Promise.resolve()) },
      configurable: true,
      writable: true,
    })
  })

  it('按钮是纯图标：没有文字，但有真实 SVG', () => {
    const header = buildCodeHeader('python', 'print(1)')
    const button = header.querySelector('.md-code__copy') as HTMLButtonElement

    expect(button).toBeTruthy()
    // 只保留图标：文本必须为空，否则「不要留文字」这条要求就退化回去了
    expect(button.textContent?.trim()).toBe('')
    expect(button.querySelector('svg')).toBeTruthy()
  })

  it('纯图标必须有可读名称，且带上语言以便区分多个代码块', () => {
    const header = buildCodeHeader('java', 'int x = 1;')
    const button = header.querySelector('.md-code__copy') as HTMLButtonElement

    expect(button.title).toBeTruthy()
    expect(button.getAttribute('aria-label')).toBe('复制 java 代码')
  })

  it('语言名渲染在左侧、按钮在右侧 —— 顺序反了会让「语言」看起来像按钮标签', () => {
    const header = buildCodeHeader('sql', 'select 1')
    const label = header.querySelector('.md-code__lang')

    expect(label?.textContent).toBe('sql')
    expect(header.children[0]).toBe(label)
    expect(header.children[1]).toBe(header.querySelector('.md-code__copy'))
  })

  it('点击写入的是该代码块的原文，而不是别的块或其他内容', async () => {
    const code = 'def f(x):\n    return x'
    const header = buildCodeHeader('python', code)
    const button = header.querySelector('.md-code__copy') as HTMLButtonElement

    button.click()
    await Promise.resolve()

    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(code)
  })

  it('成功后换成对勾并打上 done 类 —— 去掉文字后这是唯一的成功反馈', async () => {
    vi.useFakeTimers()
    const header = buildCodeHeader('python', 'x')
    const button = header.querySelector('.md-code__copy') as HTMLButtonElement
    const beforePathCount = button.querySelectorAll('path').length

    button.click()
    // 让 writeText 的 then 跑完
    await vi.advanceTimersByTimeAsync(0)

    expect(button.classList.contains('md-code__copy--done')).toBe(true)
    // 对勾与复制是两个不同图标，path 数量不同 —— 据此确认图标真的换了
    expect(button.querySelectorAll('path').length).not.toBe(beforePathCount)

    // 超时后必须还原，否则对勾会永远停在那里，看起来像「这个块已被永久复制」
    await vi.advanceTimersByTimeAsync(COPY_DONE_MS)
    expect(button.classList.contains('md-code__copy--done')).toBe(false)
    expect(button.querySelectorAll('path').length).toBe(beforePathCount)

    vi.useRealTimers()
  })
})
