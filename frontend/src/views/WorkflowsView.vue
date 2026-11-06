<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { formatDateTime } from '@/lib/format'
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
const editorOpen = ref(false)
const historyOpen = ref(false)
const enabledCount = computed(() => items.value.filter((item) => item.enabled).length)
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
  historyOpen.value = true
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
function focusName(): void {
  document.getElementById('workflow-name')?.focus()
}
function edit(row: Workflow): void {
  editing.value = row.id
  Object.assign(form, {
    name: row.name,
    kind: row.kind,
    timezone: row.timezone,
    localTime: row.localTime,
    weekday: row.weekday,
    enabled: row.enabled,
    maxRunsPerDay: row.maxRunsPerDay,
    maxItems: row.maxItems,
    maxOutputChars: row.maxOutputChars,
  })
  editorOpen.value = true
}
function openCreate(): void {
  reset()
  error.value = ''
  editorOpen.value = true
}
function reset(): void {
  editing.value = null
  Object.assign(form, defaults())
}
async function save(): Promise<void> {
  await action(async () => {
    await saveWorkflow(editing.value, { ...form })
    await load()
    reset()
    editorOpen.value = false
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
  <div class="workflows os-page">
    <PageHeader size="page" title="定时报告" description="把晨报、周报和资料巡检交给固定计划，随时回看执行记录。">
      <el-button :icon="Refresh" :loading="busy" @click="action(load)">刷新计划</el-button>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建计划</el-button>
    </PageHeader>
    <div class="workflow-overview">
      <span
        ><strong>{{ total }}</strong> 个计划 · {{ enabledCount }} 个已启用</span
      ><span>只读汇总 · 不调用模型</span>
    </div>
    <el-alert v-if="error && !editorOpen" :title="error" type="error" :closable="false" show-icon />
    <el-card v-loading="busy" shadow="never" class="workflow-list">
      <el-empty v-if="!items.length && !busy" description="还没有报告计划，先创建一个晨报试试" :image-size="88"
        ><el-button type="primary" @click="openCreate">创建第一个计划</el-button></el-empty
      >
      <article v-for="row in items" :key="row.id" class="workflow-row">
        <div class="workflow-row__heading">
          <div class="actions">
            <h2>{{ row.name }}</h2>
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{
              row.enabled ? '已启用' : '已禁用'
            }}</el-tag>
          </div>
          <span class="workflow-kind">{{ kinds[row.kind] }}</span>
        </div>
        <p class="workflow-schedule">
          {{ row.localTime
          }}<span v-if="row.kind === 'WEEKLY_REVIEW'">
            · {{ ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'][row.weekday] }}</span
          ><span> · {{ row.timezone }}</span>
        </p>
        <p class="hint">
          {{ row.enabled ? '下次自动执行' : '启用后的下次时间' }}：{{ formatDateTime(row.nextRunAt) }} · 每日最多
          {{ row.maxRunsPerDay }} 次尝试
        </p>
        <div class="workflow-row__footer">
          <span class="hint">{{ row.maxItems }} 项 · 最多 {{ row.maxOutputChars.toLocaleString() }} 字符</span>
          <div class="actions">
            <el-button text :disabled="busy" @click="edit(row)">编辑</el-button>
            <el-button text :disabled="busy" @click="toggle(row)">{{ row.enabled ? '禁用' : '启用' }}</el-button>
            <el-button :disabled="busy" @click="action(() => loadHistory(row))">执行历史</el-button>
            <el-button type="primary" plain :disabled="busy" @click="run(row)">立即生成</el-button>
          </div>
        </div>
      </article>
    </el-card>
    <details class="os-disclosure">
      <summary>了解报告范围与执行规则</summary>
      <p>
        晨报汇总今日和逾期待办与日程，周报回顾前 7
        天，资料巡检检查本地资料和采集状态。每次尝试均计入每日上限，单期最多尝试 3 次。
      </p>
      <p>夏令时缺失时刻向后移，重复时刻取较晚一次；恢复后最多补跑一个错过的期次。</p>
    </details>

    <el-dialog
      v-model="editorOpen"
      :title="editing ? '编辑计划' : '新建计划'"
      width="min(660px, calc(100vw - 32px))"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      @opened="focusName"
    >
      <form id="workflow-editor-form" class="workflow-form" @submit.prevent="save">
        <label class="workflow-form__wide"
          >计划名称<el-input
            id="workflow-name"
            v-model="form.name"
            aria-label="计划名称"
            placeholder="例如：每日工作晨报"
            maxlength="100"
            :disabled="busy"
        /></label>
        <label
          >报告类型<el-select v-model="form.kind" aria-label="报告类型" :disabled="busy"
            ><el-option v-for="(label, value) in kinds" :key="value" :label="label" :value="value" /></el-select
        ></label>
        <label
          >时区<el-input
            v-model="form.timezone"
            aria-label="IANA 时区"
            placeholder="Asia/Shanghai"
            maxlength="100"
            :disabled="busy"
        /></label>
        <label
          >当地执行时间<input v-model="form.localTime" aria-label="当地执行时间" type="time" required :disabled="busy"
        /></label>
        <label v-if="form.kind === 'WEEKLY_REVIEW'"
          >每周执行日<el-select v-model="form.weekday" aria-label="每周执行日" :disabled="busy"
            ><el-option
              v-for="(label, i) in ['周一', '周二', '周三', '周四', '周五', '周六', '周日']"
              :key="i"
              :value="i + 1"
              :label="label" /></el-select
        ></label>
        <details class="os-disclosure workflow-form__wide">
          <summary>执行与输出限制</summary>
          <div class="workflow-form__limits">
            <label
              >每日尝试上限<el-input-number
                v-model="form.maxRunsPerDay"
                aria-label="每日尝试上限"
                :min="1"
                :max="24"
                :disabled="busy"
            /></label>
            <label
              >数据项上限<el-input-number
                v-model="form.maxItems"
                aria-label="数据项上限"
                :min="1"
                :max="100"
                :disabled="busy"
            /></label>
            <label
              >输出字符上限<el-input-number
                v-model="form.maxOutputChars"
                aria-label="输出字符上限"
                :min="1000"
                :max="20000"
                :step="1000"
                :disabled="busy"
            /></label>
          </div>
        </details>
        <div class="workflow-form__wide">
          <el-checkbox v-model="form.enabled" :disabled="busy">启用自动执行</el-checkbox>
          <p class="hint">新计划默认禁用；开启后按所选时区自动生成报告。</p>
        </div>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="workflow-form__wide" />
      </form>
      <template #footer>
        <el-button :disabled="busy" @click="editorOpen = false">取消</el-button>
        <el-button
          type="primary"
          native-type="submit"
          form="workflow-editor-form"
          :loading="busy"
          :disabled="!form.name?.trim() || !form.timezone?.trim()"
          >保存计划</el-button
        >
      </template>
    </el-dialog>
    <el-drawer v-model="historyOpen" :title="`执行历史 · ${selected?.name ?? ''}`" size="min(680px, 100vw)">
      <template v-if="selected"
        ><div class="actions">
          <span class="hint">最近 {{ history.length }} / {{ historyTotal }} 期</span
          ><el-button :loading="busy" @click="action(() => loadHistory(selected!))">刷新历史</el-button>
        </div>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <el-empty v-if="!history.length" description="尚无执行记录，可在计划中点击“立即生成”" />
        <article v-for="row in history" :key="row.id" class="workflow-row">
          <div class="actions">
            <strong>{{ formatDateTime(row.scheduledFor) }}</strong
            ><el-tag :type="row.status === 'FAILED' ? 'danger' : row.status === 'SUCCEEDED' ? 'success' : 'info'">{{
              status[row.status]
            }}</el-tag>
          </div>
          <p class="hint">
            {{ row.source === 'MANUAL' ? '手动' : '定时' }} · 已尝试 {{ row.attempts }}/3 · 模型调用
            {{ row.modelCalls }}
          </p>
          <el-alert v-if="row.error" :title="row.error" type="error" :closable="false" />
          <el-button v-if="row.status === 'FAILED' && row.attempts < 3" :disabled="busy" @click="retry(row)"
            >重试此期</el-button
          >
          <details v-if="row.history.length" class="os-disclosure">
            <summary>查看 {{ row.history.length }} 次尝试</summary>
            <ul>
              <li v-for="attempt in row.history" :key="attempt.attempt">
                第 {{ attempt.attempt }} 次 · {{ status[attempt.status] ?? attempt.status }} ·
                {{ formatDateTime(attempt.startedAt) }} {{ attempt.error }}
              </li>
            </ul>
          </details>
          <pre v-if="row.report">{{ row.report }}</pre>
          <router-link v-if="row.conversationId" :to="{ path: '/chat', query: { conversationId: row.conversationId } }"
            >查看只读报告会话</router-link
          >
        </article>
      </template>
    </el-drawer>
  </div>
</template>
<style scoped>
.workflows {
  min-width: 0;
}
.workflow-overview,
.workflow-row__heading,
.workflow-row__footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-3);
}
.workflow-overview {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
}
.workflow-overview strong {
  color: var(--el-text-color-primary);
}
.workflow-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--os-space-4);
}
.workflow-form > label,
.workflow-form__limits > label {
  display: grid;
  gap: var(--os-space-2);
  font-size: var(--os-font-sm);
  color: var(--el-text-color-regular);
}
.workflow-form input[type='time'] {
  width: 100%;
  min-width: 0;
  font: inherit;
  padding: 7px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-blank);
}
.workflow-form__wide {
  grid-column: 1/-1;
}
.workflow-form__limits {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--os-space-3);
  margin-top: var(--os-space-4);
}
.workflow-form__limits :deep(.el-input-number) {
  width: 100%;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--os-space-2);
}
.actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.workflow-row {
  padding: var(--os-space-5) 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  overflow-wrap: anywhere;
}
.workflow-row:first-child {
  padding-top: 0;
}
.workflow-row:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}
.workflow-row h2 {
  margin: 0;
  font-size: var(--os-font-lg);
  font-weight: 600;
}
.workflow-kind {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
}
.workflow-schedule {
  font-size: var(--os-font-lg);
  font-variant-numeric: tabular-nums;
}
.workflow-schedule span {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
  line-height: 1.65;
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
  .workflow-row__footer > .actions {
    width: 100%;
  }
  .workflow-overview {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--os-space-1);
  }
}
</style>
