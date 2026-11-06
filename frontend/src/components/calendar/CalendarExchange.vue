<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { calendarConflicts, importIcs, previewIcs, type IcsPreview, type CalendarConflicts } from '@/api/calendar'
import { http } from '@/api/http'
import { triggerBlobDownload } from '@/api/export'
import { localDayKey } from '@/lib/calendar-dates'

const emit = defineEmits<{ imported: [] }>()
const content = ref('')
const preview = ref<IcsPreview | null>(null)
const busy = ref(false)
const error = ref('')
const conflicts = ref<CalendarConflicts | null>(null)
const from = ref(localDayKey(new Date().toISOString()))
const dateText = (s: string): string => new Date(s).toLocaleString()
async function perform(action: () => Promise<void>): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    await action()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败，请重试'
  } finally {
    busy.value = false
  }
}
async function choose(event: Event): Promise<void> {
  preview.value = null
  content.value = ''
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  await perform(async () => {
    if (file.size > 1048576) throw new Error('ICS 文件最多 1MiB')
    content.value = await file.text()
    preview.value = await previewIcs(content.value)
  })
}
async function confirm(): Promise<void> {
  if (!preview.value) return
  await perform(async () => {
    const result = await importIcs(content.value, preview.value!.digest)
    ElMessage.success(`已导入 ${result.created} 条，跳过已存在 ${result.skipped} 条`)
    preview.value = null
    content.value = ''
    emit('imported')
  })
}
async function download(): Promise<void> {
  await perform(async () => {
    const response = await http.get('/calendar/events/ics', { responseType: 'blob' })
    triggerBlobDownload(response.data as Blob, 'personal-ai-os.ics')
  })
}
async function inspect(): Promise<void> {
  await perform(async () => {
    const start = new Date(`${from.value}T00:00:00`)
    const end = new Date(start)
    end.setDate(end.getDate() + 7)
    conflicts.value = await calendarConflicts(start.toISOString(), end.toISOString())
  })
}
</script>
<template>
  <el-card shadow="never" class="exchange os-form-card--embedded">
    <h2 class="exchange__heading">导入与导出</h2>
    <p class="hint">
      先预览再确认，按 UID 跳过已有日程。支持每日、每周、工作日及 1–28
      日每月重复；复杂规则、提醒组件和未来夏令时重复会明确拒绝。最多 1MiB / 200 条。
    </p>
    <div class="actions">
      <label class="file-label"
        >选择 ICS 文件<input
          aria-label="导入 ICS 文件"
          type="file"
          accept=".ics,text/calendar"
          :disabled="busy"
          @change="choose"
      /></label>
      <el-button :disabled="busy" @click="download">导出 ICS</el-button>
    </div>
    <div class="exchange__divider">
      <h2 class="exchange__heading">检查时间冲突</h2>
      <p class="hint">选择起始日期，检查未来 7 天的重叠安排。</p>
    </div>
    <div class="actions">
      <label>起始日期 <input v-model="from" aria-label="冲突起始日" type="date" /></label>
      <el-button :loading="busy" @click="inspect">检查未来 7 天冲突</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="preview" class="preview">
      <p>
        预览 {{ preview.events.length }} 条，其中
        {{ preview.duplicateCount }} 条已存在。确认后将原子导入，其余内容不会覆盖已有编辑。
      </p>
      <ul>
        <li v-for="entry in preview.events" :key="entry.uid">
          {{ entry.event.title }} · {{ dateText(entry.event.startsAt) }}
        </li>
      </ul>
      <el-button type="primary" :loading="busy" @click="confirm">确认导入</el-button>
    </div>
    <div v-if="conflicts" aria-live="polite">
      <p>
        共 {{ conflicts.total }} 组冲突{{
          conflicts.truncated ? '（仅展示前 100 组，请缩小窗口）' : ''
        }}。首尾相接的日程不算冲突。
      </p>
      <ul>
        <li v-for="(pair, index) in conflicts.items" :key="index">
          {{ pair.first.title }}（{{ dateText(pair.first.startsAt) }}）与 {{ pair.second.title }}（{{
            dateText(pair.second.startsAt)
          }}）
        </li>
      </ul>
    </div>
  </el-card>
</template>
<style scoped>
.exchange {
  min-width: 0;
}
.hint {
  color: var(--el-text-color-secondary);
  margin-top: 0;
  line-height: 1.7;
}
.actions {
  display: flex;
  gap: var(--os-space-3);
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: var(--os-space-3);
}
.actions label {
  display: flex;
  gap: var(--os-space-2);
  align-items: center;
  flex-wrap: wrap;
}
input {
  max-width: 100%;
  font: inherit;
}
.preview ul {
  max-height: 240px;
  overflow: auto;
}
li {
  overflow-wrap: anywhere;
  margin-block: var(--os-space-2);
}
.exchange__heading {
  margin: 0 0 var(--os-space-3);
  font-size: var(--os-font-lg);
  font-weight: 600;
}
.exchange__divider {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: var(--os-space-5);
  margin-top: var(--os-space-5);
}
.actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.actions input[type='date'] {
  padding: 7px 10px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--os-radius-sm);
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-blank);
}
</style>
