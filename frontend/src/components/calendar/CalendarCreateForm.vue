<script setup lang="ts">
/**
 * 新建日程表单（从 CalendarView 拆出，2026-10-26）。
 *
 * <p>基本信息与时间设置按两列对齐，提醒与提交停靠在同一行。
 *
 * <p>{@code fillSlot} 供月视图「点格子加号」调用：预填标题为空 + 该日 9:00 起一小时，
 * 省掉用户再选一次日期。表单自己不知道「哪一天被点了」，由父层决定。
 */
import { Calendar, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { ref, useId } from 'vue'

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
const formId = useId()

/** 月视图点格子：把该时间段填进表单 */
function fillSlot(start: Date, end: Date): void {
  title.value = ''
  location.value = ''
  allDay.value = false
  range.value = [start, end]
  ElMessage.info(`已填入新建表单：${start.toLocaleString('zh-CN', { hour12: false })}`)
}

function submit(): void {
  if (props.creating) {
    return
  }
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
  <el-card class="cal-create-panel" shadow="never">
    <template #header>
      <div class="cal-create-panel__heading">
        <el-icon aria-hidden="true"><Calendar /></el-icon>
        <h2>新建日程</h2>
      </div>
    </template>

    <el-form class="cal-create" :disabled="creating" @submit.prevent="submit">
      <div class="cal-create__field">
        <label class="cal-create__label" :for="`${formId}-title`">日程标题</label>
        <el-input
          :id="`${formId}-title`"
          v-model="title"
          class="cal-create__title"
          maxlength="200"
          placeholder="例如：每周例会"
        />
      </div>

      <div class="cal-create__field">
        <label class="cal-create__label" :for="`${formId}-location`">
          地点<span class="cal-create__optional">可选</span>
        </label>
        <el-input :id="`${formId}-location`" v-model="location" maxlength="200" placeholder="会议室或线上会议链接" />
      </div>

      <div class="cal-create__field">
        <div class="cal-create__field-head">
          <label class="cal-create__label" :for="`${formId}-start`">起止时间</label>
          <div class="cal-create__all-day">
            <span>全天</span>
            <el-switch v-model="allDay" aria-label="全天日程" size="small" />
          </div>
        </div>
        <label class="sr-only" :for="`${formId}-end`">结束时间</label>
        <el-date-picker
          :id="[`${formId}-start`, `${formId}-end`]"
          v-model="range"
          type="datetimerange"
          class="cal-create__range"
          format="YYYY-MM-DD HH:mm"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
        />
      </div>

      <div class="cal-create__field">
        <label class="cal-create__label" :for="`${formId}-recurrence`">重复</label>
        <el-select :id="`${formId}-recurrence`" v-model="recurrenceType" class="cal-create__recurrence">
          <el-option
            v-for="option in RECURRENCE_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <div v-if="recurrenceType !== 'NONE'" class="cal-create__repeat-until">
          <label class="cal-create__label" :for="`${formId}-until`">
            重复截止<span class="cal-create__optional">可选</span>
          </label>
          <el-date-picker
            :id="`${formId}-until`"
            v-model="recurrenceUntil"
            type="date"
            class="cal-create__until"
            placeholder="不设截止日期"
          />
        </div>
      </div>

      <div class="cal-create__footer">
        <div class="cal-create__lead">
          <label class="cal-create__label" :for="`${formId}-lead`">提前提醒</label>
          <el-input-number :id="`${formId}-lead`" v-model="leadMinutes" :min="0" :max="10080" :step="5" />
          <span class="cal-create__unit">分钟</span>
        </div>
        <el-button type="primary" native-type="submit" :icon="Plus" :loading="creating" :disabled="creating">
          创建日程
        </el-button>
      </div>
    </el-form>
  </el-card>
</template>

<style scoped>
.cal-create-panel__heading {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  color: var(--el-text-color-regular);
}

.cal-create-panel__heading h2 {
  margin: 0;
  font-size: var(--os-font-md);
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.cal-create {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  gap: var(--os-space-4) var(--os-space-5);
}

.cal-create__field,
.cal-create__repeat-until {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
  min-width: 0;
}

.cal-create__label {
  color: var(--el-text-color-regular);
  font-size: var(--os-font-sm);
  line-height: var(--os-space-6);
  font-weight: 500;
}

.cal-create__field-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: var(--os-space-6);
}

.cal-create__all-day {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.cal-create__optional {
  margin-left: var(--os-space-2);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  font-weight: 400;
}

.cal-create__repeat-until {
  margin-top: var(--os-space-1);
}

/* 日期控件的默认宽度与 flex-basis 都需要解除，才能对齐网格。 */
.cal-create :deep(.el-date-editor),
.cal-create :deep(.el-select) {
  width: 100%;
  min-width: 0;
  flex: none;
}

.cal-create :deep(.el-range-input) {
  min-width: 0;
}

.cal-create__footer {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-4);
  padding-top: var(--os-space-4);
  border-top: 1px solid var(--el-border-color-lighter);
}

.cal-create__lead {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
}

.cal-create__lead :deep(.el-input-number) {
  width: 112px;
}

.cal-create__unit {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

@media (max-width: 600px) {
  .cal-create {
    grid-template-columns: minmax(0, 1fr);
  }

  .cal-create__footer {
    flex-wrap: wrap;
    gap: var(--os-space-3);
  }

  .cal-create__footer > .el-button {
    width: 100%;
  }
}
</style>
