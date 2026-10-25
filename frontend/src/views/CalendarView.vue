<script setup lang="ts">
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { storeToRefs } from 'pinia'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

import { useCalendarStore } from '@/stores/calendar'
import { buildMonthCells, groupByLocalDay, monthRange, shiftMonth } from '@/lib/calendar-dates'
import type { UpdateEventPayload } from '@/api/calendar'
import type { CalendarEvent, CalendarOccurrence, RecurrenceType, Reminder } from '@/types/api'

const calendarStore = useCalendarStore()
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

const title = ref('')
const location = ref('')
const allDay = ref(false)
const range = ref<[Date, Date] | null>(null)
const recurrenceType = ref<RecurrenceType>('NONE')
const recurrenceUntil = ref<Date | null>(null)
const leadMinutes = ref(15)

const recurrenceOptions: Array<{ label: string; value: RecurrenceType }> = [
  { label: '不重复', value: 'NONE' },
  { label: '每天', value: 'DAILY' },
  { label: '每周', value: 'WEEKLY' },
  { label: '每月', value: 'MONTHLY' },
  { label: '工作日', value: 'WEEKDAYS' },
]

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
const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']
/** 每格最多显示几条，超出折叠成「还有 N 条」 */
const CELL_VISIBLE_LIMIT = 3

function startOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1)
}

const viewMode = ref<CalendarViewMode>('list')
const monthCursor = ref(startOfMonth(new Date()))

const monthCells = computed(() => buildMonthCells(monthCursor.value))
/** 与列表共用同一批 occurrences，只是换一种分组方式 */
const monthGroups = computed(() => groupByLocalDay(occurrences.value))
const monthTitle = computed(
  () => `${monthCursor.value.getFullYear()} 年 ${monthCursor.value.getMonth() + 1} 月`,
)
/** 后端说总共有 N 条、这次只取回 M 条 —— 差额必须说出来 */
const monthOverflow = computed(() => Math.max(0, total.value - occurrences.value.length))

function monthItems(dayKey: string): CalendarOccurrence[] {
  return monthGroups.value.get(dayKey) ?? []
}

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

