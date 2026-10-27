<script setup lang="ts">
/**
 * 新建日程表单（从 CalendarView 拆出，2026-10-26）。
 *
 * <p>三组纵排（基本信息 / 时间 / 重复与提醒），组内横排（T031）。
 *
 * <p>{@code fillSlot} 供月视图「点格子加号」调用：预填标题为空 + 该日 9:00 起一小时，
 * 省掉用户再选一次日期。表单自己不知道「哪一天被点了」，由父层决定。
 */
import { ElMessage } from 'element-plus'
import { ref } from 'vue'

import { RECURRENCE_OPTIONS } from '@/lib/calendar-format'
import type { RecurrenceType } from '@/types/api'

const props = defineProps<{
  /** 默认提前提醒分钟数（取设置页偏好） */
  defaultLead: number
  creating: boolean
}>()

const emit = defineEmits<{
  (
    e: 'create',
    payload: {
      title: string
      location: string | undefined
      allDay: boolean
      startsAt: string
      endsAt: string
      recurrenceType: RecurrenceType
      recurrenceUntil: string | undefined
      reminderLeadMinutes: number
    },
  ): void
}>()

const title = ref('')
const location = ref('')
const allDay = ref(false)
const range = ref<[Date, Date] | null>(null)
const recurrenceType = ref<RecurrenceType>('NONE')
const recurrenceUntil = ref<Date | null>(null)
const leadMinutes = ref(props.defaultLead)

/** 月视图点格子：把该时间段填进表单 */
function fillSlot(start: Date, end: Date): void {
  title.value = ''
  location.value = ''
  allDay.value = false
  range.value = [start, end]
  ElMessage.info(`已填入新建表单：${start.toLocaleString('zh-CN', { hour12: false })}`)
}

function submit(): void {
  const value = title.value.trim()
  if (!value) {
    ElMessage.warning('请填写日程标题')
    return
  }
  if (!range.value || range.value.length !== 2) {
    ElMessage.warning('请选择开始与结束时间')
    return
  }
  emit('create', {
    title: value,
    location: location.value.trim() || undefined,
    allDay: allDay.value,
    startsAt: range.value[0].toISOString(),
    endsAt: range.value[1].toISOString(),
    recurrenceType: recurrenceType.value,
    recurrenceUntil: recurrenceUntil.value?.toISOString(),
    reminderLeadMinutes: leadMinutes.value,
  })
}

/** 创建成功后由父组件调用：表单不知道成败，避免失败时清空用户输入 */
function reset(): void {
  title.value = ''
  location.value = ''
  range.value = null
  recurrenceUntil.value = null
  recurrenceType.value = 'NONE'
  allDay.value = false
}

defineExpose({ fillSlot, reset })
</script>

<template>
  <el-card shadow="never">
    <template #header>新建日程</template>

    <!-- 表单分组（T031）：基本信息 / 时间 / 重复与提醒，替代原来一排混放的控件 -->
    <div class="cal-create">
      <section class="cal-create__group">
        <span class="cal-create__group-label">基本信息</span>
        <div class="cal-create__row">
          <el-input v-model="title" class="cal-create__title" maxlength="200" placeholder="日程标题，例如：周会" />
          <el-input v-model="location" class="cal-create__location" placeholder="地点（可选）" />
        </div>
      </section>

      <section class="cal-create__group">
        <span class="cal-create__group-label">时间</span>
        <div class="cal-create__row">
          <el-date-picker
            v-model="range"
            type="datetimerange"
            class="cal-create__range"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
          />
          <el-checkbox v-model="allDay">全天</el-checkbox>
        </div>
      </section>

      <section class="cal-create__group">
        <span class="cal-create__group-label">重复与提醒</span>
        <div class="cal-create__row">
          <el-select v-model="recurrenceType" class="cal-create__recurrence">
            <el-option
              v-for="option in RECURRENCE_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-date-picker
            v-if="recurrenceType !== 'NONE'"
            v-model="recurrenceUntil"
            type="date"
            class="cal-create__until"
            placeholder="重复截止（可选）"
          />
          <div class="cal-create__lead">
            <span class="cal-create__lead-label">提前</span>
            <el-input-number v-model="leadMinutes" :min="0" :max="10080" :step="5" size="small" />
            <span class="cal-create__lead-label">分钟提醒</span>
          </div>
        </div>
      </section>

      <div class="cal-create__actions">
        <el-button type="primary" :loading="creating" @click="submit">创建</el-button>
      </div>
    </div>

    <p class="cal-create__hint">
      重复日程在查询时展开为一次次发生，服务端只存规则；「全天」会归一到当天 00:00 至次日 00:00。
    </p>
  </el-card>
</template>

<style scoped>
/* 新建表单：三组纵排，组内横排（T031） */
.cal-create {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.cal-create__group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.cal-create__group-label {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  font-weight: 600;
}

.cal-create__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.cal-create__actions {
  display: flex;
  justify-content: flex-end;
}

.cal-create__title {
  width: 220px;
}

.cal-create__range {
  width: 360px;
}

.cal-create__location {
  width: 160px;
}

.cal-create__recurrence {
  width: 110px;
}

.cal-create__until {
  width: 160px;
}

.cal-create__lead {
  display: flex;
  align-items: center;
  gap: 6px;
}

.cal-create__lead-label {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.cal-create__hint {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}
</style>
