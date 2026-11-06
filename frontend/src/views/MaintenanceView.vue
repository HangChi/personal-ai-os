<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { downloadExport } from '@/api/export'
import {
  maintenanceSnapshot,
  verifyBackup,
  recordDrill,
  type MaintenanceSnapshot,
  type MaintenanceCheck,
  type MaintenanceItem,
} from '@/api/maintenance'
const data = ref<MaintenanceSnapshot | null>(null)
const current = ref<MaintenanceCheck | null>(null)
const busy = ref(false)
const error = ref('')
const localNow = (): string => {
  const now = new Date()
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
}
const drill = reactive({
  tool: 'scripts/dev/backup.py drill',
  summary: '',
  outcome: 'PASSED',
  completedAt: localNow(),
  artifactDigest: '',
})
const statuses: Record<string, string> = {
  VALID: '静态校验通过',
  INVALID: '校验未通过',
  UNVERIFIED: '外部记录 · 未独立验证',
  RUNNING: '运行中',
  INTERRUPTED: '已中断',
  STOPPED: '已停止',
  WAITING_USER: '等待回答',
  WAITING_CONFIRMATION: '等待确认',
  FAILED: '失败',
}
const roleNames: Record<string, string> = {
  combined: '综合进程',
  api: 'API',
  index: '索引 Worker',
  review: '审阅与报告 Worker',
  delivery: '通知 Worker',
}
const queues = computed(() =>
  data.value
    ? [
        {
          title: '索引失败',
          items: data.value.failedIndexJobs,
          description: '已清除的任务正文不能直接重放，请重新上传文件或在网页采集中检查更新。',
          to: '/knowledge',
        },
        {
          title: '通知投递失败',
          items: data.value.failedDeliveries,
          description: '检查通知订阅与投递设置，避免重复发送外部通知。',
          to: '/settings?section=calendar',
        },
        {
          title: '报告失败',
          items: data.value.failedWorkflows,
          description: '每日额度包含失败尝试；达到单期 3 次上限后不能再重试此期。',
          to: '/workflows',
        },
        {
          title: '待查看的对话执行',
          items: data.value.unfinishedRuns,
          description: '等待回答、等待确认、主动停止与故障中断需要分别处理。',
          to: '/chat',
        },
      ]
    : [],
)
function when(at: string | undefined): string {
  return at ? new Date(at).toLocaleString() : '—'
}
function link(row: MaintenanceItem, fallback: string): string | { path: string; query: { conversationId: string } } {
  return row.conversationId ? { path: '/chat', query: { conversationId: row.conversationId } } : fallback
}
async function load(): Promise<void> {
  data.value = await maintenanceSnapshot()
}
async function action(work: () => Promise<void>): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await work()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '维护操作失败，请重试'
  } finally {
    busy.value = false
  }
}
async function backup(): Promise<void> {
  await action(async () => {
    await downloadExport()
    ElMessage.success('业务备份已下载')
  })
}
async function verify(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  await action(async () => {
    current.value = null
    if (file.size > 20 * 1024 * 1024) throw new Error('业务 JSON 最多 20MiB')
    current.value = await verifyBackup(await file.arrayBuffer())
    await load()
  })
}
async function saveDrill(): Promise<void> {
  await action(async () => {
    current.value = await recordDrill({
      tool: drill.tool,
      summary: drill.summary,
      outcome: drill.outcome,
      completedAt: new Date(drill.completedAt).toISOString(),
      ...(drill.artifactDigest ? { artifactDigest: drill.artifactDigest } : {}),
    })
    await load()
    ElMessage.success('已记录外部报告，尚未独立验证')
  })
}
onMounted(() => {
  void action(load)
})
</script>
<template>
  <div class="maintenance">
    <PageHeader size="page" title="维护与恢复" description="检查运行状态，保留校验与演练记录"
      ><el-button :loading="busy" @click="action(load)">刷新维护状态</el-button></PageHeader
    >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-card shadow="never">
      <template #header>业务备份与静态校验</template>
      <p>
        业务 JSON
        包含项目、任务、日程、会话、记忆和知识分块；不含附件、配置、运行队列、定时计划或网页版本历史。完整迁移请使用数据库与附件备份包。
      </p>
      <div class="actions">
        <el-button type="primary" :loading="busy" @click="backup">下载业务备份</el-button
        ><label class="file-control"
          >选择 JSON 校验<input
            aria-label="选择业务备份校验"
            type="file"
            accept=".json,application/json"
            :disabled="busy"
            @change="verify"
        /></label>
      </div>
      <p class="hint">
        最多
        20MiB；仅检查格式、类型、标识与引用关系，不导入数据、不调用模型。静态通过不等于恢复成功；知识分块实际恢复仍需嵌入模型。单类最多
        10000 条、消息和分块各最多 50000 条，问题最多展示 100 项。
      </p>
      <div v-if="current" class="check-result" role="status">
        <strong>{{ statuses[current.status] ?? current.status }}</strong>
        <p>{{ when(current.createdAt) }}</p>
        <p class="digest">
          输入内容摘要：<code>{{ current.digest }}</code>
        </p>
        <p v-if="current.counts">
          记录数：{{
            Object.entries(current.counts)
              .map(([key, value]) => `${key} ${value}`)
              .join(' · ')
          }}
        </p>
        <ul v-if="current.issues?.length">
          <li v-for="(issue, i) in current.issues" :key="i">{{ issue }}</li>
        </ul>
        <p v-if="current.truncated">问题已达到展示上限，尚有其他记录未继续检查。</p>
        <p v-if="current.source === 'EXTERNAL'">
          {{ current.tool }} · {{ current.outcome === 'PASSED' ? '报告为通过' : '报告为失败' }} ·
          {{ current.summary }}（外部填报，未独立验证）
        </p>
      </div>
    </el-card>
    <el-card v-if="data" shadow="never">
      <template #header>运行进程心跳 · {{ data.heartbeats.total }} 个实例</template>
      <p class="hint">
        每 30 秒更新，90 秒未续写显示失联。这里显示进程所属角色，在线不代表所有任务都成功。数据时间：{{
          when(data.generatedAt)
        }}。
      </p>
      <el-empty v-if="!data.heartbeats.items.length" description="尚未收到心跳，请确认维护心跳已启用" />
      <p v-if="data.heartbeats.total > data.heartbeats.items.length">
        展示最近 {{ data.heartbeats.items.length }} 个实例。
      </p>
      <article v-for="row in data.heartbeats.items" :key="row.instanceId" class="maintenance-row heartbeat-row">
        <div class="actions">
          <strong>{{ row.backend }} · {{ roleNames[row.role] ?? row.role }}</strong
          ><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'">{{
            row.status === 'ACTIVE' ? '在线' : '已失联'
          }}</el-tag>
        </div>
        <p>最近心跳 {{ when(row.lastSeenAt) }} · 启动 {{ when(row.startedAt) }}</p>
        <code>{{ row.instanceId }}</code>
      </article>
    </el-card>
    <div class="queue-grid">
      <el-card v-for="queue in queues" :key="queue.title" shadow="never">
        <template #header
          ><div class="actions">
            <strong>{{ queue.title }}</strong
            ><el-tag :type="queue.items.total ? 'warning' : 'info'">{{ queue.items.total }}</el-tag>
          </div></template
        >
        <p class="hint">{{ queue.description }}</p>
        <el-empty v-if="!queue.items.items.length" description="当前没有记录" :image-size="48" />
        <p v-if="queue.items.total > queue.items.items.length">展示最近 {{ queue.items.items.length }} 条。</p>
        <article v-for="row in queue.items.items" :key="row.id" class="maintenance-row">
          <div>{{ row.name ?? row.title ?? row.kind ?? row.id }}</div>
          <p>
            {{ statuses[row.status] ?? row.status
            }}{{ row.attempts !== undefined ? ` · 已尝试 ${row.attempts} 次` : '' }} · {{ when(row.updatedAt) }}
          </p>
          <p v-if="row.error">{{ row.error }}</p>
          <router-link :to="link(row, queue.to)">查看与处理</router-link>
        </article>
        <router-link :to="queue.to">打开相关页面</router-link>
      </el-card>
    </div>
    <el-card shadow="never">
      <template #header>数据库与附件恢复演练</template>
      <p>
        在受控维护窗口使用既有 CLI
        创建、验证备份包，并在随机命名的独立数据库中演练恢复。网页只保存外部报告，不执行恢复。
      </p>
      <pre>
