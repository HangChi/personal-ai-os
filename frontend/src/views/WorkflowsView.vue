<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import {
  workflows,
  saveWorkflow,
  runWorkflow,
  workflowHistory,
  retryWorkflow,
  type Workflow,
  type WorkflowChange,
  type WorkflowExecution,
} from '@/api/workflows'
const items = ref<Workflow[]>([])
const total = ref(0)
const busy = ref(false)
const error = ref('')
const editing = ref<string | null>(null)
const selected = ref<Workflow | null>(null)
const history = ref<WorkflowExecution[]>([])
const historyTotal = ref(0)
const kinds = { MORNING_BRIEF: '晨报', WEEKLY_REVIEW: '周报', KNOWLEDGE_REVIEW: '资料巡检' }
const status: Record<string, string> = { PENDING: '待执行', RUNNING: '执行中', SUCCEEDED: '成功', FAILED: '失败' }
const defaults = (): WorkflowChange => ({
  name: '',
  kind: 'MORNING_BRIEF',
  timezone: 'Asia/Shanghai',
  localTime: '09:00',
  weekday: 1,
  enabled: false,
  maxRunsPerDay: 3,
  maxItems: 30,
  maxOutputChars: 10000,
})
const form = reactive<WorkflowChange>(defaults())
async function load(): Promise<void> {
  const v = await workflows()
  items.value = v.items
  total.value = v.total
}
async function loadHistory(row: Workflow): Promise<void> {
  const v = await workflowHistory(row.id)
  selected.value = row
  history.value = v.items
  historyTotal.value = v.total
}
async function action(work: () => Promise<void>): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await work()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败，请重试'
  } finally {
    busy.value = false
  }
}
function edit(row: Workflow): void {
  editing.value = row.id
  Object.assign(form, row)
  document.getElementById('workflow-name')?.focus()
}
function reset(): void {
  editing.value = null
  Object.assign(form, defaults())
}
async function save(): Promise<void> {
  await action(async () => {
    await saveWorkflow(editing.value, form)
    await load()
    reset()
    ElMessage.success('计划已保存')
  })
}
async function toggle(row: Workflow): Promise<void> {
  await action(async () => {
    await saveWorkflow(row.id, { enabled: !row.enabled })
    await load()
  })
}
async function run(row: Workflow): Promise<void> {
  await action(async () => {
    await runWorkflow(row.id)
    await loadHistory(row)
    ElMessage.success('只读报告已进入队列，请刷新执行历史')
  })
}
async function retry(row: WorkflowExecution): Promise<void> {
  await action(async () => {
    await retryWorkflow(row.id)
    if (selected.value) await loadHistory(selected.value)
  })
}
onMounted(() => {
  void action(load)
})
</script>
<template>
  <div class="workflows">
    <PageHeader size="page" title="定时报告"
      ><el-button :loading="busy" @click="action(load)">刷新计划</el-button></PageHeader
    >
    <el-alert
      title="固定只读报告 · 零模型调用"
      description="晨报汇总今日和逾期待办与日程，周报回顾前 7 天，资料巡检检查本地资料和采集状态。每次尝试均计入每日上限，单期最多尝试 3 次。"
      type="info"
      :closable="false"
      show-icon
    />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-card shadow="never">
      <template #header>{{ editing ? '编辑计划' : '新建计划' }}</template>
      <form class="workflow-form" @submit.prevent="save">
        <label>计划名称<el-input id="workflow-name" v-model="form.name" aria-label="计划名称" maxlength="100" /></label>
        <label
          >报告类型<el-select v-model="form.kind" aria-label="报告类型"
            ><el-option v-for="(label, value) in kinds" :key="value" :label="label" :value="value" /></el-select
        ></label>
        <label
          >IANA 时区<el-input
            v-model="form.timezone"
            aria-label="IANA 时区"
            placeholder="Asia/Shanghai"
            maxlength="100"
        /></label>
        <label>当地执行时间<input v-model="form.localTime" aria-label="当地执行时间" type="time" required /></label>
        <label v-if="form.kind === 'WEEKLY_REVIEW'"
          >每周执行日<el-select v-model="form.weekday" aria-label="每周执行日"
            ><el-option
              v-for="(label, i) in ['周一', '周二', '周三', '周四', '周五', '周六', '周日']"
              :key="i"
              :value="i + 1"
              :label="label" /></el-select
        ></label>
        <label
          >每日尝试上限<el-input-number v-model="form.maxRunsPerDay" aria-label="每日尝试上限" :min="1" :max="24"
        /></label>
        <label>数据项上限<el-input-number v-model="form.maxItems" aria-label="数据项上限" :min="1" :max="100" /></label>
        <label
          >输出字符上限<el-input-number
            v-model="form.maxOutputChars"
            aria-label="输出字符上限"
            :min="1000"
            :max="20000"
            :step="1000"
        /></label>
        <div class="actions">
          <el-checkbox v-model="form.enabled">启用自动执行</el-checkbox
          ><el-button type="primary" native-type="submit" :loading="busy" :disabled="!form.name?.trim()"
            >保存计划</el-button
          ><el-button v-if="editing" :disabled="busy" @click="reset">取消编辑</el-button>
        </div>
      </form>
      <p class="hint">新计划默认禁用。夏令时缺失时刻向后移，重复时刻取较晚一次；恢复后最多补跑一个错过的期次。</p>
    </el-card>
    <el-card shadow="never">
      <template #header>计划 · {{ total }} 个</template>
      <el-empty v-if="!items.length" description="创建一个晨报、周报或资料巡检计划" />
      <p v-if="total > items.length">仅显示最近 {{ items.length }} 个计划。</p>
      <article v-for="row in items" :key="row.id" class="workflow-row">
        <div class="actions">
          <strong>{{ row.name }}</strong
          ><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '已启用' : '已禁用' }}</el-tag
          ><span>{{ kinds[row.kind] }}</span>
        </div>
        <p>
          {{ row.timezone }} · {{ row.localTime }}{{ row.kind === 'WEEKLY_REVIEW' ? ` · 每周${row.weekday}` : '' }} ·
          每日最多 {{ row.maxRunsPerDay }} 次尝试
        </p>
        <p class="hint">
          {{ row.enabled ? '下次自动执行' : '启用后的下次时间' }}：{{ new Date(row.nextRunAt).toLocaleString() }} ·
          {{ row.maxItems }} 项 / {{ row.maxOutputChars }} 字符
        </p>
        <div class="actions">
          <el-button :disabled="busy" @click="edit(row)">编辑</el-button
          ><el-button :disabled="busy" @click="toggle(row)">{{ row.enabled ? '禁用' : '启用' }}</el-button
          ><el-button :disabled="busy" @click="run(row)">立即生成</el-button
          ><el-button :disabled="busy" @click="action(() => loadHistory(row))">执行历史</el-button>
        </div>
      </article>
    </el-card>
    <el-card v-if="selected" shadow="never">
      <template #header
        ><div class="actions">
          <span>执行历史 · {{ selected.name }} · {{ historyTotal }} 期</span
          ><el-button :loading="busy" @click="action(() => loadHistory(selected!))">刷新历史</el-button>
        </div></template
      >
      <p>展示最近 {{ history.length }} 期；每次尝试都有独立记录。报告会话只读。</p>
      <el-empty v-if="!history.length" description="尚无执行记录" />
      <article v-for="row in history" :key="row.id" class="workflow-row">
        <p>
          {{ new Date(row.scheduledFor).toLocaleString() }} · {{ row.source === 'MANUAL' ? '手动' : '定时' }} ·
          {{ status[row.status] }} · 已尝试 {{ row.attempts }}/3 · 模型调用 {{ row.modelCalls }}
        </p>
        <p v-if="row.error" role="alert">{{ row.error }}</p>
        <el-button v-if="row.status === 'FAILED' && row.attempts < 3" :disabled="busy" @click="retry(row)"
          >重试此期</el-button
        >
        <ul v-if="row.history.length">
          <li v-for="attempt in row.history" :key="attempt.attempt">
            第 {{ attempt.attempt }} 次 · {{ status[attempt.status] ?? attempt.status }} ·
            {{ new Date(attempt.startedAt).toLocaleString() }} {{ attempt.error }}
          </li>
        </ul>
        <pre v-if="row.report">{{ row.report }}</pre>
        <router-link v-if="row.conversationId" :to="{ path: '/chat', query: { conversationId: row.conversationId } }"
          >查看只读报告会话</router-link
        >
      </article>
    </el-card>
  </div>
</template>
<style scoped>
.workflows {
  display: grid;
  gap: var(--os-space-4);
  min-width: 0;
}
.workflow-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: var(--os-space-4);
}
.workflow-form label {
  display: grid;
  gap: var(--os-space-2);
  color: var(--el-text-color-regular);
}
.workflow-form input[type='time'] {
  font: inherit;
  padding: 6px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-blank);
}
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--os-space-2);
}
.workflow-form > .actions {
  grid-column: 1/-1;
}
.workflow-row {
  padding: var(--os-space-4) 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  overflow-wrap: anywhere;
}
.workflow-row:last-child {
  border-bottom: 0;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
}
pre {
  max-height: 28rem;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  background: var(--el-fill-color-light);
  padding: var(--os-space-4);
  border-radius: var(--el-border-radius-base);
}
@media (max-width: 600px) {
  .workflow-form {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
