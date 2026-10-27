<script setup lang="ts">
/**
 * 待确认的高风险操作（从 ChatView 拆出，2026-10-26）。
 *
 * <p>助手只能「申请」执行；真正生效必须在这里由人点确认。
 * 二次确认（删除类）仍由父组件负责 —— 它需要弹窗与错误提示，属于交互编排，
 * 不是这块列表的职责。
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
    <el-alert type="warning" :closable="false" show-icon title="助手申请执行高风险操作，需要你确认后才会真正生效" />
    <div v-for="confirmation in items" :key="confirmation.id" class="chat-confirmations__item">
      <div class="chat-confirmations__body">
        <el-tag type="warning" size="small" effect="plain">
          {{ actionLabel(confirmation.action) }}
        </el-tag>
        <span class="chat-confirmations__summary">{{ confirmation.summary }}</span>
        <span class="chat-confirmations__meta">影响 {{ confirmation.affectedCount }} 项</span>
      </div>
      <div class="chat-confirmations__actions">
        <el-button
          size="small"
          :loading="resolvingId === confirmation.id"
          @click="emit('resolve', confirmation, false)"
        >
          取消
        </el-button>
        <el-button
          size="small"
          type="danger"
          :loading="resolvingId === confirmation.id"
          @click="emit('resolve', confirmation, true)"
        >
          确认执行
        </el-button>
      </div>
    </div>
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
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-3);
  padding: var(--os-space-2) var(--os-space-3);
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: var(--os-radius-md);
  background: var(--el-color-warning-light-9);
}

.chat-confirmations__body {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  min-width: 0;
}

.chat-confirmations__summary {
  font-size: var(--os-font-xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-confirmations__meta {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  white-space: nowrap;
}

.chat-confirmations__actions {
  flex: none;
}
</style>