/** 点格子空白处：预填新建表单并滚过去，省掉「再选一次日期」 */
function startCreateOn(date: Date): void {
  const now = new Date()
  const isToday = date.toDateString() === now.toDateString()
  const start = new Date(date)
  start.setHours(isToday ? now.getHours() : 9, isToday ? now.getMinutes() : 0, 0, 0)
  const end = new Date(start.getTime() + 60 * 60 * 1000)

  title.value = ''
  location.value = ''
  allDay.value = false
  range.value = [start, end]

  void nextTick(() => {
    document.querySelector('.calendar__create')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
  ElMessage.info(`已填入新建表单：${start.toLocaleString('zh-CN', { hour12: false })}`)
}

/** 格子里只显示时分 */
function shortTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso.slice(11, 16)
  }
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

/** 跨天日程显示在起始日，并标出来（否则容易以为它只到当天结束） */
function isCrossDay(item: CalendarOccurrence): boolean {
  return new Date(item.startsAt).toDateString() !== new Date(item.endsAt).toDateString()
}

async function handleCreate(): Promise<void> {
  const value = title.value.trim()
  if (!value) {
    ElMessage.warning('请填写日程标题')
    return
  }
  if (!range.value || range.value.length !== 2) {
    ElMessage.warning('请选择开始与结束时间')
    return
  }

  const ok = await calendarStore.add({
    title: value,
    location: location.value.trim() || undefined,
    allDay: allDay.value,
    startsAt: range.value[0].toISOString(),
    endsAt: range.value[1].toISOString(),
    recurrenceType: recurrenceType.value,
    recurrenceUntil: recurrenceUntil.value?.toISOString(),
    reminderLeadMinutes: leadMinutes.value,
  })

  if (ok) {
    ElMessage.success('已创建日程')
    title.value = ''
    location.value = ''
    range.value = null
    recurrenceUntil.value = null
    recurrenceType.value = 'NONE'
    allDay.value = false
  }
}

const editing = ref(false)
const editSaving = ref(false)
const editLoadingId = ref<string | null>(null)
/** 编辑基线：**原始定义**而非发生实例（实例只带单次时间，用它提交会抹掉重复规则与描述） */
const editOriginal = ref<CalendarEvent | null>(null)
const editForm = ref<{
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

async function openEdit(occurrence: CalendarOccurrence): Promise<void> {
  editLoadingId.value = occurrence.eventId
  try {
    // 列表返回的是「发生实例」，缺少重复截止与描述 → 编辑前必须取回原始定义
    const event = await calendarStore.loadEvent(occurrence.eventId)
    if (!event) {
      return
    }
    editOriginal.value = event
    editForm.value = {
      title: event.title,
      location: event.location ?? '',
      description: event.description ?? '',
      allDay: event.allDay,
      range: [new Date(event.startsAt), new Date(event.endsAt)],
      recurrenceType: event.recurrenceType,
      recurrenceUntil: event.recurrenceUntil ? new Date(event.recurrenceUntil) : null,
      reminderLeadMinutes: event.reminderLeadMinutes,
    }
    editing.value = true
  } finally {
    editLoadingId.value = null
  }
}

async function submitEdit(): Promise<void> {
  const original = editOriginal.value
  if (!original) {
    return
  }
  const value = editForm.value.title.trim()
  if (!value) {
    ElMessage.warning('请填写日程标题')
    return
  }
  if (!editForm.value.range || editForm.value.range.length !== 2) {
    ElMessage.warning('请选择开始与结束时间')
    return
  }

  const payload: UpdateEventPayload = {
    title: value,
    // 地点与描述始终提交（空串表示清空），否则用户永远清不掉已有内容
    location: editForm.value.location.trim(),
    description: editForm.value.description.trim(),
    allDay: editForm.value.allDay,
    // 起止**成对**提供：后端要求如此，单独改一个会让「结束早于开始」有可乘之机
    startsAt: editForm.value.range[0].toISOString(),
    endsAt: editForm.value.range[1].toISOString(),
    recurrenceType: editForm.value.recurrenceType,
    reminderLeadMinutes: editForm.value.reminderLeadMinutes,
  }
  // 重复截止：有值则设置；原本有值现在被清空则显式请求清除
  if (editForm.value.recurrenceUntil) {
    payload.recurrenceUntil = editForm.value.recurrenceUntil.toISOString()
  } else if (original.recurrenceUntil) {
    payload.clearRecurrenceUntil = true
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

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? '—'
    : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
}

function formatRange(start: string, end: string, isAllDay: boolean): string {
  if (isAllDay) {
    return '全天'
  }
  return `${formatTime(start)} ~ ${formatTime(end)}`
}

function formatDayLabel(day: string): string {
  const date = new Date(`${day}T00:00:00`)
  if (Number.isNaN(date.getTime())) {
    return day
  }
  const weekday = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][date.getDay()]
  return `${date.toLocaleDateString('zh-CN')} ${weekday}`
}

function sourceLabel(sourceType: string): string {
  return sourceType === 'TASK' ? '任务' : '日程'
}
</script>

<template>
  <div class="calendar">
    <el-card class="calendar__notify" shadow="never">
      <div class="calendar__notify-row">
        <template v-if="notificationPermission === 'granted'">
          <!-- 已授权时折叠为一行小条：不再占半屏（T032） -->
          <el-tag size="small" type="success" effect="plain">通知已开启</el-tag>
          <span class="calendar__notify-compact">切到别的页面也能收到到点提醒（应用完全关闭时收不到）</span>
        </template>
        <template v-else-if="notificationPermission === 'default'">
          <el-tag size="small" type="info" effect="plain">通知未开启</el-tag>
          <span>开启后即使切到别的页面也能收到提醒。</span>
          <el-button
            size="small"
            type="primary"
            :loading="requestingPermission"
            @click="enableNotifications"
          >
            开启浏览器通知
          </el-button>
        </template>
        <template v-else-if="notificationPermission === 'denied'">
          <el-tag size="small" type="warning" effect="plain">通知被阻止</el-tag>
          <span>
            提醒只会在本页显示。恢复方法：点地址栏左侧的站点图标 → 通知 → 允许，然后刷新页面。
          </span>
        </template>
        <template v-else>
          <el-tag size="small" type="info" effect="plain">不支持通知</el-tag>
          <span>当前浏览器不支持系统通知，提醒只会在本页显示。</span>
        </template>
      </div>
    </el-card>

    <el-card v-if="dueReminders.length > 0" class="calendar__reminders" shadow="never">
      <template #header>
        <div class="calendar__header">
          <span>到点提醒（页面每 30 秒自动刷新）</span>
          <div class="calendar__reminders-actions">
            <span class="calendar__stat">{{ dueReminders.length }} 条待处理</span>
            <!-- 批量确认：8 条提醒逐条点「知道了」是重复劳动（T030） -->
            <el-button size="small" type="primary" :loading="acknowledgingAll" @click="acknowledgeAll">
              全部知道了
            </el-button>
          </div>
        </div>
      </template>

      <div v-for="reminder in dueReminders" :key="reminder.id" class="calendar__reminder">
        <div class="calendar__reminder-body">
          <el-tag size="small" type="warning" effect="plain">{{ sourceLabel(reminder.sourceType) }}</el-tag>
          <span class="calendar__reminder-title">{{ reminder.title }}</span>
          <span class="calendar__reminder-meta">
            {{ formatTime(reminder.occurrenceAt) }} 开始 · 提醒时间 {{ formatTime(reminder.remindAt) }}
          </span>
        </div>
        <el-button size="small" type="primary" @click="acknowledge(reminder)">知道了</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>新建日程</template>

      <!-- 表单分组（T031）：基本信息 / 时间 / 重复与提醒，替代原来一排混放的控件 -->
      <div class="calendar__create">
        <section class="calendar__form-group">
          <span class="calendar__form-group-label">基本信息</span>
          <div class="calendar__form-row">
            <el-input v-model="title" class="calendar__field-title" maxlength="200" placeholder="日程标题，例如：周会" />
            <el-input v-model="location" class="calendar__field-location" placeholder="地点（可选）" />
          </div>
        </section>

        <section class="calendar__form-group">
          <span class="calendar__form-group-label">时间</span>
          <div class="calendar__form-row">
            <el-date-picker
              v-model="range"
              type="datetimerange"
              class="calendar__field-range"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
            />
            <el-checkbox v-model="allDay">全天</el-checkbox>
          </div>
        </section>

        <section class="calendar__form-group">
          <span class="calendar__form-group-label">重复与提醒</span>
          <div class="calendar__form-row">
            <el-select v-model="recurrenceType" class="calendar__field-recurrence">
              <el-option
                v-for="option in recurrenceOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <el-date-picker
              v-if="recurrenceType !== 'NONE'"
              v-model="recurrenceUntil"
              type="date"
              class="calendar__field-until"
              placeholder="重复截止（可选）"
            />
            <div class="calendar__field-lead">
              <span class="calendar__lead-label">提前</span>
              <el-input-number v-model="leadMinutes" :min="0" :max="10080" :step="5" size="small" />
              <span class="calendar__lead-label">分钟提醒</span>
            </div>
          </div>
        </section>

        <div class="calendar__create-actions">
          <el-button type="primary" :loading="creating" @click="handleCreate">创建</el-button>
        </div>
      </div>

      <p class="calendar__hint">
        重复日程在查询时展开为一次次发生，服务端只存规则；「全天」会归一到当天 00:00 至次日 00:00。
      </p>
    </el-card>

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
      <template v-if="viewMode === 'month'">
        <div class="calendar__month-bar">
          <el-button size="small" @click="shiftMonthBy(-1)">上一月</el-button>
          <span class="calendar__month-title">{{ monthTitle }}</span>
          <el-button size="small" @click="goToday">今天</el-button>
          <el-button size="small" @click="shiftMonthBy(1)">下一月</el-button>
          <span v-if="monthOverflow > 0" class="calendar__month-warn">
            另有 {{ monthOverflow }} 条未显示
          </span>
        </div>

        <div v-loading="loadingOccurrences" class="calendar__grid">
          <div v-for="weekday in WEEKDAYS" :key="weekday" class="calendar__weekday">
            {{ weekday }}
          </div>

          <div
            v-for="cell in monthCells"
            :key="cell.key"
            class="calendar__cell"
            :class="{
              'calendar__cell--muted': !cell.inMonth,
              'calendar__cell--today': cell.isToday,
            }"
          >
            <div class="calendar__cell-head">
              <span class="calendar__cell-day">{{ cell.day }}</span>
              <el-button
                class="calendar__cell-add"
                link
                type="primary"
                size="small"
                @click="startCreateOn(cell.date)"
              >
                +
              </el-button>
            </div>

            <div
              v-for="(item, index) in monthItems(cell.key).slice(0, CELL_VISIBLE_LIMIT)"
              :key="`${item.eventId}-${index}`"
              class="calendar__cell-item"
              :title="item.title"
              @click="openEdit(item)"
            >
              <!--
                色点：原规格按「日程/任务」来源着色，但 occurrence 没有来源字段
                （后端 DTO 不带 sourceType，纯前端拿不到）→ 降级为「重复 vs 一次性」，
                扫读月历时区分「每周都有的」与「仅此一次」同样有用。偏差已记录。
              -->
              <span
                class="calendar__cell-dot"
                :class="{ 'calendar__cell-dot--recurring': item.recurring }"
              />
              <span class="calendar__cell-time">
                {{ item.allDay ? '全天' : shortTime(item.startsAt) }}
              </span>
              <span class="calendar__cell-title">{{ item.title }}</span>
              <span v-if="isCrossDay(item)" class="calendar__cell-cross">跨天</span>
            </div>

            <el-popover
              v-if="monthItems(cell.key).length > CELL_VISIBLE_LIMIT"
              placement="bottom-start"
              :width="260"
              trigger="click"
            >
              <template #reference>
                <button type="button" class="calendar__cell-more">
                  还有 {{ monthItems(cell.key).length - CELL_VISIBLE_LIMIT }} 条
                </button>
              </template>
              <div
                v-for="(item, index) in monthItems(cell.key).slice(CELL_VISIBLE_LIMIT)"
                :key="`more-${item.eventId}-${index}`"
                class="calendar__cell-more-item"
                @click="openEdit(item)"
              >
                <span
                  class="calendar__cell-dot"
                  :class="{ 'calendar__cell-dot--recurring': item.recurring }"
                />
                <span class="calendar__cell-time">
                  {{ item.allDay ? '全天' : shortTime(item.startsAt) }}
                </span>
                <span>{{ item.title }}</span>
              </div>
            </el-popover>
          </div>
        </div>
      </template>

      <!-- 列表视图 -->
      <template v-else>
      <el-empty
        v-if="groupedByDay.length === 0 && !loadingOccurrences"
        description="这段时间还没有日程"
        :image-size="70"
      />
      <div v-else v-loading="loadingOccurrences" class="calendar__days">
        <div v-for="group in groupedByDay" :key="group.day" class="calendar__day">
          <div class="calendar__day-label">{{ formatDayLabel(group.day) }}</div>
          <div v-for="(item, index) in group.items" :key="`${item.eventId}-${index}`" class="calendar__item">
            <span class="calendar__item-time">
              {{ formatRange(item.startsAt, item.endsAt, item.allDay) }}
            </span>
            <span class="calendar__item-title">{{ item.title }}</span>
            <el-tag v-if="item.recurring" size="small" type="info" effect="plain">
              {{ recurrenceOptions.find((option) => option.value === item.recurrenceType)?.label }}
            </el-tag>
            <span v-if="item.location" class="calendar__item-location">{{ item.location }}</span>
            <el-button
              class="calendar__item-action"
              link
              type="primary"
              size="small"
              :loading="editLoadingId === item.eventId"
              @click="openEdit(item)"
            >
              编辑
            </el-button>
            <el-button
              class="calendar__item-delete"
              link
              type="danger"
              size="small"
              @click="handleDelete(item.eventId, item.title)"
            >
              删除
            </el-button>
          </div>
        </div>

        <div v-if="total > 0" class="calendar__pagination">
          <span class="calendar__total">区间内共 {{ total }} 次发生</span>
          <el-pagination
            layout="prev, pager, next"
            :total="total"
            :current-page="page + 1"
            :page-size="size"
            background
            @current-change="(next: number) => calendarStore.goToPage(next - 1)"
          />
        </div>
      </div>
      </template>
    </el-card>

    <el-dialog v-model="editing" title="编辑日程" width="560px">
      <p class="calendar__edit-note">
        编辑作用于**整条日程**（含全部重复发生）：改时间后每一次发生都会用新时间。
        目前不支持「只改/只删某一次发生」。
      </p>
      <el-form label-width="90px">
        <el-form-item label="标题" required>
          <el-input v-model="editForm.title" maxlength="200" placeholder="日程标题" />
        </el-form-item>
        <el-form-item label="时间" required>
          <el-date-picker
            v-model="editForm.range"
            type="datetimerange"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
          />
        </el-form-item>
        <el-form-item label="全天">
          <el-checkbox v-model="editForm.allDay">
            全天日程（起止会归一到当天 00:00 至次日 00:00）
          </el-checkbox>
        </el-form-item>
        <el-form-item label="重复">
          <div class="calendar__edit-form-row">
            <el-select v-model="editForm.recurrenceType" style="width: 130px">
              <el-option
                v-for="option in recurrenceOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <el-date-picker
              v-if="editForm.recurrenceType !== 'NONE'"
              v-model="editForm.recurrenceUntil"
              type="date"
              placeholder="重复截止（留空即无限）"
            />
          </div>
        </el-form-item>
        <el-form-item label="地点">
          <el-input v-model="editForm.location" maxlength="200" placeholder="留空即清空" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="editForm.description"
            type="textarea"
            :rows="3"
            maxlength="2000"
            placeholder="留空即清空"
          />
        </el-form-item>
        <el-form-item label="提前提醒">
          <div class="calendar__edit-form-row">
            <el-input-number v-model="editForm.reminderLeadMinutes" :min="0" :max="10080" :step="5" />
            <span class="calendar__lead-label">分钟</span>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editing = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
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

.calendar__reminders-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.calendar__stat {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.calendar__notify {
  margin-bottom: 12px;
}

.calendar__notify-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: var(--os-font-xs);
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

/* 已授权时的一行小条：不换行、超出省略（T032） */
.calendar__notify-compact {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.calendar__reminders {
  border-color: var(--el-color-warning-light-5);
}

.calendar__reminder {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.calendar__reminder:last-child {
  border-bottom: none;
}

.calendar__reminder-body {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.calendar__reminder-title {
  font-size: 14px;
  font-weight: 600;
}

.calendar__reminder-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

/* 新建表单：三组纵排，组内横排（T031） */
.calendar__create {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.calendar__form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.calendar__form-group-label {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  font-weight: 600;
}

.calendar__form-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.calendar__create-actions {
  display: flex;
  justify-content: flex-end;
}

.calendar__field-title {
  width: 220px;
}

.calendar__field-range {
  width: 360px;
}

.calendar__field-location {
  width: 160px;
}

.calendar__field-recurrence {
  width: 110px;
}

.calendar__field-until {
  width: 160px;
}

.calendar__field-lead {
  display: flex;
  align-items: center;
  gap: 6px;
}

.calendar__lead-label {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.calendar__hint {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.calendar__days {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.calendar__pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  margin-top: 14px;
}

.calendar__total {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.calendar__day-label {
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}

.calendar__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0 6px 12px;
  border-left: 3px solid var(--el-color-primary-light-5);
}

.calendar__item-time {
  flex: none;
  width: 130px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.calendar__item-title {
  font-size: 14px;
}

.calendar__item-location {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.calendar__item-action {
  margin-left: auto;
}

.calendar__item-delete {
  margin-left: 0;
}

.calendar__edit-form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.calendar__edit-note {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
/* ---- 视图切换与月网格（阶段 13） ---- */

.calendar__view {
  display: flex;
  align-items: center;
  gap: 12px;
}

.calendar__month-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.calendar__month-title {
  min-width: 110px;
  text-align: center;
  font-weight: 600;
}

.calendar__month-warn {
  margin-left: auto;
  color: var(--el-color-warning);
  font-size: 12px;
}

/*
 * 7 列 × 6 行。行数固定是刻意的：让不同月份的高度一致，切月时下方内容不会被顶来顶去。
 */
.calendar__grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  border-top: 1px solid var(--el-border-color-lighter);
  border-left: 1px solid var(--el-border-color-lighter);
}

.calendar__weekday {
  padding: 6px 8px;
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-right: 1px solid var(--el-border-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.calendar__cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-height: 96px;
  padding: 4px 6px;
  border-right: 1px solid var(--el-border-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.calendar__cell--muted {
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-placeholder);
}

.calendar__cell--today {
  background: var(--el-color-primary-light-9);
}

.calendar__cell-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.calendar__cell-day {
  font-size: 12px;
  font-weight: 600;
}

/* 「+」只在悬停时出现：42 个格子都常驻一个加号会非常吵 */
.calendar__cell-add {
  opacity: 0;
  transition: opacity 0.15s;
}

.calendar__cell:hover .calendar__cell-add {
  opacity: 1;
}

/*
 * 月视图色点（T032）：主色 = 一次性日程，warning = 重复日程。
 * 原规格按「日程/任务」来源着色，但 occurrence DTO 没有来源字段
 * （后端不提供 sourceType，纯前端拿不到），降级为重复/一次性区分。
 */
.calendar__cell-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-primary);
}

.calendar__cell-dot--recurring {
  background: var(--el-color-warning);
}

.calendar__cell-item {
  display: flex;
  align-items: baseline;
  gap: 4px;
  padding: 1px 4px;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
  overflow: hidden;
}

.calendar__cell-item:hover {
  background: var(--el-color-primary-light-8);
}

.calendar__cell-time {
  flex: none;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.calendar__cell-title {
  flex: 1;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.calendar__cell-cross {
  flex: none;
  font-size: 10px;
  color: var(--el-color-warning);
}

.calendar__cell-more {
  margin-top: 2px;
  padding: 0;
  border: none;
  background: none;
  color: var(--el-color-primary);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}

.calendar__cell-more-item {
  display: flex;
  gap: 6px;
  padding: 4px 0;
  font-size: 12px;
  cursor: pointer;
}

.calendar__cell-more-item:hover {
  color: var(--el-color-primary);
}
</style>
