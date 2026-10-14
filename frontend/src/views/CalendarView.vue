<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { storeToRefs } from 'pinia'
import { onMounted, onUnmounted, ref } from 'vue'

import { useCalendarStore } from '@/stores/calendar'
import type { RecurrenceType, Reminder } from '@/types/api'

const calendarStore = useCalendarStore()
const { dueReminders, loadingOccurrences, creating, error, groupedByDay } = storeToRefs(calendarStore)

const title = ref('')
const location = ref('')
const allDay = ref(false)
const range = ref<[Date, Date] | null>(null)
const recurrenceType = ref<RecurrenceType>('NONE')
const recurrenceUntil = ref<Date | null>(null)
const leadMinutes = ref(15)

const recurrenceOptions: Array<{ label: string; value: RecurrenceType }> = [
  { label: '不重复', value: 'NONE' },
  { label: '每天', value: 'DAILY' },
  { label: '每周', value: 'WEEKLY' },
  { label: '每月', value: 'MONTHLY' },
  { label: '工作日', value: 'WEEKDAYS' },
]

onMounted(() => {
  void calendarStore.loadOccurrences()
  // 轮询是提醒的「送达」方式：本地单机没有推送通道，靠页面定时拉取。
  // 轮询失败在 store 里被静默处理，不打扰用户。
  calendarStore.startReminderPolling()
})

onUnmounted(() => {
  calendarStore.stopReminderPolling()
})

async function handleCreate(): Promise<void> {
  const value = title.value.trim()
  if (!value) {
    ElMessage.warning('请填写日程标题')
    return
  }
  if (!range.value || range.value.length !== 2) {
    ElMessage.warning('请选择开始与结束时间')
    return
  }

  const ok = await calendarStore.add({
    title: value,
    location: location.value.trim() || undefined,
    allDay: allDay.value,
    startsAt: range.value[0].toISOString(),
    endsAt: range.value[1].toISOString(),
    recurrenceType: recurrenceType.value,
    recurrenceUntil: recurrenceUntil.value?.toISOString(),
    reminderLeadMinutes: leadMinutes.value,
  })

  if (ok) {
    ElMessage.success('已创建日程')
    title.value = ''
    location.value = ''
    range.value = null
    recurrenceUntil.value = null
    recurrenceType.value = 'NONE'
    allDay.value = false
  }
}

