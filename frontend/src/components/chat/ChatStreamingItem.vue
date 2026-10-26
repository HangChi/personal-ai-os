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

import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

defineProps<{
  /** 已收到的增量内容；空串表示尚未收到首 token */
  content: string
  /** 检索状态提示；null 表示没有提示可显示 */
  hint: string | null
}>()
</script>

<template>
  <div class="chat-streaming">
    <span class="chat-streaming__avatar">
      <el-icon :size="14"><ChatLineRound /></el-icon>
    </span>
    <div class="chat-streaming__content">
      <!-- 首 token 前：三点打字动画（检索中附状态文字） -->
      <div v-if="!content" class="chat-streaming__typing">
        <span class="chat-streaming__dot" />
        <span class="chat-streaming__dot" />
        <span class="chat-streaming__dot" />
        <span v-if="hint" class="chat-streaming__hint">{{ hint }}</span>
      </div>
      <template v-else>
        <MarkdownRenderer :content="content" streaming />
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
  width: 28px;
  height: 28px;
  margin-top: 2px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.chat-streaming__content {
  min-width: 0;
  flex: 1;
  font-size: var(--os-font-sm);
  line-height: 1.75;
}

/* ---- 流式打字动画（阶段 20，FR-004） ---- */

.chat-streaming__typing {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: var(--os-space-2) 0;
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
  font-size: 11px;
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
