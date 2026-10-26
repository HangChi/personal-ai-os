<script setup lang="ts">
/**
 * 日程月视图（从 CalendarView 拆出，2026-10-26）。
 *
 * <p>7 列 × 6 行固定网格：行数固定是刻意的 —— 不同月份高度一致，切月时下方内容不会被顶来顶去。
 *
 * <p>色点的降级说明（阶段 13）：原规格按「日程/任务」来源着色，但 occurrence DTO
 * 不带 sourceType，纯前端拿不到 → 降级为「重复 vs 一次性」。扫读月历时这个区分同样有用。
 */
import { isCrossDay, shortTime } from '@/lib/calendar-format'
import type { MonthCell } from '@/lib/calendar-dates'
import type { CalendarOccurrence } from '@/types/api'

defineProps<{
  cells: MonthCell[]
  /** 按本地日期分组的发生实例 */
  itemsByDay: Map<string, CalendarOccurrence[]>
  title: string
  /** 后端说还有多少条没取回（超出单次上限时） */
  overflow: number
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'prev'): void
  (e: 'next'): void
  (e: 'today'): void
  (e: 'create-on', date: Date): void
  (e: 'open-edit', item: CalendarOccurrence): void
}>()

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']
/** 每格最多显示几条，超出折叠成「还有 N 条」 */
const CELL_VISIBLE_LIMIT = 3

function itemsOf(map: Map<string, CalendarOccurrence[]>, key: string): CalendarOccurrence[] {
  return map.get(key) ?? []
}
</script>

<template>
  <div>
    <div class="cal-month__bar">
      <el-button size="small" @click="emit('prev')">上一月</el-button>
      <span class="cal-month__title">{{ title }}</span>
      <el-button size="small" @click="emit('today')">今天</el-button>
      <el-button size="small" @click="emit('next')">下一月</el-button>
      <span v-if="overflow > 0" class="cal-month__warn">另有 {{ overflow }} 条未显示</span>
    </div>

    <div v-loading="loading" class="cal-month__grid">
      <div v-for="weekday in WEEKDAYS" :key="weekday" class="cal-month__weekday">{{ weekday }}</div>

      <div
        v-for="cell in cells"
        :key="cell.key"
        class="cal-month__cell"
        :class="{ 'cal-month__cell--muted': !cell.inMonth, 'cal-month__cell--today': cell.isToday }"
      >
        <div class="cal-month__cell-head">
          <span class="cal-month__cell-day">{{ cell.day }}</span>
          <el-button class="cal-month__cell-add" link type="primary" size="small" @click="emit('create-on', cell.date)">
            +
          </el-button>
        </div>

        <div
          v-for="(item, index) in itemsOf(itemsByDay, cell.key).slice(0, CELL_VISIBLE_LIMIT)"
          :key="`${item.eventId}-${index}`"
          class="cal-month__cell-item"
          :title="item.title"
          @click="emit('open-edit', item)"
        >
          <span class="cal-month__cell-dot" :class="{ 'cal-month__cell-dot--recurring': item.recurring }" />
          <span class="cal-month__cell-time">{{ item.allDay ? '全天' : shortTime(item.startsAt) }}</span>
          <span class="cal-month__cell-title">{{ item.title }}</span>
          <span v-if="isCrossDay(item)" class="cal-month__cell-cross">跨天</span>
        </div>

        <el-popover
          v-if="itemsOf(itemsByDay, cell.key).length > CELL_VISIBLE_LIMIT"
          placement="bottom-start"
          :width="260"
          trigger="click"
        >
          <template #reference>
            <button type="button" class="cal-month__cell-more">
              还有 {{ itemsOf(itemsByDay, cell.key).length - CELL_VISIBLE_LIMIT }} 条
            </button>
          </template>
          <div
            v-for="(item, index) in itemsOf(itemsByDay, cell.key).slice(CELL_VISIBLE_LIMIT)"
            :key="`more-${item.eventId}-${index}`"
            class="cal-month__cell-more-item"
            @click="emit('open-edit', item)"
          >
            <span class="cal-month__cell-dot" :class="{ 'cal-month__cell-dot--recurring': item.recurring }" />
            <span class="cal-month__cell-time">{{ item.allDay ? '全天' : shortTime(item.startsAt) }}</span>
            <span>{{ item.title }}</span>
          </div>
        </el-popover>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ---- 月网格（阶段 13） ---- */

.cal-month__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.cal-month__title {
  min-width: 110px;
  text-align: center;
  font-weight: 600;
}

.cal-month__warn {
  margin-left: auto;
  color: var(--el-color-warning);
  font-size: 12px;
}

/*
 * 7 列 × 6 行。行数固定是刻意的：让不同月份的高度一致，切月时下方内容不会被顶来顶去。
 */
.cal-month__grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  border-top: 1px solid var(--el-border-color-lighter);
  border-left: 1px solid var(--el-border-color-lighter);
}

.cal-month__weekday {
  padding: 6px 8px;
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-right: 1px solid var(--el-border-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.cal-month__cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-height: 96px;
  padding: 4px 6px;
  border-right: 1px solid var(--el-border-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.cal-month__cell--muted {
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-placeholder);
}

.cal-month__cell--today {
  background: var(--el-color-primary-light-9);
}

.cal-month__cell-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.cal-month__cell-day {
  font-size: 12px;
  font-weight: 600;
}

/* 「+」只在悬停时出现：42 个格子都常驻一个加号会非常吵 */
.cal-month__cell-add {
  opacity: 0;
  transition: opacity 0.15s;
}

.cal-month__cell:hover .cal-month__cell-add {
  opacity: 1;
}

/*
 * 月视图色点（T032）：主色 = 一次性日程，warning = 重复日程。
 * 原规格按「日程/任务」来源着色，但 occurrence DTO 没有来源字段
 * （后端不提供 sourceType，纯前端拿不到），降级为重复/一次性区分。
 */
.cal-month__cell-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-primary);
}

.cal-month__cell-dot--recurring {
  background: var(--el-color-warning);
}

.cal-month__cell-item {
  display: flex;
  align-items: baseline;
  gap: 4px;
  padding: 1px 4px;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
  overflow: hidden;
}

.cal-month__cell-item:hover {
  background: var(--el-color-primary-light-8);
}

.cal-month__cell-time {
  flex: none;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.cal-month__cell-title {
  flex: 1;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.cal-month__cell-cross {
  flex: none;
  font-size: 10px;
  color: var(--el-color-warning);
}

.cal-month__cell-more {
  margin-top: 2px;
  padding: 0;
  border: none;
  background: none;
  color: var(--el-color-primary);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}

.cal-month__cell-more-item {
  display: flex;
  gap: 6px;
  padding: 4px 0;
  font-size: 12px;
  cursor: pointer;
}

.cal-month__cell-more-item:hover {
  color: var(--el-color-primary);
}
</style>
