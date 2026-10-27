<script setup lang="ts">
/**
 * 日程列表视图（从 CalendarView 拆出，2026-10-26）。
 *
 * <p>按天分组，每条显示时间段、标题、重复标记与地点。
 * 分页只在列表视图出现（月视图一次拉整月，没有页码概念）。
 */
import { RECURRENCE_OPTIONS, formatDayLabel, formatRange } from '@/lib/calendar-format'
import type { CalendarOccurrence } from '@/types/api'

defineProps<{
  groups: Array<{ day: string; items: CalendarOccurrence[] }>
  total: number
  page: number
  size: number
  loading: boolean
  /** 正在拉取原始定义的那条（编辑按钮转圈，防止重复点） */
  editLoadingId: string | null
}>()

const emit = defineEmits<{
  (e: 'edit', item: CalendarOccurrence): void
  (e: 'remove', eventId: string, title: string): void
  (e: 'page-change', page: number): void
}>()

/** 重复标记的中文名；找不到时返回空串（不显示徽标） */
function recurrenceLabel(type: string): string {
  return RECURRENCE_OPTIONS.find((option) => option.value === type)?.label ?? ''
}
</script>

<template>
  <el-empty v-if="groups.length === 0 && !loading" description="这段时间还没有日程" :image-size="70" />
  <div v-else v-loading="loading" class="cal-list">
    <div v-for="group in groups" :key="group.day" class="cal-list__day">
      <div class="cal-list__day-label">{{ formatDayLabel(group.day) }}</div>
      <div v-for="(item, index) in group.items" :key="`${item.eventId}-${index}`" class="cal-list__item">
        <span class="cal-list__item-time">{{ formatRange(item.startsAt, item.endsAt, item.allDay) }}</span>
        <span class="cal-list__item-title">{{ item.title }}</span>
        <el-tag v-if="item.recurring" size="small" type="info" effect="plain">
          {{ recurrenceLabel(item.recurrenceType) }}
        </el-tag>
        <span v-if="item.location" class="cal-list__item-location">{{ item.location }}</span>
        <el-button
          class="cal-list__item-action"
          link
          type="primary"
          size="small"
          :loading="editLoadingId === item.eventId"
          @click="emit('edit', item)"
        >
          编辑
        </el-button>
        <el-button
          class="cal-list__item-delete"
          link
          type="danger"
          size="small"
          @click="emit('remove', item.eventId, item.title)"
        >
          删除
        </el-button>
      </div>
    </div>

    <div v-if="total > 0" class="cal-list__pagination">
      <span class="cal-list__total">区间内共 {{ total }} 次发生</span>
      <el-pagination
        layout="prev, pager, next"
        :total="total"
        :current-page="page + 1"
        :page-size="size"
        background
        @current-change="(next: number) => emit('page-change', next - 1)"
      />
    </div>
  </div>
</template>

<style scoped>
.cal-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.cal-list__pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  margin-top: 14px;
}

.cal-list__total {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
}

.cal-list__day-label {
  margin-bottom: 6px;
  font-size: var(--os-font-sm);
  font-weight: 600;
  color: var(--el-text-color-regular);
}

.cal-list__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0 6px 12px;
  border-left: 3px solid var(--el-color-primary-light-5);
}

.cal-list__item-time {
  flex: none;
  width: 130px;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
}

.cal-list__item-title {
  font-size: 14px;
}

.cal-list__item-location {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.cal-list__item-action {
  margin-left: auto;
}

.cal-list__item-delete {
  margin-left: 0;
}
</style>