python3 scripts/dev/backup.py backup
python3 scripts/dev/backup.py verify &lt;备份文件&gt;
python3 scripts/dev/backup.py drill &lt;备份文件&gt;</pre>
      <form class="drill-form" @submit.prevent="saveDrill">
        <label>工具<el-input v-model="drill.tool" aria-label="演练工具" maxlength="80" /></label>
        <label
          >完成时间<input v-model="drill.completedAt" aria-label="演练完成时间" type="datetime-local" required
        /></label>
        <label
          >CLI 报告结果<el-select v-model="drill.outcome" aria-label="CLI 报告结果"
            ><el-option label="报告为通过" value="PASSED" /><el-option label="报告为失败" value="FAILED" /></el-select
        ></label>
        <label class="wide"
          >报告摘要<el-input
            v-model="drill.summary"
            aria-label="外部演练摘要"
            type="textarea"
            :rows="3"
            maxlength="2000"
            placeholder="填写执行环境、检查范围和实际结果"
        /></label>
        <label class="wide"
          >备份包 SHA-256（可选）<el-input v-model="drill.artifactDigest" aria-label="备份包摘要" maxlength="64"
        /></label>
        <div class="wide actions">
          <el-button native-type="submit" :loading="busy" :disabled="!drill.summary.trim() || !drill.tool.trim()"
            >记录外部报告</el-button
          ><span class="hint">始终标记为外部记录，未独立验证</span>
        </div>
      </form>
    </el-card>
    <el-card v-if="data" shadow="never">
      <template #header>校验与外部报告历史 · {{ data.checks.total }} 条</template>
      <p class="hint">保留最近 200 条摘要，展示最近 20 条；原始备份文件不落库。</p>
      <el-empty v-if="!data.checks.items.length" description="选择一份业务 JSON 开始校验" />
      <article v-for="row in data.checks.items" :key="row.id" class="maintenance-row">
        <div class="actions">
          <strong>{{ row.kind === 'BUSINESS_JSON' ? '业务 JSON' : '外部演练报告' }}</strong
          ><el-tag :type="row.status === 'VALID' ? 'success' : row.status === 'INVALID' ? 'danger' : 'warning'">{{
            statuses[row.status] ?? row.status
          }}</el-tag>
        </div>
        <p>{{ when(row.createdAt) }}</p>
        <code>{{ row.digest }}</code>
        <details>
          <summary>查看记录详情</summary>
          <p v-if="row.summary">{{ row.tool }} · {{ row.outcome }} · {{ row.summary }} · {{ when(row.completedAt) }}</p>
          <p v-if="row.counts">{{ row.counts }}</p>
          <ul v-if="row.issues?.length">
            <li v-for="(issue, i) in row.issues" :key="i">{{ issue }}</li>
          </ul>
          <p v-if="row.truncated">问题超过展示上限。</p>
        </details>
      </article>
    </el-card>
  </div>
