<script setup lang="ts">
/**
 * Markdown 渲染器（阶段 18，contracts C-01）。
 *
 * 管线（顺序即契约，两步都不可省）：
 *   marked.parse(content) → DOMPurify.sanitize(html) → v-html
 * 漏消毒 = 存储型 XSS（消息内容可来自模型输出、用户输入、知识库文档）。
 *
 * 代码高亮：highlight.js 核心 + 按需注册的 10 种语言（research R-02）。
 * 漏注册的语言回落 plaintext（不高亮但不报错）。
 *
 * 超长内容（> LIMIT 字符）截断预览 + 「展开全文」：marked 是同步解析，
 * 流式期间每帧全量重解析 5 万字符会掉帧（C-01 用例 7）。
 */
import { ElMessage } from 'element-plus'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import markdown from 'highlight.js/lib/languages/markdown'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { computed, nextTick, ref, watch } from 'vue'

hljs.registerLanguage('java', java)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('python', python)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('json', json)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('yaml', yaml)
hljs.registerLanguage('markdown', markdown)
// plaintext 是内置回落，无需注册

marked.setOptions({ gfm: true, breaks: true })

/**
 * 代码块自定义渲染：marked 默认只输出裸 <pre><code>，必须挂上 hljs。
 * 漏了这一步的表现是「代码块在、但没有一行高亮」——语言注册了却没人调用。
 *
 * hljs.highlight 的输入是纯文本、输出自带转义，因此不引入新的注入面；
 * 之后照旧过 DOMPurify（管线顺序不变）。
 *
 * ⚠️ 未注册的语言（plaintext 在 hljs 里不是可注册语言）不能直接喂给
 * hljs.highlight——它会抛 "Unknown language"，而这个异常会穿过 marked.parse
 * 一路把 Vue 渲染带崩（实测：整页连环 pageerror）。未识别语言走 escapeHTML
 * 原文展示，不高亮但不炸。
 */
/** HTML 转义（hljs 11 不再导出 escapeHTML，这四行就是它的全部） */
function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

marked.use({
  renderer: {
    code({ text, lang }: { text: string; lang?: string }): string {
      if (lang && hljs.getLanguage(lang)) {
        const { value } = hljs.highlight(text, { language: lang })
        return `<pre><code class="hljs language-${lang}">${value}</code></pre>`
      }
      return `<pre><code class="hljs">${escapeHtml(text)}</code></pre>`
    },
  },
})

const props = defineProps<{
  content: string
  /** 流式输出中：显示打字光标 */
  streaming?: boolean
}>()

/** 超过该长度先截断，避免流式期间同步解析超长文本掉帧 */
const LIMIT = 10_000

const expanded = ref(false)
const container = ref<HTMLElement | null>(null)

const truncated = computed(() => props.content.length > LIMIT)
const visibleContent = computed(() =>
  truncated.value && !expanded.value ? `${props.content.slice(0, LIMIT)}…` : props.content,
)

const sanitizedHtml = computed(() => {
  const raw = marked.parse(visibleContent.value, { async: false }) as string
  return DOMPurify.sanitize(raw)
})

/**
 * 给每个代码块加复制按钮。
 *
 * 在渲染后用 DOM 后处理注入（而不是写进 marked 渲染器）：v-html 的内容
 * 无法挂 Vue 事件，按钮用原生 addEventListener，并用 data 属性标记
 * 「已处理」，避免流式重渲染时重复添加。
 */
async function decorateCodeBlocks(): Promise<void> {
  await nextTick()
  const root = container.value
  if (!root) {
    return
  }
  for (const pre of root.querySelectorAll('pre')) {
    if (pre.dataset.decorated === '1') {
      continue
    }
    pre.dataset.decorated = '1'
    const button = document.createElement('button')
    button.type = 'button'
    button.className = 'md-code-copy'
    button.textContent = '复制'
    button.addEventListener('click', () => {
      const code = pre.querySelector('code')?.textContent ?? ''
      void navigator.clipboard.writeText(code).then(
        () => {
          button.textContent = '已复制'
          window.setTimeout(() => {
            button.textContent = '复制'
          }, 1500)
        },
        () => {
          ElMessage.error('复制失败，请手动选择复制')
        },
      )
    })
    pre.appendChild(button)
  }
}

watch(sanitizedHtml, () => void decorateCodeBlocks(), { immediate: true })

