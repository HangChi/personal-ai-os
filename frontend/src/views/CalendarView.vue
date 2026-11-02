<script setup lang="ts">
/**
 * 日程页（阶段 18 改造；2026-10-26 按区块拆分为子组件）。
 *
 * <p>本文件保留编排：通知状态条与轮询、视图切换与月份游标、以及「点格子 → 填表单 →
 * 滚动过去」这条跨组件链路。具体的通知条、到点提醒、新建表单、月网格、列表、编辑弹窗
 * 各自成组件（`components/calendar/`）。
 */
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import { computed, nextTick, onMounted, ref } from 'vue'

import CalendarCreateForm from '@/components/calendar/CalendarCreateForm.vue'
import CalendarDayList from '@/components/calendar/CalendarDayList.vue'
import CalendarDueReminders from '@/components/calendar/CalendarDueReminders.vue'
import CalendarEditDialog from '@/components/calendar/CalendarEditDialog.vue'
import CalendarMonthGrid from '@/components/calendar/CalendarMonthGrid.vue'
import CalendarNotifyBanner from '@/components/calendar/CalendarNotifyBanner.vue'
import PageHeader from '@/components/PageHeader.vue'
import { buildMonthCells, groupByLocalDay, monthRange, shiftMonth } from '@/lib/calendar-dates'
import { confirmDanger } from '@/lib/confirm-box'
import { useCalendarStore } from '@/stores/calendar'
import { useSettingsStore } from '@/stores/settings'
import type { CreateEventPayload, UpdateEventPayload } from '@/api/calendar'
import type { CalendarEvent, CalendarOccurrence, Reminder } from '@/types/api'

const calendarStore = useCalendarStore()
const settingsStore = useSettingsStore()
const {
  dueReminders,
  notificationPermission,
  requestingPermission,
  loadingOccurrences,
  creating,
  error,
  groupedByDay,
  occurrences,
  total,
  page,
  size,
} = storeToRefs(calendarStore)

onMounted(() => {
  void calendarStore.loadOccurrences()
})

async function enableNotifications(): Promise<void> {
  await calendarStore.enableNotifications()
  if (notificationPermission.value === 'granted') {
    ElMessage.success('通知已开启')
  } else if (notificationPermission.value === 'denied') {
    ElMessage.warning('浏览器拒绝了通知权限，可在站点设置里手动允许')
  }
}

// ------------------------------------------------------------------ 视图切换与月网格

type CalendarViewMode = 'list' | 'month'

/**
 * 月视图一次拉整月。
 *
 * 后端 `max-query-limit` 是 500，传更多也会被收敛 —— 超过这个量说明一个月有 500+ 次发生，
 * 此时页面必须明说「另有 N 条未显示」，而不是让人以为看到的就是全部。
 */
const MONTH_PAGE_SIZE = 500
/** 列表视图每页条数（与 store 默认值一致） */
const LIST_PAGE_SIZE = 100

function startOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1)
}

const viewMode = ref<CalendarViewMode>('list')
const monthCursor = ref(startOfMonth(new Date()))

const monthCells = computed(() => buildMonthCells(monthCursor.value))
/** 与列表共用同一批 occurrences，只是换一种分组方式 */
const monthGroups = computed(() => groupByLocalDay(occurrences.value))
const monthTitle = computed(() => `${monthCursor.value.getFullYear()} 年 ${monthCursor.value.getMonth() + 1} 月`)
/** 后端说总共有 N 条、这次只取回 M 条 —— 差额必须说出来 */
const monthOverflow = computed(() => Math.max(0, total.value - occurrences.value.length))

async function loadMonth(): Promise<void> {
  const { from, to } = monthRange(monthCursor.value)
  await calendarStore.loadOccurrences(from, to, 0, MONTH_PAGE_SIZE)
}

