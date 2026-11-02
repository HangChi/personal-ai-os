<script setup lang="ts">
import { computed } from 'vue'
import { Check, Close, Loading, Tools, Warning } from '@element-plus/icons-vue'
import type { RunActivity } from '@/api/chat-runs'
const props = defineProps<{ items: RunActivity[]; reconnecting?: boolean; generating?: boolean }>()
const running = computed(() => props.items.findLast((item) => item.status === 'RUNNING'))
const labels: Record<string, string> = {
  RUNNING: '执行中',
  OK: '已完成',
  DUPLICATE: '已复用结果',
  REQUIRES_CONFIRMATION: '等待你确认',
  ERROR: '执行失败',
  REJECTED: '未执行',
}
function action(tool: string): string {
  if (/exec|command|shell|terminal/i.test(tool)) return '执行命令'
  if (/search|query|list|get|find/i.test(tool)) return '查询信息'
  if (/Plan/.test(tool)) return '更新执行计划'
  return '调用工具'
}
const phase = computed(() =>
  props.reconnecting
    ? '连接中断，正在重新连接 · 任务在后台继续'
    : running.value
      ? `${action(running.value.tool)} · ${running.value.tool}`
      : props.generating
        ? '正在生成回答'
        : props.items.some((item) => item.status === 'REQUIRES_CONFIRMATION')
          ? '操作需要你确认'
          : '正在处理请求',
)
</script>
<template>
  <div class="execution">
    <div class="execution__phase" role="status">
      <el-icon class="is-loading"><Loading /></el-icon>{{ phase }}
    </div>
    <details v-if="items.length" class="execution__details" open>
      <summary>执行记录 · {{ items.length }} 项操作</summary>
      <ol>
        <li v-for="item in items" :key="item.id" :class="{ 'is-failed': ['ERROR', 'REJECTED'].includes(item.status) }">
          <el-icon :class="{ 'is-loading': item.status === 'RUNNING' }">
            <Loading v-if="item.status === 'RUNNING'" /><Warning
              v-else-if="item.status === 'REQUIRES_CONFIRMATION'"
            /><Close v-else-if="['ERROR', 'REJECTED'].includes(item.status)" /><Check v-else />
          </el-icon>
          <span class="execution__action"
            >{{ action(item.tool) }}<code>{{ item.tool }}</code></span
          >
          <span class="execution__status">{{ labels[item.status] ?? item.status }}</span>
          <span v-if="item.status !== 'RUNNING'" class="execution__time"
            >{{ (item.durationMs / 1000).toFixed(1) }}s</span
          >
        </li>
      </ol>
    </details>
    <div v-if="!items.length && !generating && !reconnecting" class="execution__note">
      <el-icon><Tools /></el-icon>工具执行后会在这里显示进度
    </div>
  </div>
</template>
<style scoped>
.execution {
  margin-bottom: var(--os-space-3);
  max-width: 720px;
  font-size: var(--os-font-sm);
}
.execution__phase {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  color: var(--el-text-color-regular);
  min-height: 32px;
  overflow-wrap: anywhere;
}
.execution__phase .el-icon {
  flex: none;
  color: var(--el-color-primary);
}
.execution__details {
  border-left: 2px solid var(--el-border-color);
  padding-left: var(--os-space-3);
  margin: var(--os-space-2) 0 0 6px;
}
summary {
  cursor: pointer;
  color: var(--el-text-color-secondary);
  min-height: 32px;
  line-height: 32px;
}
ol {
  list-style: none;
  padding: 0;
  margin: 0;
}
li {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  padding: var(--os-space-2) 0;
  flex-wrap: wrap;
}
li > .el-icon {
  color: var(--el-color-success);
}
li.is-failed > .el-icon {
  color: var(--el-color-danger);
}
.execution__action {
  display: flex;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  flex: 1;
  min-width: 0;
}
code {
  font-size: var(--os-font-xs);
  overflow-wrap: anywhere;
  color: var(--el-text-color-secondary);
}
.execution__status,
.execution__time {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}
.execution__time {
  font-variant-numeric: tabular-nums;
}
.execution__note {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}
summary:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
@media (prefers-reduced-motion: reduce) {
  .is-loading {
    animation: none;
  }
}
</style>
