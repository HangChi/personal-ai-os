<script setup lang="ts">
/**
 * 日程列表视图（从 CalendarView 拆出，2026-10-26）。
 *
 * <p>按天分组，每条显示时间段、标题、重复标记与地点。
 * 分页只在列表视图出现（月视图一次拉整月，没有页码概念）。
 */
import AppPagination from '@/components/AppPagination.vue'
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

    <!--
      分页统一走 AppPagination：文案用 totalLabel 传，量词是「次发生」而不是「条」
      —— 日程的分页单位是「重复发生的次数」，不是日程条数（一条每周的日程在这里
      会贡献几十次）。改造前这里手写了一遍分页容器，与另外三处同名不同样。
    -->
    <AppPagination
      :total="total"
      :page="page"
      :size="size"
      total-label="区间内共 {n} 次发生"
      @change="(next: number) => emit('page-change', next)"
    />
  </div>
</template>

<style scoped>
.cal-list {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-3);
}

.cal-list__day-label {
  margin-bottom: var(--os-space-1);
  font-size: var(--os-font-sm);
  font-weight: 600;
  color: var(--el-text-color-regular);
}

.cal-list__item {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  padding: var(--os-space-1) 0 var(--os-space-1) var(--os-space-3);
  border-left: 3px solid var(--el-color-primary-light-5);
}

.cal-list__item-time {
  flex: none;
  width: 130px;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
}

.cal-list__item-title {
  font-size: var(--os-font-md);
}

.cal-list__item-location {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

/* 操作按钮推到行尾，与时间、标题、标签拉开层次 */
.cal-list__item-action {
  margin-left: auto;
}

/*
 * 清除 EP 给相邻按钮的 12px 左边距：编辑与删除是同一组操作，
 * 间距由行内的 gap 统一控制（8px）。
 */
.cal-list__item-delete {
  margin-left: 0;
}
</style>