async function switchViewMode(next: CalendarViewMode): Promise<void> {
  viewMode.value = next
  if (next === 'month') {
    await loadMonth()
    return
  }
  // 切回列表时沿用当前月窗口，但把每页条数恢复成 100（月视图会把它改成 500）
  const { from, to } = monthRange(monthCursor.value)
  await calendarStore.loadOccurrences(from, to, 0, LIST_PAGE_SIZE)
}

async function shiftMonthBy(delta: number): Promise<void> {
  monthCursor.value = shiftMonth(monthCursor.value, delta)
  await loadMonth()
}

/** el-radio-group 的 change 载荷是宽类型，这里收窄 */
function handleViewChange(value: string | number | boolean): void {
  void switchViewMode(value as CalendarViewMode)
}

async function goToday(): Promise<void> {
  monthCursor.value = startOfMonth(new Date())
  await loadMonth()
}

const createForm = ref<InstanceType<typeof CalendarCreateForm> | null>(null)

/** 点格子空白处：预填新建表单并滚过去，省掉「再选一次日期」 */
function startCreateOn(date: Date): void {
  const now = new Date()
  const isToday = date.toDateString() === now.toDateString()
  const start = new Date(date)
  start.setHours(isToday ? now.getHours() : 9, isToday ? now.getMinutes() : 0, 0, 0)
  const end = new Date(start.getTime() + 60 * 60 * 1000)

  createForm.value?.fillSlot(start, end)

  void nextTick(() => {
    document.querySelector('.cal-create')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
}

async function handleCreate(payload: CreateEventPayload): Promise<void> {
  const ok = await calendarStore.add(payload)
  if (ok) {
    ElMessage.success('已创建日程')
    createForm.value?.reset()
  }
}

// ------------------------------------------------------------------ 编辑与删除

const editing = ref(false)
const editSaving = ref(false)
const editLoadingId = ref<string | null>(null)
/** 编辑基线：**原始定义**而非发生实例（实例只带单次时间，用它提交会抹掉重复规则与描述） */
const editOriginal = ref<CalendarEvent | null>(null)

async function openEdit(occurrence: CalendarOccurrence): Promise<void> {
  editLoadingId.value = occurrence.eventId
  try {
    // 列表返回的是「发生实例」，缺少重复截止与描述 → 编辑前必须取回原始定义
    const event = await calendarStore.loadEvent(occurrence.eventId)
    if (!event) {
      return
    }
    editOriginal.value = event
    editing.value = true
  } finally {
    editLoadingId.value = null
  }
}

async function submitEdit(payload: UpdateEventPayload): Promise<void> {
  const original = editOriginal.value
  if (!original) {
    return
  }
  if (!payload.title?.trim()) {
    ElMessage.warning('请填写日程标题')
    return
  }

  editSaving.value = true
  try {
    const ok = await calendarStore.edit(original.id, payload)
    if (ok) {
      ElMessage.success('已保存')
      editing.value = false
    } else {
      // 失败就地提示：页面顶部的 alert 被弹窗盖住，用户看不到
      ElMessage.error(error.value ?? '保存失败')
    }
  } finally {
    editSaving.value = false
  }
}

async function handleDelete(eventId: string, eventTitle: string): Promise<void> {
  const ok = await confirmDanger({
    title: '删除日程',
    message: `删除「${eventTitle}」会删除整条日程（含全部重复发生）并同时删除它的提醒。此操作不可恢复。`,
    confirmText: '删除',
  })
  if (!ok) {
    return
  }
  const deletedReminders = await calendarStore.remove(eventId)
  if (deletedReminders !== null) {
    ElMessage.success(`已删除，同时清理提醒 ${deletedReminders} 条`)
  }
}

// ------------------------------------------------------------------ 提醒确认

async function acknowledge(reminder: Reminder): Promise<void> {
  if (!(await calendarStore.acknowledge(reminder))) {
    ElMessage.error('确认提醒失败，请重试')
  }
}

/** 批量确认只统计服务端已确认的成功数，失败项保留供重试。 */
const acknowledgingAll = ref(false)

async function acknowledgeAll(): Promise<void> {
  if (acknowledgingAll.value) return
  const reminders = [...dueReminders.value]
  acknowledgingAll.value = true
  try {
    const results = await Promise.all(reminders.map((reminder) => calendarStore.acknowledge(reminder)))
    const succeeded = results.filter(Boolean).length
    if (succeeded === reminders.length) {
      ElMessage.success(`已确认 ${succeeded} 条提醒`)
    } else {
      ElMessage.warning(`已确认 ${succeeded} 条，${reminders.length - succeeded} 条失败，请重试`)
    }
  } finally {
    acknowledgingAll.value = false
  }
}
</script>

<template>
  <div class="calendar">
    <PageHeader size="page" title="日程" />

    <CalendarNotifyBanner
      :permission="notificationPermission"
      :requesting="requestingPermission"
      @enable="enableNotifications"
    />

    <CalendarDueReminders
      v-if="dueReminders.length > 0"
      :reminders="dueReminders"
      :acknowledging-all="acknowledgingAll"
      @acknowledge="acknowledge"
      @acknowledge-all="acknowledgeAll"
    />

    <CalendarCreateForm
      ref="createForm"
      :default-lead="settingsStore.settings.reminderLead"
      :creating="creating"
      @create="handleCreate"
    />

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

    <el-card shadow="never">
      <template #header>
        <div class="calendar__header">
          <div class="calendar__view">
            <span class="calendar__card-title">{{ viewMode === 'month' ? '月视图' : '日程列表' }}</span>
            <el-radio-group
              class="calendar__switch"
              :model-value="viewMode"
              size="small"
              aria-label="日程视图"
              @change="handleViewChange"
            >
              <el-radio-button value="list">列表</el-radio-button>
              <el-radio-button value="month">月</el-radio-button>
            </el-radio-group>
          </div>
          <el-button size="small" :loading="loadingOccurrences" @click="calendarStore.loadOccurrences()">
            刷新
          </el-button>
        </div>
      </template>

      <!-- 月视图：复用同一批 occurrences，只换分组方式 -->
      <CalendarMonthGrid
        v-if="viewMode === 'month'"
        :cells="monthCells"
        :items-by-day="monthGroups"
        :title="monthTitle"
        :overflow="monthOverflow"
        :loading="loadingOccurrences"
        @prev="shiftMonthBy(-1)"
        @next="shiftMonthBy(1)"
        @today="goToday"
        @create-on="startCreateOn"
        @open-edit="openEdit"
      />

      <!-- 列表视图 -->
      <CalendarDayList
        v-else
        :groups="groupedByDay"
        :total="total"
        :page="page"
        :size="size"
        :loading="loadingOccurrences"
        :edit-loading-id="editLoadingId"
        @edit="openEdit"
        @remove="handleDelete"
        @page-change="(next: number) => calendarStore.goToPage(next)"
      />
    </el-card>

    <CalendarEditDialog v-model:visible="editing" :event="editOriginal" :saving="editSaving" @submit="submitEdit" />
  </div>
</template>

<style scoped>
.calendar {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--os-space-5);
}

/* 页头由 PageHeader（size="page"）承担，本文件不再自带 __head/__title/__subtitle */

.calendar__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-3);
  flex-wrap: wrap;
}

.calendar__view {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
  flex-wrap: wrap;
}

.calendar__card-title {
  flex: none;
  width: 4em;
  white-space: nowrap;
  font-size: var(--os-font-md);
  font-weight: 600;
}

.calendar__switch {
  flex: none;
}

.calendar__switch :deep(.el-radio-button__inner) {
  min-width: 44px;
  min-height: 28px;
  height: 28px;
  padding: 5px 10px;
  font-size: var(--os-font-xs);
  line-height: 14px;
}

.calendar__header > .el-button.el-button--small {
  min-height: 28px;
  height: 28px;
  padding-inline: var(--os-space-3);
}

@media (pointer: coarse) {
  .calendar__switch :deep(.el-radio-button__inner),
  .calendar__header > .el-button.el-button--small {
    min-height: 32px;
  }
}
</style>
