<script setup lang="ts">
/**
 * 到点提醒卡片（从 CalendarView 拆出，2026-10-26）。
 *
 * <p>批量确认（T030）的要点：逐条点「知道了」是重复劳动；这里一次确认全部，
 * 但**只报告成功数** —— acknowledge 内部先乐观移除再请求，
 * 失败的下一轮轮询会带回来，此时声称「失败 N 条」反而误导。
 */
import { formatTime, sourceLabel } from '@/lib/calendar-format'
import type { Reminder } from '@/types/api'

defineProps<{
  reminders: Reminder[]
  acknowledgingAll: boolean
}>()

const emit = defineEmits<{
  (e: 'acknowledge', reminder: Reminder): void
  (e: 'acknowledge-all'): void
}>()
</script>

<template>
  <el-card class="cal-reminders" shadow="never">
    <template #header>
      <div class="cal-reminders__header">
        <span>到点提醒（页面每 30 秒自动刷新）</span>
        <div class="cal-reminders__actions">
          <span class="cal-reminders__stat">{{ reminders.length }} 条待处理</span>
          <el-button size="small" type="primary" :loading="acknowledgingAll" @click="emit('acknowledge-all')">
            全部知道了
          </el-button>
        </div>
      </div>
    </template>

    <div v-for="reminder in reminders" :key="reminder.id" class="cal-reminders__item">
      <div class="cal-reminders__body">
        <el-tag size="small" type="warning" effect="plain">{{ sourceLabel(reminder.sourceType) }}</el-tag>
        <span class="cal-reminders__title">{{ reminder.title }}</span>
        <span class="cal-reminders__meta">
          {{ formatTime(reminder.occurrenceAt) }} 开始 · 提醒时间 {{ formatTime(reminder.remindAt) }}
        </span>
      </div>
      <el-button size="small" type="primary" @click="emit('acknowledge', reminder)">知道了</el-button>
    </div>
  </el-card>
</template>

<style scoped>
.cal-reminders {
  border-color: var(--el-color-warning-light-5);
}

.cal-reminders__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.cal-reminders__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.cal-reminders__stat {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.cal-reminders__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.cal-reminders__item:last-child {
  border-bottom: none;
}

.cal-reminders__body {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.cal-reminders__title {
  font-size: 14px;
  font-weight: 600;
}

.cal-reminders__meta {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}
</style>