function toggleExpand(): void {
  expanded.value = !expanded.value
}
</script>

<template>
  <div class="md">
    <div ref="container" class="md__body" :class="{ 'md__body--streaming': streaming }">
      <!-- eslint-disable-next-line vue/no-v-html -- 内容已过 DOMPurify 消毒（C-01） -->
      <div v-html="sanitizedHtml"></div>
    </div>
    <el-button v-if="truncated" class="md__expand" link type="primary" size="small" @click="toggleExpand">
      {{ expanded ? '收起' : `展开全文（${content.length} 字符）` }}
    </el-button>
  </div>
</template>

<style scoped>
.md {
  min-width: 0;
}

.md__body {
  font-size: var(--os-font-sm);
  line-height: 1.7;
  word-break: break-word;
}

/* 流式光标：块级元素末尾不好插伪元素，用右侧边线模拟 */
.md__body--streaming::after {
  content: '';
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--el-color-primary);
  animation: md-cursor-blink 0.8s steps(2) infinite;
}

@keyframes md-cursor-blink {
  50% {
    opacity: 0;
  }
}

.md__expand {
  margin-top: var(--os-space-1);
}

/* ---- 渲染后的 Markdown 元素排版（EP 不管 v-html 内容的间距）---- */
.md__body :deep(h1),
.md__body :deep(h2),
.md__body :deep(h3),
.md__body :deep(h4) {
  margin: var(--os-space-3) 0 var(--os-space-2);
  line-height: 1.4;
  font-weight: 600;
}

.md__body :deep(h1) {
  font-size: var(--os-font-lg);
}

.md__body :deep(h2) {
  font-size: var(--os-font-md);
}

.md__body :deep(h3),
.md__body :deep(h4) {
  font-size: var(--os-font-sm);
}

.md__body :deep(h1:first-child),
.md__body :deep(h2:first-child),
.md__body :deep(h3:first-child),
.md__body :deep(p:first-child) {
  margin-top: 0;
}

.md__body :deep(p) {
  margin: var(--os-space-2) 0;
}

.md__body :deep(ul),
.md__body :deep(ol) {
  margin: var(--os-space-2) 0;
  padding-left: 1.4em;
}

.md__body :deep(li) {
  margin: 2px 0;
}

.md__body :deep(a) {
  color: var(--el-color-primary);
}

/* 外链加标识，避免看不出是可点的 */
.md__body :deep(a[href^='http'])::after {
  content: '↗';
  margin-left: 2px;
  font-size: 0.85em;
}

.md__body :deep(blockquote) {
  margin: var(--os-space-2) 0;
  padding: 2px var(--os-space-3);
  border-left: 3px solid var(--el-border-color);
  color: var(--el-text-color-secondary);
}

.md__body :deep(code:not(pre code)) {
  padding: 1px 5px;
  border-radius: var(--os-radius-sm);
  background: var(--el-fill-color);
  font-size: 0.92em;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
}

.md__body :deep(pre) {
  position: relative;
  margin: var(--os-space-2) 0;
  padding: var(--os-space-2) var(--os-space-3);
  border-radius: var(--os-radius-md);
  overflow-x: auto;
  /* highlight.js 主题（github / github-dark）画背景与字色 */
  font-size: var(--os-font-xs);
  line-height: 1.6;
}

.md__body :deep(pre code) {
  background: none;
  padding: 0;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
}

.md__body :deep(table) {
  margin: var(--os-space-2) 0;
  border-collapse: collapse;
  font-size: var(--os-font-xs);
}

.md__body :deep(th),
.md__body :deep(td) {
  padding: 4px 10px;
  border: 1px solid var(--el-border-color-lighter);
}

.md__body :deep(th) {
  background: var(--el-fill-color-light);
}

.md__body :deep(hr) {
  margin: var(--os-space-3) 0;
  border: none;
  border-top: 1px solid var(--el-border-color-lighter);
}

/* 代码块复制按钮：绝对定位在右上角，悬停才明显（不挡代码） */
.md__body :deep(.md-code-copy) {
  position: absolute;
  top: 6px;
  right: 6px;
  padding: 2px 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-sm);
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font-size: 11px;
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--os-duration-fast) var(--os-ease);
}

.md__body :deep(pre:hover .md-code-copy) {
  opacity: 1;
}

.md__body :deep(.md-code-copy:hover) {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary-light-5);
}
</style>