async function handleDelete(eventId: string, eventTitle: string): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除「${eventTitle}」会同时删除它的提醒。若只想删掉重复日程中的某一次，请改用助手确认流程。`,
      '删除日程',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const deletedReminders = await calendarStore.remove(eventId)
  if (deletedReminders !== null) {
    ElMessage.success(`已删除，同时清理提醒 ${deletedReminders} 条`)
  }
}

async function acknowledge(reminder: Reminder): Promise<void> {
  await calendarStore.acknowledge(reminder)
}

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? '—'
    : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
}

function formatRange(start: string, end: string, isAllDay: boolean): string {
  if (isAllDay) {
    return '全天'
  }
  return `${formatTime(start)} ~ ${formatTime(end)}`
}

function formatDayLabel(day: string): string {
  const date = new Date(`${day}T00:00:00`)
  if (Number.isNaN(date.getTime())) {
    return day
  }
  const weekday = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][date.getDay()]
  return `${date.toLocaleDateString('zh-CN')} ${weekday}`
}

function sourceLabel(sourceType: string): string {
  return sourceType === 'TASK' ? '任务' : '日程'
}
</script>

<template>
  <div class="calendar">
    <el-card v-if="dueReminders.length > 0" class="calendar__reminders" shadow="never">
      <template #header>
        <div class="calendar__header">
          <span>到点提醒（页面每 30 秒自动刷新）</span>
          <span class="calendar__stat">{{ dueReminders.length }} 条待处理</span>
        </div>
      </template>

      <div v-for="reminder in dueReminders" :key="reminder.id" class="calendar__reminder">
        <div class="calendar__reminder-body">
          <el-tag size="small" type="warning" effect="plain">{{ sourceLabel(reminder.sourceType) }}</el-tag>
          <span class="calendar__reminder-title">{{ reminder.title }}</span>
          <span class="calendar__reminder-meta">
            {{ formatTime(reminder.occurrenceAt) }} 开始 · 提醒时间 {{ formatTime(reminder.remindAt) }}
          </span>
        </div>
        <el-button size="small" type="primary" @click="acknowledge(reminder)">知道了</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>新建日程</template>

      <div class="calendar__create">
        <el-input v-model="title" class="calendar__field-title" maxlength="200" placeholder="日程标题，例如：周会" />
        <el-date-picker
          v-model="range"
          type="datetimerange"
          class="calendar__field-range"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
        />
        <el-input v-model="location" class="calendar__field-location" placeholder="地点（可选）" />
        <el-select v-model="recurrenceType" class="calendar__field-recurrence">
          <el-option
            v-for="option in recurrenceOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-date-picker
          v-if="recurrenceType !== 'NONE'"
          v-model="recurrenceUntil"
          type="date"
          class="calendar__field-until"
          placeholder="重复截止（可选）"
        />
        <el-checkbox v-model="allDay">全天</el-checkbox>
        <div class="calendar__field-lead">
          <span class="calendar__lead-label">提前</span>
          <el-input-number v-model="leadMinutes" :min="0" :max="10080" :step="5" size="small" />
          <span class="calendar__lead-label">分钟提醒</span>
        </div>
        <el-button type="primary" :loading="creating" @click="handleCreate">创建</el-button>
      </div>

      <p class="calendar__hint">
        重复日程在查询时展开为一次次发生，服务端只存规则；「全天」会归一到当天 00:00 至次日 00:00。
      </p>
    </el-card>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

    <el-card shadow="never">
      <template #header>
        <div class="calendar__header">
          <span>日程列表</span>
          <el-button size="small" :loading="loadingOccurrences" @click="calendarStore.loadOccurrences()">
            刷新
          </el-button>
        </div>
      </template>

      <el-empty
        v-if="groupedByDay.length === 0 && !loadingOccurrences"
        description="这段时间还没有日程"
        :image-size="70"
      />
      <div v-else v-loading="loadingOccurrences" class="calendar__days">
        <div v-for="group in groupedByDay" :key="group.day" class="calendar__day">
          <div class="calendar__day-label">{{ formatDayLabel(group.day) }}</div>
          <div v-for="(item, index) in group.items" :key="`${item.eventId}-${index}`" class="calendar__item">
            <span class="calendar__item-time">
              {{ formatRange(item.startsAt, item.endsAt, item.allDay) }}
            </span>
            <span class="calendar__item-title">{{ item.title }}</span>
            <el-tag v-if="item.recurring" size="small" type="info" effect="plain">
              {{ recurrenceOptions.find((option) => option.value === item.recurrenceType)?.label }}
            </el-tag>
            <span v-if="item.location" class="calendar__item-location">{{ item.location }}</span>
            <el-button
              class="calendar__item-action"
              link
              type="danger"
              size="small"
              @click="handleDelete(item.eventId, item.title)"
            >
              删除
            </el-button>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.calendar {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.calendar__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.calendar__stat {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.calendar__reminders {
  border-color: var(--el-color-warning-light-5);
}

.calendar__reminder {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.calendar__reminder:last-child {
  border-bottom: none;
}

.calendar__reminder-body {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.calendar__reminder-title {
  font-size: 14px;
  font-weight: 600;
}

.calendar__reminder-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.calendar__create {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.calendar__field-title {
  width: 220px;
}

.calendar__field-range {
  width: 360px;
}

.calendar__field-location {
  width: 160px;
}

.calendar__field-recurrence {
  width: 110px;
}

.calendar__field-until {
  width: 160px;
}

.calendar__field-lead {
  display: flex;
  align-items: center;
  gap: 6px;
}

.calendar__lead-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.calendar__hint {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.calendar__days {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.calendar__day-label {
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}

.calendar__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0 6px 12px;
  border-left: 3px solid var(--el-color-primary-light-5);
}

.calendar__item-time {
  flex: none;
  width: 130px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.calendar__item-title {
  font-size: 14px;
}

.calendar__item-location {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.calendar__item-action {
  margin-left: auto;
}
</style>
