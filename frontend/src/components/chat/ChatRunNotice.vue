<script setup lang="ts">
import { computed } from 'vue'
import { InfoFilled, RefreshRight } from '@element-plus/icons-vue'
import type { ChatRun } from '@/api/chat-runs'
const props = defineProps<{ run: ChatRun; busy?: boolean }>()
defineEmits<{ resume: []; summarize: [] }>()
const disconnected = computed(() => props.run.status === 'RUNNING')
const uncertain = computed(() => !disconnected.value && props.run.recoveryMode === 'SUMMARY_ONLY')
const recovered = computed(() => {
  let value = false
  for (const event of props.run.events) {
    if (event.name === 'reset') value = false
    if (event.name === 'warning') {
      try {
        if (JSON.parse(event.payload).code === 'AGENT_RECOVERY') value = true
      } catch {
        /* Older malformed notices must not hide recovery actions. */
      }
    }
  }
  return value
})
const title = computed(() =>
  disconnected.value ? '连接已断开，任务仍在后台执行' : uncertain.value ? '工具执行结果待核实' : '任务已暂停',
)
const description = computed(() =>
  disconnected.value
    ? '重新连接可以查看最新进度，不会重新提交任务。'
    : uncertain.value
      ? recovered.value
        ? '已有结果已在上方整理。部分操作仍需核实，可查看活动记录；其他问题可以继续发送。'
        : '部分工具未返回确定结果，已暂停自动执行。可先整理已有结果，不会再次调用工具。'
      : '进度已保存。若工具连续失败或达到执行上限，可先检查工具配置，也可以整理已有结果。',
)
</script>

<template>
  <section class="run-notice" role="status" aria-live="polite">
    <el-icon class="run-notice__icon"><InfoFilled /></el-icon>
    <div class="run-notice__body">
      <p class="run-notice__title">{{ title }}</p>
      <p class="run-notice__description">{{ description }}</p>
    </div>
    <div class="run-notice__actions">
      <router-link v-if="uncertain" to="/activity" class="run-notice__link">查看活动记录</router-link>
      <el-button v-if="!uncertain" text size="small" :icon="RefreshRight" :loading="busy" @click="$emit('resume')">
        {{ disconnected ? '重新连接' : '继续任务' }}
      </el-button>
      <el-button v-if="!disconnected && !recovered" text size="small" :loading="busy" @click="$emit('summarize')">
        整理已有结果
      </el-button>
    </div>
  </section>
</template>

<style scoped>
.run-notice {
  display: flex;
  align-items: flex-start;
  gap: var(--os-space-3);
  padding-block: var(--os-space-3);
  border-top: 1px solid var(--el-border-color-light);
}
.run-notice__icon {
  margin-top: 3px;
  color: var(--el-text-color-regular);
  flex-shrink: 0;
}
.run-notice__body {
  flex: 1;
  min-width: 0;
}
.run-notice__title {
  margin: 0;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.run-notice__description {
  margin: var(--os-space-1) 0 0;
  color: var(--el-text-color-regular);
  font-size: var(--os-font-sm);
  line-height: 1.6;
}
.run-notice__actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
}
.run-notice__actions .el-button + .el-button {
  margin-left: 0;
}
.run-notice__link {
  color: var(--el-color-primary);
  font-size: var(--os-font-sm);
  padding: var(--os-space-2);
}
@media (max-width: 760px) {
  .run-notice {
    flex-wrap: wrap;
  }
  .run-notice__actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
