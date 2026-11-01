<script setup lang="ts">
/**
 * 待确认的高风险操作（从 ChatView 拆出，2026-10-26）。
 *
 * <p>助手只能「申请」执行；真正生效必须在这里由人点确认。
 * 删除任务的二次确认与处理结果由父组件负责。
 */
import { Lock } from '@element-plus/icons-vue'

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
      <el-icon class="chat-confirmations__icon" aria-hidden="true"><Lock /></el-icon>
      <div class="chat-confirmations__body">
        <div class="chat-confirmations__heading">
          <span class="chat-confirmations__title">{{ actionLabel(confirmation.action) }}</span>
          <span class="chat-confirmations__status">等待确认</span>
        </div>
        <p class="chat-confirmations__summary">{{ confirmation.summary }}</p>
        <div class="chat-confirmations__footer">
          <span class="chat-confirmations__meta">涉及 {{ confirmation.affectedCount }} 项</span>
          <div class="chat-confirmations__actions">
            <el-button size="small" text :disabled="resolvingId !== null" @click="emit('resolve', confirmation, false)">
              取消
            </el-button>
            <el-button
              size="small"
              :type="confirmation.action.startsWith('DELETE_') ? 'danger' : 'primary'"
              plain
              :loading="resolvingId === confirmation.id"
              :disabled="resolvingId !== null"
              @click="emit('resolve', confirmation, true)"
            >
              {{ confirmation.action.startsWith('DELETE_') ? '确认删除' : '确认执行' }}
            </el-button>
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
}

.chat-confirmations__item {
  display: flex;
  align-items: flex-start;
  gap: var(--os-space-3);
  width: 100%;
  max-width: 720px;
  padding: var(--os-space-3) var(--os-space-4);
  border: 1px solid var(--el-border-color);
  border-radius: var(--os-radius-md);
  background: var(--el-bg-color);
}

.chat-confirmations__icon {
  flex: none;
  margin-top: 3px;
  color: var(--el-text-color-secondary);
}

.chat-confirmations__body {
  flex: 1;
  min-width: 0;
}

.chat-confirmations__heading {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  flex-wrap: wrap;
}

.chat-confirmations__title {
  font-size: var(--os-font-sm);
  font-weight: 500;
}

.chat-confirmations__status {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.chat-confirmations__summary {
  margin: var(--os-space-2) 0 var(--os-space-3);
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
  gap: var(--os-space-2);
  flex: none;
  margin-left: auto;
}

.chat-confirmations__actions > .el-button {
  margin: 0;
}
</style>
