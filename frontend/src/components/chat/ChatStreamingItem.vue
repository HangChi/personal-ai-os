<script setup lang="ts">
/**
 * 流式中的助手消息（从 ChatView 拆出，2026-10-26）。
 *
 * <p>首 token 之前显示三点打字动画，并附带检索状态文字（阶段 19 的状态机）：
 * 「正在检索知识库…」/「已找到 N 条依据」/「未找到相关依据」。
 * 有内容后就换成 Markdown 渲染，状态文字降到内容下方 ——
 * 位置变化是刻意的：它不该继续占据首屏的视觉焦点。
 */
import { ChatLineRound } from '@element-plus/icons-vue'
import { onBeforeUnmount, ref, watch } from 'vue'

import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import { createThrottle } from '@/lib/throttle'

const props = defineProps<{
  /** 已收到的增量内容；空串表示尚未收到首 token */
  content: string
  /** 检索状态提示；null 表示没有提示可显示 */
  hint: string | null
}>()

/**
 * 实际送去渲染的内容。
 *
 * <p>不直接把 `props.content` 交给 MarkdownRenderer：每个 SSE delta 都会让它变，
 * 而每次渲染要跑一遍 `marked.parse` + `DOMPurify.sanitize` + 整块 v-html 重写。
 * 模型连发几十个 delta 时，这就是几十次全量重解析，长回答下会明显卡顿 ——
 * 而人眼分辨不出「每 16ms 变」和「每 80ms 变」。
 *
 * <p>节流只限制**渲染频率**，不丢数据：窗口内保留最新值，流结束时 flush 一次
 * 把尾巴补上。这一步必须做，否则最后一帧可能停在定时器里，屏幕看起来像「回答被截断」。
 */
const renderedContent = ref(props.content)
// 显式给泛型：否则 createThrottle() 推断为 unknown，回调里的 value 拿不到 string 类型
const throttle = createThrottle<string>()

watch(
  () => props.content,
  (next) => {
    throttle.push(next, (value: string) => {
      renderedContent.value = value
    })
  },
)

onBeforeUnmount(() => {
  // 卸载前先冲刷一次：会话切换时最后几帧也要落到 DOM 上（虽然随即就卸载了，
  // 但 flush 后再 cancel 的语义是明确的，不依赖「反正要卸载」）
  throttle.flush()
  throttle.cancel()
})
</script>

<template>
  <div class="chat-streaming">
    <span class="chat-streaming__avatar">
      <el-icon :size="14"><ChatLineRound /></el-icon>
    </span>
    <div class="chat-streaming__content">
      <!--
        读屏提示（P3）：三点动画是纯视觉的，没有可读文本 —— 不补这一句的话，
        读屏用户只会察觉到「页面上多了一块东西」却不知道是什么。
      -->
      <span class="sr-only">助手正在回复</span>
      <!--
        首 token 前：三点打字动画装在同一个气泡里（阶段 24）。
        改造前它裸露在页面上，与首 token 到达后的助手气泡形态不一致 ——
        生成开始的一瞬间会「跳」一下。现在两者共用同一套气泡外观。
      -->
      <div v-if="!content" class="chat-streaming__bubble">
        <div class="chat-streaming__typing">
          <span class="chat-streaming__dot" />
          <span class="chat-streaming__dot" />
          <span class="chat-streaming__dot" />
          <span v-if="hint" class="chat-streaming__hint">{{ hint }}</span>
        </div>
      </div>
      <template v-else>
        <div class="chat-streaming__bubble">
          <!--
            必须绑 renderedContent（节流后的值），不能绑 content（原始 delta 流）。
            这里曾绑成 content：节流器照常被调用、renderedContent 照常被赋值，
            但没人读它 —— 于是每个 delta 都触发一次完整重解析，节流形同虚设，
            而 lint / vue-tsc 都不会报（ref 被写就算「已使用」）。
          -->
          <MarkdownRenderer :content="renderedContent" streaming />
        </div>
        <div v-if="hint" class="chat-streaming__hint chat-streaming__hint--below">{{ hint }}</div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.chat-streaming {
  display: flex;
  align-items: flex-start;
  gap: var(--os-space-3);
}

.chat-streaming__avatar {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  margin-top: 2px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  box-shadow: inset 0 0 0 1px var(--el-color-primary-light-7);
}

.chat-streaming__content {
  min-width: 0;
  flex: 1;
  font-size: var(--os-font-md);
  line-height: 1.72;
}

/*
 * 气泡外观必须与 ChatMessageItem 的 .chat-msg__bubble--assistant 完全一致，
 * 否则首 token 到达时会出现「形态跳变」。
 * 这两处是同一份视觉契约，改动时请成对修改（无共享组件，刻意的：
 * 流式态需要一个「打字点也能装进来」的变体，抽公共组件反而要加条件分支）。
 */
.chat-streaming__bubble {
  padding: 0.85em 1em;
  border-radius: var(--os-radius-2xl);
  border-bottom-left-radius: var(--os-radius-xs);
  background: var(--os-bubble-bg);
  border: 1px solid var(--os-bubble-border);
  box-shadow: var(--os-shadow-bubble);
  width: fit-content;
  max-width: 100%;
}

.chat-streaming__bubble :deep(.md__body) {
  font-size: var(--os-font-md);
  line-height: 1.72;
}

/* 首末块距由 MarkdownRenderer 统一清零（见 .md__html > :first-child 的注释）；
   原先这里写的 `.md__body > :first-child` 命中的是 v-html 包裹层，从没生效过。 */

/* ---- 流式打字动画（阶段 20，FR-004） ---- */

/* 气泡已给内边距，这里只留一点点上下让三点视觉居中，不再叠 padding */
.chat-streaming__typing {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 0;
}

.chat-streaming__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-text-color-placeholder);
  animation: chat-typing-bounce 1.2s infinite ease-in-out;
}

.chat-streaming__dot:nth-child(2) {
  animation-delay: 0.15s;
}

.chat-streaming__dot:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes chat-typing-bounce {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  30% {
    transform: translateY(-4px);
    opacity: 1;
  }
}

.chat-streaming__hint {
  margin-left: var(--os-space-2);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.chat-streaming__hint--below {
  margin: var(--os-space-1) 0 0;
}

/* 尊重系统「减少动态效果」偏好 */
@media (prefers-reduced-motion: reduce) {
  .chat-streaming__dot {
    animation: none;
  }
}
</style>
