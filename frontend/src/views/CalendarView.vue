<script setup lang="ts">
/**
 * 日程页（阶段 18 改造；2026-10-26 按区块拆分为子组件）。
 *
 * <p>本文件保留编排：通知状态条与轮询、视图切换与月份游标、以及「点格子 → 填表单 →
 * 滚动过去」这条跨组件链路。具体的通知条、到点提醒、新建表单、月网格、列表、编辑弹窗
 * 各自成组件（`components/calendar/`）。
 */
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { storeToRefs } from 'pinia'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

import CalendarCreateForm from '@/components/calendar/CalendarCreateForm.vue'
import CalendarDayList from '@/components/calendar/CalendarDayList.vue'
import CalendarDueReminders from '@/components/calendar/CalendarDueReminders.vue'
import CalendarEditDialog from '@/components/calendar/CalendarEditDialog.vue'
import CalendarMonthGrid from '@/components/calendar/CalendarMonthGrid.vue'
import CalendarNotifyBanner from '@/components/calendar/CalendarNotifyBanner.vue'
import { buildMonthCells, groupByLocalDay, monthRange, shiftMonth } from '@/lib/calendar-dates'
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
  inlineNotices,
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
  // 轮询是提醒的「送达」方式：本地单机没有推送通道，靠页面定时拉取。
  // 轮询失败在 store 里被静默处理，不打扰用户。
  calendarStore.startReminderPolling()
})

/**
 * 站内提示：store 只负责决定「该提示什么」，渲染留给视图。
 *
 * 提示内容与系统通知完全一致（同样来自 `NoticePayload`），
 * 这样"在页面里看到"与"在通知中心看到"不会是两套说法。
 */
watch(
  inlineNotices,
  () => {
    const notices = calendarStore.consumeInlineNotices()
    for (const notice of notices) {
      ElNotification({
        title: notice.title,
        message: notice.body,
        type: 'warning',
        duration: 6000,
        position: 'bottom-right',
      })
    }
  },
  // 不加 deep：store 每次都是整体替换数组，引用变化即可触发；
  // deep 会在「回调里写回同一状态」时把自反馈放大成递归更新
)

async function enableNotifications(): Promise<void> {
  await calendarStore.enableNotifications()
  if (notificationPermission.value === 'granted') {
    ElMessage.success('通知已开启')
  } else if (notificationPermission.value === 'denied') {
    ElMessage.warning('浏览器拒绝了通知权限，可在站点设置里手动允许')
  }
}

onUnmounted(() => {
  calendarStore.stopReminderPolling()
})

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
  try {
    await ElMessageBox.confirm(
      `删除「${eventTitle}」会删除整条日程（含全部重复发生）并同时删除它的提醒。`,
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

// ------------------------------------------------------------------ 提醒确认

async function acknowledge(reminder: Reminder): Promise<void> {
  await calendarStore.acknowledge(reminder)
}

/**
 * 批量确认到点提醒（阶段 18，T030）。
 *
 * 8 条提醒逐条点「知道了」是明确的重复劳动。逐条调用现有 acknowledge
 * （纯前端不新增接口），汇总报告成败；acknowledge 内部先乐观移除再请求，
 * 失败的下一轮轮询会带回来，所以这里数不出失败也不误导——提示只说成功数。
 */
const acknowledgingAll = ref(false)

async function acknowledgeAll(): Promise<void> {
  const count = dueReminders.value.length
  acknowledgingAll.value = true
  try {
    await Promise.allSettled(dueReminders.value.map((reminder) => calendarStore.acknowledge(reminder)))
    ElMessage.success(`已确认 ${count} 条提醒`)
  } finally {
    acknowledgingAll.value = false
  }
}
</script>

<template>
  <div class="calendar">
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
            <span>日程</span>
            <el-radio-group :model-value="viewMode" size="small" @change="handleViewChange">
              <el-radio-button value="list">列表</el-radio-button>
              <el-radio-button value="month">月</el-radio-button>
            </el-radio-group>
          </div>
          <el-button
            v-if="viewMode === 'list'"
            size="small"
            :loading="loadingOccurrences"
            @click="calendarStore.loadOccurrences()"
          >
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

.calendar__view {
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
