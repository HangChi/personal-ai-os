<script setup lang="ts">
import { CHAT_COMMANDS } from '@/lib/chat-commands'
import type { CommandEntry } from '@/stores/chat-commands'
import type { DirectTool } from '@/api/chat-tools'
defineProps<{
  view: 'help' | 'tools' | null
  tools: DirectTool[]
  loading: boolean
  error: string
  entries: CommandEntry[]
}>()
const emit = defineEmits<{ (event: 'close'): void; (event: 'fill', value: string): void; (event: 'reload'): void }>()
const labels: Record<string, string> = {
  OK: '已完成',
  DUPLICATE: '已回放，未重复执行',
  REQUIRES_CONFIRMATION: '待确认',
  REJECTED: '已拒绝',
  ERROR: '结果需核对',
}
</script>

<template>
  <section v-if="view || entries.length" class="command-panel" aria-label="快捷操作面板">
    <header v-if="view">
      <strong>{{ view === 'help' ? '快捷指令' : '可用工具' }}</strong>
      <el-button text size="small" aria-label="关闭快捷指令帮助" @click="emit('close')">关闭</el-button>
    </header>
    <template v-if="view === 'help'">
      <p>指令直接匹配工具，无需对话模型解析。删除等操作仍需确认；普通文本以 / 开头时，请输入 //。</p>
      <button
        v-for="command in CHAT_COMMANDS"
        :key="command.name"
        type="button"
        class="command-panel__command"
        @click="emit('fill', '/' + command.name + ' ')"
      >
        <code>{{ command.usage }}</code
        ><span>{{ command.description }}</span>
      </button>
    </template>
    <template v-if="view === 'tools'">
      <p v-if="loading" role="status">正在加载工具…</p>
      <p v-else-if="error" role="alert">
        {{ error }} <el-button size="small" @click="emit('reload')">重新加载</el-button>
      </p>
      <p v-else-if="!tools.length">当前没有可用工具。</p>
      <details v-for="tool in tools" :key="tool.name">
        <summary>
          <code>{{ tool.name }}</code> · {{ tool.description }}
        </summary>
        <pre>{{ tool.inputSchema }}</pre>
        <el-button size="small" @click="emit('fill', '/tool ' + tool.name + ' ')">填入指令</el-button>
      </details>
    </template>
    <article v-for="entry in entries" :key="entry.id" class="command-panel__result" :aria-busy="entry.busy">
      <code>{{ entry.input }}</code>
      <p v-if="entry.busy" role="status">正在执行快捷操作…</p>
      <p v-else-if="entry.error" role="alert">{{ entry.error }}</p>
      <template v-else-if="entry.execution">
        <p role="status">
          <strong>{{ labels[entry.execution.result.status] ?? '未知状态，请核对' }}</strong> ·
          {{ entry.execution.result.message }}<span v-if="entry.execution.replayed">（原请求回放）</span>
        </p>
        <p v-if="entry.execution.result.status === 'REQUIRES_CONFIRMATION'">尚未执行，请在上方待确认操作中处理。</p>
        <details v-if="entry.execution.result.data != null">
          <summary>查看结果</summary>
          <pre>{{ JSON.stringify(entry.execution.result.data, null, 2) }}</pre>
        </details>
      </template>
    </article>
  </section>
</template>

<style scoped>
.command-panel {
  flex: none;
  max-height: min(320px, 35vh);
  overflow: auto;
  margin: var(--os-space-2) var(--os-chat-gutter);
  padding: var(--os-space-3);
  border: 1px solid var(--el-border-color);
  border-radius: var(--os-radius-md);
  background: var(--el-bg-color);
  font-size: var(--os-font-sm);
  overflow-wrap: anywhere;
}
.command-panel header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.command-panel p {
  margin: var(--os-space-2) 0;
  color: var(--el-text-color-regular);
}
.command-panel__command {
  display: flex;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  width: 100%;
  padding: var(--os-space-2);
  background: transparent;
  color: var(--el-text-color-primary);
  border: 0;
  text-align: left;
  cursor: pointer;
}
.command-panel__command:hover {
  background: var(--el-fill-color-light);
}
.command-panel__command:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
}
.command-panel__command span {
  color: var(--el-text-color-secondary);
}
.command-panel details {
  padding: var(--os-space-2) 0;
}
.command-panel summary {
  cursor: pointer;
}
.command-panel pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 240px;
  overflow: auto;
  padding: var(--os-space-2);
  background: var(--el-fill-color-light);
  font-size: var(--os-font-xs);
}
.command-panel__result {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: var(--os-space-2);
  margin-top: var(--os-space-2);
}
</style>
