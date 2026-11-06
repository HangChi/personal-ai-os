<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listCaptures,
  captureUrl,
  refreshCapture,
  captureVersions,
  type Capture,
  type CaptureVersion,
} from '@/api/knowledge-captures'
const url = ref('')
const items = ref<Capture[]>([])
const total = ref(0)
const busy = ref(false)
const error = ref('')
const history = ref<CaptureVersion[]>([])
const historyTotal = ref(0)
const selected = ref<CaptureVersion | null>(null)
const source = ref('')
const names: Record<string, string> = {
  PENDING: '等待索引',
  RUNNING: '正在索引',
  SUCCEEDED: '已索引',
  FAILED: '失败，可刷新重试',
  EMPTY: '尚无成功版本',
}
async function load(): Promise<void> {
  const data = await listCaptures()
  items.value = data.items
  total.value = data.total
}
async function action(work: () => Promise<void>): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await work()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '采集操作失败，请重试'
  } finally {
    busy.value = false
  }
}
async function capture(): Promise<void> {
  await action(async () => {
    const result = await captureUrl(url.value)
    ElMessage.success(result.pendingJobId ? '已进入后台索引队列' : '正文未变化，沿用当前版本')
    url.value = ''
    await load()
  })
}
async function refresh(row: Capture): Promise<void> {
  await action(async () => {
    const result = await refreshCapture(row.id)
    ElMessage.success(result.pendingJobId ? '索引任务处理中' : '正文未变化')
    await load()
  })
}
async function versions(row: Capture): Promise<void> {
  await action(async () => {
    const result = await captureVersions(row.id)
    history.value = result.items
    historyTotal.value = result.total
    selected.value = result.items[0] ?? null
    source.value = row.url
  })
}
onMounted(() => {
  void action(load)
})
</script>
<template>
  <div class="captures">
    <el-card shadow="never">
      <template #header>公开网页采集</template>
      <p class="hint">
        支持 UTF-8 网页与纯文本，最多 1MiB / 15 秒。禁止内网、重定向和登录态；URL
        路径请使用百分号编码。新内容索引成功后才切换当前版本，失败保留旧资料。
      </p>
      <form class="capture-form" @submit.prevent="capture">
        <el-input
          v-model="url"
          aria-label="公开网页 URL"
          placeholder="https://example.com/article"
          maxlength="2048"
        /><el-button type="primary" native-type="submit" :loading="busy" :disabled="!url.trim()">采集网页</el-button>
      </form>
    </el-card>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-card shadow="never">
      <template #header
        ><div class="actions">
          <span>来源与版本 · {{ total }} 条</span><el-button :loading="busy" @click="action(load)">刷新状态</el-button>
        </div></template
      >
      <el-empty v-if="!items.length" description="还没有网页来源" />
      <p v-if="total > items.length">仅展示最近 {{ items.length }} 个来源。</p>
      <article v-for="row in items" :key="row.id" class="capture-row">
        <a :href="row.url" target="_blank" rel="noopener noreferrer">{{ row.url }}</a>
        <p>
          当前成功版本 {{ row.currentVersion }} · {{ names[row.status] ?? row.status }} · 最近检查
          {{ new Date(row.lastCheckedAt).toLocaleString() }}
        </p>
        <p v-if="row.lastError" role="alert">{{ row.lastError }}</p>
        <div class="actions">
          <el-button :disabled="busy || !!row.pendingJobId" @click="refresh(row)">检查更新</el-button
          ><el-button :disabled="busy" @click="versions(row)">版本历史</el-button>
        </div>
      </article>
    </el-card>
    <el-card v-if="selected" shadow="never">
      <template #header>正文历史 · {{ source }}</template>
      <p>保留 {{ historyTotal }} 个版本，展示最近 {{ history.length }} 个。失败版本不会成为当前资料。</p>
      <div class="actions">
        <el-button
          v-for="version in history"
          :key="version.version"
          :type="selected.version === version.version ? 'primary' : 'default'"
          @click="selected = version"
          >版本 {{ version.version }} · {{ names[version.status] ?? version.status }}</el-button
        >
      </div>
      <p>{{ selected.title }} · {{ selected.capturedAt }}</p>
      <p class="hash">SHA-256：{{ selected.hash }}</p>
      <pre>{{ selected.content }}</pre>
    </el-card>
  </div>
</template>
<style scoped>
.captures {
  display: grid;
  gap: var(--os-space-4);
  min-width: 0;
}
.hint {
  color: var(--el-text-color-secondary);
  line-height: 1.7;
  margin-top: 0;
}
.capture-form {
  display: flex;
  gap: var(--os-space-3);
  flex-wrap: wrap;
}
.capture-form .el-input {
  flex: 1;
  min-width: 200px;
}
.actions {
  display: flex;
  gap: var(--os-space-3);
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
}
.capture-row {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-block: var(--os-space-4);
  overflow-wrap: anywhere;
}
.hash {
  overflow-wrap: anywhere;
  font-size: var(--os-font-xs);
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 400px;
  overflow: auto;
  font: inherit;
  line-height: 1.7;
}
</style>
