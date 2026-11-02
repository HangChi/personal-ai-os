<script setup lang="ts">
/** 单次决定的内联授权卡片；所有影响信息直接可见，不再弹二次确认。 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { TaskConfirmation } from '@/types/api'
const props = defineProps<{
  items: TaskConfirmation[]
  resolvingId: string | null
  busy?: boolean
  error?: string | null
}>()
const emit = defineEmits<{ (e: 'resolve', confirmation: TaskConfirmation, approve: boolean): void }>()
const now = ref(Date.now())
const submittedId = ref<string | null>(null)
const resolving = computed(() => props.resolvingId ?? submittedId.value)
const timer = setInterval(() => {
  now.value = Date.now()
}, 1000)
onBeforeUnmount(() => clearInterval(timer))
watch(
  () => props.resolvingId,
  (id, previous) => {
    if (id === null && previous !== null) submittedId.value = null
  },
)
watch(
  () => props.items,
  (items) => {
    if (submittedId.value && !items.some((item) => item.id === submittedId.value)) submittedId.value = null
  },
)
function expired(item: TaskConfirmation): boolean {
  return Date.parse(item.expiresAt) <= now.value
}
function decide(item: TaskConfirmation, approve: boolean): void {
  if (resolving.value || props.busy || expired(item)) return
  submittedId.value = item.id
  emit('resolve', item, approve)
}
function actionLabel(action: string): string {
  const labels: Record<string, string> = {
    DELETE_TASK: '删除任务',
    SCHEDULE_TASK: '修改截止时间',
    SET_PRIORITY: '修改优先级',
    DELETE_CALENDAR_EVENT: '删除日程',
    MCP_TOOL_CALL: '调用外部工具',
  }
  return labels[action] ?? action
}
</script>
<template>
  <div class="chat-confirmations">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <section
      v-for="confirmation in items"
      :key="confirmation.id"
      class="chat-confirmations__item"
      :aria-label="`待确认：${actionLabel(confirmation.action)}`"
    >
      <div class="chat-confirmations__body">
        <div class="chat-confirmations__heading">
          <span class="chat-confirmations__title">是否允许{{ actionLabel(confirmation.action) }}？</span>
          <span class="chat-confirmations__status">{{ expired(confirmation) ? '已过期' : '等待你的决定' }}</span>
        </div>
        <p class="chat-confirmations__summary">{{ confirmation.summary }}</p>
        <div class="chat-confirmations__facts">
          <span>涉及 {{ confirmation.affectedCount }} 项</span>
          <span>有效至 {{ new Date(confirmation.expiresAt).toLocaleString('zh-CN') }}</span>
        </div>
        <p v-if="confirmation.action.startsWith('DELETE_')" class="chat-confirmations__risk">删除后无法撤销。</p>
        <p v-if="expired(confirmation)" class="chat-confirmations__note" role="status">
          确认已过期，请让助手重新申请。
        </p>
        <p v-else class="chat-confirmations__note">
          只授权这一次操作。暂不选择会保留待确认；确认后直接执行，不再发送消息。
        </p>
        <div class="chat-confirmations__footer">
          <span v-if="busy" class="chat-confirmations__meta">本轮结束后可确认</span>
          <div class="chat-confirmations__actions">
            <button
              type="button"
              class="chat-confirmations__cancel"
              :disabled="resolving !== null || busy || expired(confirmation)"
              @click="decide(confirmation, false)"
            >
              拒绝
            </button>
            <button
              type="button"
              class="chat-confirmations__approve"
              :class="{ 'chat-confirmations__approve--danger': confirmation.action.startsWith('DELETE_') }"
              :disabled="resolving !== null || busy || expired(confirmation)"
              :aria-busy="resolving === confirmation.id"
              @click="decide(confirmation, true)"
            >
              <span v-if="resolving === confirmation.id" class="chat-confirmations__spinner" aria-hidden="true" />
              {{ resolving === confirmation.id ? '正在处理' : '允许本次操作' }}
            </button>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>
<style scoped>
.chat-confirmations__facts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--os-space-1) var(--os-space-3);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}
.chat-confirmations__note {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.6;
  margin: var(--os-space-2) 0;
}
.chat-confirmations__risk {
  color: var(--el-color-danger);
  font-size: var(--os-font-xs);
  margin: var(--os-space-2) 0;
}
.chat-confirmations__actions > button:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
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

@media (prefers-reduced-motion: reduce) {
  .chat-confirmations__spinner {
    animation: none;
  }
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
