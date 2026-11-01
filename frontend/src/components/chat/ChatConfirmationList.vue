<script setup lang="ts">
/**
 * 待确认的高风险操作（从 ChatView 拆出，2026-10-26）。
 *
 * <p>助手只能「申请」执行；真正生效必须在这里由人点确认。
 * 删除任务的二次确认与处理结果由父组件负责。
 */
import type { TaskConfirmation } from '@/types/api'

defineProps<{
  items: TaskConfirmation[]
  /** 正在处理的那一条（按钮显示 loading，防止重复点击） */
  resolvingId: string | null
}>()

const emit = defineEmits<{ (e: 'resolve', confirmation: TaskConfirmation, approve: boolean): void }>()

/** 高风险操作的中文名；未识别时直接展示原始动作码，避免「未知操作」这类无用提示 */
function actionLabel(action: string): string {
  switch (action) {
    case 'DELETE_TASK':
      return '删除任务'
    case 'SCHEDULE_TASK':
      return '修改截止时间'
    case 'SET_PRIORITY':
      return '修改优先级'
    case 'DELETE_CALENDAR_EVENT':
      return '删除日程'
    case 'MCP_TOOL_CALL':
      return '调用外部工具'
    default:
      return action
  }
}
</script>

<template>
  <div class="chat-confirmations">
    <section
      v-for="confirmation in items"
      :key="confirmation.id"
      class="chat-confirmations__item"
      :aria-label="`待确认：${actionLabel(confirmation.action)}`"
    >
      <div class="chat-confirmations__body">
        <div class="chat-confirmations__heading">
          <span class="chat-confirmations__title">{{ actionLabel(confirmation.action) }}</span>
          <span class="chat-confirmations__status">需要确认</span>
        </div>
        <p class="chat-confirmations__summary">{{ confirmation.summary }}</p>
        <div class="chat-confirmations__footer">
          <span class="chat-confirmations__meta">涉及 {{ confirmation.affectedCount }} 项</span>
          <div class="chat-confirmations__actions">
            <button
              type="button"
              class="chat-confirmations__cancel"
              :disabled="resolvingId !== null"
              @click="emit('resolve', confirmation, false)"
            >
              取消
            </button>
            <button
              type="button"
              class="chat-confirmations__approve"
              :class="{ 'chat-confirmations__approve--danger': confirmation.action.startsWith('DELETE_') }"
              :disabled="resolvingId !== null"
              :aria-busy="resolvingId === confirmation.id"
              @click="emit('resolve', confirmation, true)"
            >
              <span v-if="resolvingId === confirmation.id" class="chat-confirmations__spinner" aria-hidden="true" />
              {{
                resolvingId === confirmation.id
                  ? '正在处理'
                  : confirmation.action.startsWith('DELETE_')
                    ? '确认删除'
                    : '确认执行'
              }}
            </button>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.chat-confirmations {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
  padding-left: 44px;
}

.chat-confirmations__item {
  width: 100%;
  max-width: 600px;
  padding: var(--os-space-3) var(--os-space-4);
  border-left: 2px solid var(--el-color-primary-light-5);
  border-radius: 0 var(--os-radius-lg) var(--os-radius-lg) 0;
  background: var(--el-fill-color-light);
}

.chat-confirmations__body {
  flex: 1;
  min-width: 0;
}

.chat-confirmations__heading {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  justify-content: space-between;
  flex-wrap: wrap;
}

.chat-confirmations__title {
  font-size: var(--os-font-sm);
  font-weight: 600;
}

.chat-confirmations__status {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.chat-confirmations__summary {
  margin: var(--os-space-2) 0;
  color: var(--el-text-color-regular);
  font-size: var(--os-font-sm);
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.chat-confirmations__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--os-space-2);
}

.chat-confirmations__meta {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  white-space: nowrap;
}

.chat-confirmations__actions {
  display: flex;
  gap: var(--os-space-3);
  flex: none;
  margin-left: auto;
}

.chat-confirmations__actions > button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 28px;
  margin: 0;
  padding: 4px 10px;
  border: 0;
  border-radius: var(--os-radius-sm);
  font: inherit;
  font-size: var(--os-font-xs);
  line-height: 20px;
  cursor: pointer;
  transition: background var(--os-duration-fast) var(--os-ease);
}

.chat-confirmations__cancel {
  color: var(--el-text-color-secondary);
  background: transparent;
}

.chat-confirmations__cancel:hover:not(:disabled) {
  color: var(--el-text-color-primary);
  background: var(--el-fill-color);
}

.chat-confirmations__approve {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.chat-confirmations__approve:hover:not(:disabled) {
  background: var(--el-color-primary-light-8);
}

.chat-confirmations__approve--danger {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}

.chat-confirmations__approve--danger:hover:not(:disabled) {
  background: var(--el-color-danger-light-8);
}

.chat-confirmations__actions > button:disabled {
  opacity: 0.65;
  cursor: default;
}

.chat-confirmations__spinner {
  width: 12px;
  height: 12px;
  border: 1.5px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: confirmation-spin 0.8s linear infinite;
}

@keyframes confirmation-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 600px) {
  .chat-confirmations {
    padding-left: 0;
  }
}

@media (pointer: coarse) {
  .chat-confirmations__actions > button {
    min-height: 44px;
  }
}
</style>
