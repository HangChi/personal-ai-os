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

/*
 * 徽标改成「浅底主色 + 圆形」：原先是实心主色方块，与消息区助手头像
 * （浅底）两种风格并存，同一页里出现两个视觉主角。圆形也呼应头像形状。
 */
.chat-welcome__badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  border: 1px solid var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: var(--os-font-md);
  font-weight: 500;
}

.chat-welcome__title {
  margin: var(--os-space-4) 0 0;
  font-size: var(--os-font-lg);
  font-weight: 500;
  color: var(--el-text-color-primary);
}

.chat-welcome__hint {
  margin: var(--os-space-2) 0 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.6;
}

.chat-welcome__chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--os-space-2);
  margin-top: var(--os-space-4);
}

/*
 * 建议问题：改造前 hover 只变边框和文字色，在浅底上几乎没有反馈。
 * 补上底色变化与 1px 上移 —— 它们是「可点击」的最直接信号。
 */
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
    background var(--os-duration-fast) var(--os-ease),
    color var(--os-duration-fast) var(--os-ease),
    transform var(--os-duration-fast) var(--os-ease);
}

.chat-welcome__suggestion:hover {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  transform: translateY(-1px);
}
@media (max-width: 900px) {
  /* 窄屏收紧：欢迎区每多 10px，输入框就离首屏远 10px */
  .chat-welcome {
    padding: var(--os-space-3);
  }

  .chat-welcome__badge {
    width: 40px;
    height: 40px;
  }

  .chat-welcome__chips {
    margin-top: var(--os-space-3);
  }
}
</style>