</template>
<style scoped>
.maintenance {
  display: grid;
  gap: var(--os-space-4);
  min-width: 0;
}
p {
  line-height: 1.65;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--os-space-3);
  align-items: center;
}
.hint {
  font-size: var(--os-font-sm);
  color: var(--el-text-color-secondary);
}
.file-control {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--os-space-2);
  font-size: var(--os-font-sm);
}
.file-control input {
  max-width: 100%;
}
.check-result {
  margin-top: var(--os-space-4);
  padding: var(--os-space-4);
  border-left: 3px solid var(--el-color-primary);
  background: var(--el-fill-color-light);
  overflow-wrap: anywhere;
}
.queue-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--os-space-4);
}
.maintenance-row {
  padding: var(--os-space-3) 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  overflow-wrap: anywhere;
}
.maintenance-row:last-child {
  border-bottom: 0;
}
code {
  font-size: var(--os-font-sm);
  overflow-wrap: anywhere;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--el-fill-color-light);
  padding: var(--os-space-3);
  line-height: 1.7;
}
.drill-form {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--os-space-4);
}
.drill-form label {
  display: grid;
  gap: var(--os-space-2);
}
.drill-form input[type='datetime-local'] {
  font: inherit;
  min-width: 0;
  padding: 6px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-blank);
}
.wide {
  grid-column: 1/-1;
}
summary {
  cursor: pointer;
  margin-top: var(--os-space-3);
}
@media (max-width: 650px) {
  .queue-grid,
  .drill-form {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
