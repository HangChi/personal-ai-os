/**
 * 代码块头部条的构造（语言名 + 纯图标复制按钮）。
 *
 * <p>为什么独立成文件：这段逻辑原先内联在 MarkdownRenderer 的 `decorateCodeBlocks` 里，
 * 而后者依赖 `container` ref 与 DOMPurify 的产出。**在 happy-dom 下 DOMPurify 会把
 * `<div class="md-code">` 这一层剥掉**（实测 `<div><pre>..</pre></div>` 出来只剩
 * `<pre>..</pre>`，而真实浏览器不会），于是整个头部条根本没机会被创建 ——
 * 想把「按钮是不是纯图标、有没有可读名称」写成测试，就只能测到环境差异。
 *
 * <p>抽出来之后它只碰 DOM、不碰 Markdown 管线，任何环境都能直接验证。
 *
 * <p>另一个约束：图标必须用 `createVNode` + `render` 渲染真实组件，不能手写
 * innerHTML 抄 path 字符串 —— 手抄的 path 在图标库升级后会悄悄过时，抄错了也没人发现；
 * 走同一个组件才能与消息操作区的图标天然一致。
 */
import { Check, CopyDocument } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { createVNode, render } from 'vue'
import type { Component } from 'vue'

/** 复制成功后对勾停留的时长（ms）。与消息操作区的反馈节奏保持一致。 */
export const COPY_DONE_MS = 1500

/**
 * 构造代码块头部条。
 *
 * @param language 语言名（从 hljs class 读出；未识别传 `text`）
 * @param codeText 该代码块的纯文本，点击复制时写入剪贴板
 */
export function buildCodeHeader(language: string, codeText: string): HTMLElement {
  const header = document.createElement('div')
  header.className = 'md-code__header'

  const label = document.createElement('span')
  label.className = 'md-code__lang'
  label.textContent = language === 'text' ? '代码' : language

  const button = document.createElement('button')
  button.type = 'button'
  button.className = 'md-code__copy'
  /*
   * 纯图标必须有可读名称。
   *
   * 去掉「复制」两个字之后，按钮在无障碍树里就没有名字了 —— 读屏只念「按钮」，
   * 鼠标悬停也看不出这是干什么的。title 管后者，aria-label 管前者。
   * aria-label 带上语言名，是因为一屏可能有多个代码块，
   * 读屏用户需要能区分「复制 python 代码」和「复制 java 代码」。
   */
  button.title = '复制代码'
  button.setAttribute('aria-label', `复制 ${language} 代码`)

  /**
   * 把图标画进按钮。
   *
   * 复制成功后换成对勾、1.5 秒后还原 —— 去掉文字后这是**唯一的反馈渠道**：
   * 没有它，用户点完不知道到底成没成，而「代码不在剪贴板里」是事后才会发现的静默失败。
   */
  const paint = (icon: Component): void => {
    render(createVNode(icon), button)
  }
  paint(CopyDocument)

  button.addEventListener('click', () => {
    void navigator.clipboard.writeText(codeText).then(
      () => {
        paint(Check)
        button.classList.add('md-code__copy--done')
        window.setTimeout(() => {
          paint(CopyDocument)
          button.classList.remove('md-code__copy--done')
        }, COPY_DONE_MS)
      },
      () => {
        ElMessage.error('复制失败，请手动选择复制')
      },
    )
  })

  header.append(label, button)
  return header
}
