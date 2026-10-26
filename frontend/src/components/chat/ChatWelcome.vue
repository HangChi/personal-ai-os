<script setup lang="ts">
/**
 * 空态欢迎区（从 ChatView 拆出，2026-10-26）。
 *
 * <p>建议问题只**填入输入框**，不自动发送 —— 用户通常还要改一下再发，
 * 自动发送会让人措手不及（并消耗一次模型调用）。
 */
defineProps<{ suggestions: string[] }>()

const emit = defineEmits<{ (e: 'pick', suggestion: string): void }>()
</script>

<template>
  <div class="chat-welcome">
    <span class="chat-welcome__badge">AI</span>
    <h2 class="chat-welcome__title">有什么可以帮你？</h2>
    <p class="chat-welcome__hint">可以让我记任务、查知识库、安排日程，或者只是聊聊。</p>
    <div class="chat-welcome__chips">
      <button
        v-for="item in suggestions"
        :key="item"
        type="button"
        class="chat-welcome__suggestion"
        @click="emit('pick', item)"
      >
        {{ item }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.chat-welcome {
  margin: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: var(--os-space-5);
}

.chat-welcome__badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--os-radius-lg);
  background: var(--el-color-primary);
  color: var(--el-color-white);
  font-size: var(--os-font-md);
  font-weight: 500;
}

.chat-welcome__title {
  margin: var(--os-space-3) 0 0;
  font-size: var(--os-font-lg);
  font-weight: 500;
}

.chat-welcome__hint {
  margin: var(--os-space-1) 0 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.chat-welcome__chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--os-space-2);
  margin-top: var(--os-space-4);
}

.chat-welcome__suggestion {
  padding: 6px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 999px;
  background: var(--el-bg-color);
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
  cursor: pointer;
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    color var(--os-duration-fast) var(--os-ease);
}

.chat-welcome__suggestion:hover {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}
</style>
