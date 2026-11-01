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
  <div class="cal-month">
    <div class="cal-month__bar">
      <el-button size="small" @click="emit('prev')">上一月</el-button>
      <span class="cal-month__title">{{ title }}</span>
      <el-button size="small" @click="emit('today')">今天</el-button>
      <el-button size="small" @click="emit('next')">下一月</el-button>
      <span v-if="overflow > 0" class="cal-month__warn">另有 {{ overflow }} 条未显示</span>
    </div>

    <div class="cal-month__scroll" tabindex="0" aria-label="月历，可左右滚动">
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
            <el-button
              class="cal-month__cell-add"
              link
              type="primary"
              size="small"
              :aria-label="`在 ${cell.key} 创建日程`"
              @click="emit('create-on', cell.date)"
            >
              +
            </el-button>
          </div>

          <!-- 日程条目同样是可点击的，必须键盘可达：月视图里用户主要的操作就是点开一条改它 -->
          <div
            v-for="(item, index) in itemsOf(itemsByDay, cell.key).slice(0, CELL_VISIBLE_LIMIT)"
            :key="`${item.eventId}-${index}`"
            class="cal-month__cell-item"
            :title="item.title"
            tabindex="0"
            role="button"
            @click="emit('open-edit', item)"
            @keydown.enter.prevent="emit('open-edit', item)"
            @keydown.space.prevent="emit('open-edit', item)"
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
            <button
              v-for="(item, index) in itemsOf(itemsByDay, cell.key).slice(CELL_VISIBLE_LIMIT)"
              :key="`more-${item.eventId}-${index}`"
              type="button"
              class="cal-month__cell-more-item"
              @click="emit('open-edit', item)"
            >
              <span class="cal-month__cell-dot" :class="{ 'cal-month__cell-dot--recurring': item.recurring }" />
              <span class="cal-month__cell-time">{{ item.allDay ? '全天' : shortTime(item.startsAt) }}</span>
              <span class="cal-month__more-title">{{ item.title }}</span>
            </button>
          </el-popover>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ---- 月网格（阶段 13） ---- */

.cal-month__bar {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  flex-wrap: wrap;
  margin-bottom: var(--os-space-3);
}

.cal-month__title {
  flex: none;
  width: 108px;
  font-variant-numeric: tabular-nums;
  font-size: var(--os-font-sm);
  text-align: center;
  font-weight: 600;
}

.cal-month__bar > .el-button.el-button--small {
  min-height: 28px;
  height: 28px;
  padding-inline: var(--os-space-3);
}

.cal-month__warn {
  margin-left: auto;
  color: var(--el-color-warning);
  font-size: var(--os-font-xs);
}

/*
 * 7 列 × 6 行。行数固定是刻意的：让不同月份的高度一致，切月时下方内容不会被顶来顶去。
 */
.cal-month__scroll {
  overflow-x: auto;
  overscroll-behavior-x: contain;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-md);
}

.cal-month__grid {
  --cal-cell-height: 224px;
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  grid-template-rows: 32px repeat(6, var(--cal-cell-height));
  min-width: 672px;
}

.cal-month__grid > :nth-child(7n) {
  border-right: 0;
}

.cal-month__cell:nth-last-child(-n + 7) {
  border-bottom: 0;
}

.cal-month__weekday {
  padding: var(--os-space-2);
  text-align: center;
  font-size: var(--os-font-xs);
  font-weight: 500;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-right: 1px solid var(--el-border-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.cal-month__cell {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-1);
  min-width: 0;
  overflow: hidden;
  padding: var(--os-space-1) var(--os-space-2);
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
  font-size: var(--os-font-xs);
  font-weight: 600;
}

/*
 * 「+」只在悬停时出现：42 个格子都常驻一个加号会非常吵。
 *
 * 但**触屏设备根本没有 hover**：iPad / 手机上这个按钮会永远是 opacity:0，
 * 也就是月视图里「点某天新建日程」这个入口完全消失 —— 用户只能看到没处点的格子。
 * 那不是「吵不吵」的取舍，是功能不可用。
 *
 * 所以两段：默认按悬停显隐；在不支持 hover 的设备上常驻显示（并放大到可点面积，
 * 手指不像鼠标那样能精确点中 12px 的加号）。用 `(hover: none)` 而不是按屏宽判断：
 * 带触摸屏的笔记本同时有 hover，按宽度判断会把它们错划进来。
 */
.cal-month__cell-add {
  opacity: 0;
  transition: opacity var(--os-duration-fast) var(--os-ease);
}

.cal-month__cell:hover .cal-month__cell-add {
  opacity: 1;
}

.cal-month__cell:focus-within .cal-month__cell-add {
  opacity: 1;
}

/* 触屏：没有 hover，必须常驻；同时撑大点击热区到可手指点中的尺寸 */
@media (hover: none) {
  .cal-month__cell-add {
    opacity: 1;
    min-width: 28px;
    min-height: 28px;
    /* EP 的小按钮给了固定 height/line-height，只写 min-height 会被压回 24px */
    height: 28px;
    line-height: 28px;
    padding: 0;
  }
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
  display: grid;
  grid-template-columns: 6px minmax(0, 1fr) auto;
  align-items: center;
  column-gap: 4px;
  flex: none;
  min-width: 0;
  padding: 2px 4px;
  min-height: 24px;
  line-height: 16px;
  background: var(--el-color-primary-light-9);
  border-radius: var(--os-radius-sm);
  font-size: var(--os-font-xs);
  cursor: pointer;
  overflow: hidden;
}

.cal-month__cell-item:hover {
  background: var(--el-color-primary-light-8);
}

/* 键盘焦点环：条目可 Tab 聚焦，但不能只靠浏览器默认样式（在密集网格里几乎看不见） */
.cal-month__cell-item:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -1px;
  background: var(--el-color-primary-light-8);
}

.cal-month__cell-time {
  flex: none;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.cal-month__cell-title {
  grid-column: 2 / -1;
  grid-row: 2;
  min-width: 0;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  overflow-wrap: anywhere;
}

.cal-month__cell-cross {
  grid-column: 3;
  grid-row: 1;
  flex: none;
  font-size: var(--os-font-xs);
  color: var(--el-color-warning);
}

.cal-month__cell-more {
  flex: none;
  margin-top: 2px;
  padding: 0;
  border: none;
  background: none;
  color: var(--el-color-primary);
  font-size: var(--os-font-xs);
  text-align: left;
  cursor: pointer;
}

.cal-month__cell-more-item {
  width: 100%;
  border: 0;
  background: none;
  color: var(--el-text-color-regular);
  text-align: left;
  display: flex;
  align-items: baseline;
  gap: var(--os-space-2);
  padding: var(--os-space-1) 0;
  font-size: var(--os-font-xs);
  cursor: pointer;
}

.cal-month__more-title {
  min-width: 0;
  overflow-wrap: anywhere;
}

@media (pointer: coarse) {
  .cal-month__grid {
    --cal-cell-height: 240px;
  }

  .cal-month__bar > .el-button.el-button--small,
  .cal-month__cell-more,
  .cal-month__cell-more-item {
    min-height: 32px;
  }
}

.cal-month__cell-more-item:hover {
  color: var(--el-color-primary);
}
</style>
