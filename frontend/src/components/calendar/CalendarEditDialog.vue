<script setup lang="ts">
/**
 * 日程编辑弹窗（从 CalendarView 拆出，2026-10-26）。
 *
 * <p>编辑基线是**原始定义**而不是发生实例：实例只带单次时间，用它提交会抹掉
 * 重复规则与描述。因此父组件传进来的必须是 `loadEvent` 的结果。
 *
 * <p>提交时「重复截止」需要区分「未提供」与「显式置空」—— 与任务页的
 * clearDueAt 同一个套路，后端用独立字段表达清除意图。
 */
import { ref, watch } from 'vue'

import { RECURRENCE_OPTIONS } from '@/lib/calendar-format'
import type { UpdateEventPayload } from '@/api/calendar'
import type { CalendarEvent, RecurrenceType } from '@/types/api'

const props = defineProps<{
  visible: boolean
  /** 原始定义（不是发生实例） */
  event: CalendarEvent | null
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'submit', payload: UpdateEventPayload): void
}>()

const form = ref<{
  title: string
  location: string
  description: string
  allDay: boolean
  range: [Date, Date] | null
  recurrenceType: RecurrenceType
  recurrenceUntil: Date | null
  reminderLeadMinutes: number
}>({
  title: '',
  location: '',
  description: '',
  allDay: false,
  range: null,
  recurrenceType: 'NONE',
  recurrenceUntil: null,
  reminderLeadMinutes: 15,
})

watch(
  () => props.event,
  (event) => {
    if (!event) {
      return
    }
    form.value = {
      title: event.title,
      location: event.location ?? '',
      description: event.description ?? '',
      allDay: event.allDay,
      range: [new Date(event.startsAt), new Date(event.endsAt)],
      recurrenceType: event.recurrenceType,
      recurrenceUntil: event.recurrenceUntil ? new Date(event.recurrenceUntil) : null,
      reminderLeadMinutes: event.reminderLeadMinutes,
    }
  },
  { immediate: true },
)

function submit(): void {
  const original = props.event
  if (!original) {
    return
  }
  const payload: UpdateEventPayload = {
    title: form.value.title.trim(),
    // 地点与描述始终提交（空串表示清空），否则用户永远清不掉已有内容
    location: form.value.location.trim(),
    description: form.value.description.trim(),
    allDay: form.value.allDay,
    // 起止**成对**提供：后端要求如此，单独改一个会让「结束早于开始」有可乘之机
    startsAt: form.value.range?.[0].toISOString() ?? original.startsAt,
    endsAt: form.value.range?.[1].toISOString() ?? original.endsAt,
    recurrenceType: form.value.recurrenceType,
    reminderLeadMinutes: form.value.reminderLeadMinutes,
  }
  // 重复截止：有值则设置；原本有值现在被清空则显式请求清除
  if (form.value.recurrenceUntil) {
    payload.recurrenceUntil = form.value.recurrenceUntil.toISOString()
  } else if (original.recurrenceUntil) {
    payload.clearRecurrenceUntil = true
  }
  emit('submit', payload)
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="编辑日程"
    width="560px"
    @update:model-value="(value: boolean) => emit('update:visible', value)"
  >
    <p class="cal-edit__note">
      编辑作用于**整条日程**（含全部重复发生）：改时间后每一次发生都会用新时间。 目前不支持「只改/只删某一次发生」。
    </p>
    <el-form label-width="90px">
      <el-form-item label="标题" required>
        <el-input v-model="form.title" maxlength="200" placeholder="日程标题" />
      </el-form-item>
      <el-form-item label="时间" required>
        <el-date-picker
          v-model="form.range"
          type="datetimerange"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
        />
      </el-form-item>
      <el-form-item label="全天">
        <el-checkbox v-model="form.allDay">全天日程（起止会归一到当天 00:00 至次日 00:00）</el-checkbox>
      </el-form-item>
      <el-form-item label="重复">
        <div class="cal-edit__row">
          <el-select v-model="form.recurrenceType" style="width: 130px">
            <el-option
              v-for="option in RECURRENCE_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-date-picker
            v-if="form.recurrenceType !== 'NONE'"
            v-model="form.recurrenceUntil"
            type="date"
            placeholder="重复截止（留空即无限）"
          />
        </div>
      </el-form-item>
      <el-form-item label="地点">
        <el-input v-model="form.location" maxlength="200" placeholder="留空即清空" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="3" maxlength="2000" placeholder="留空即清空" />
      </el-form-item>
      <el-form-item label="提前提醒">
        <div class="cal-edit__row">
          <el-input-number v-model="form.reminderLeadMinutes" :min="0" :max="10080" :step="5" />
          <span class="cal-edit__label">分钟</span>
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.cal-edit__row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.cal-edit__note {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.cal-edit__label {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}
</style>
