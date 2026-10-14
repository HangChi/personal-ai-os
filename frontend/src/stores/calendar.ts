import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { createEvent, deleteEvent, getEvent, listOccurrences, updateEvent } from '@/api/calendar'
import { listReminders, markReminderRead, scanReminders } from '@/api/reminders'
import type { CreateEventPayload, UpdateEventPayload } from '@/api/calendar'
import type { CalendarEvent, CalendarOccurrence, Reminder } from '@/types/api'

/**
 * 提醒轮询间隔（毫秒）。
 *
 * 比服务端扫描间隔（默认 60 秒）稍快：轮询比扫描更频繁没有意义，
 * 但两者相等时最坏情况要等两个周期才能看到新提醒。
 */
const REMINDER_POLL_INTERVAL_MS = 30_000

/**
 * 日程与提醒状态。
 */
export const useCalendarStore = defineStore('calendar', () => {
  const occurrences = ref<CalendarOccurrence[]>([])
  /** 区间内展开后的总实例数（分页响应带来，不再有静默截断） */
  const total = ref(0)
  /** 当前页码，从 0 开始 */
  const page = ref(0)
  /** 每页条数（日程区间默认 100，与后端默认值一致） */
  const size = ref(100)
  const dueReminders = ref<Reminder[]>([])

  const loadingOccurrences = ref(false)
  const creating = ref(false)
  const error = ref<string | null>(null)

  let pollTimer: ReturnType<typeof setInterval> | null = null

  /** 按天分组的日程，便于渲染 */
  const groupedByDay = computed(() => {
    const groups = new Map<string, CalendarOccurrence[]>()
    for (const occurrence of occurrences.value) {
      const day = occurrence.startsAt.slice(0, 10)
      const bucket = groups.get(day)
      if (bucket) {
        bucket.push(occurrence)
      } else {
        groups.set(day, [occurrence])
      }
    }
    return Array.from(groups.entries()).map(([day, items]) => ({ day, items }))
  })

  /**
   * 加载区间内的发生实例（分页）。
   *
   * 显式传入 from/to 视为「换了查询窗口」，页码归零；
   * 不传则沿用当前窗口与页码（增删改后的刷新用）。
   */
  async function loadOccurrences(from?: string, to?: string, nextPage?: number): Promise<void> {
    loadingOccurrences.value = true
    error.value = null
    try {
      const result = await listOccurrences(from, to, nextPage ?? page.value, size.value)
      occurrences.value = result.items
      total.value = result.total
      page.value = result.page
    } catch (cause) {
      occurrences.value = []
      total.value = 0
      error.value = cause instanceof Error ? cause.message : '加载日程失败'
    } finally {
      loadingOccurrences.value = false
    }
  }

  /** 翻页 */
  async function goToPage(next: number): Promise<void> {
    await loadOccurrences(undefined, undefined, next)
  }

  async function add(payload: CreateEventPayload): Promise<boolean> {
    creating.value = true
    error.value = null
    try {
      await createEvent(payload)
      await loadOccurrences()
      // 新建后立刻扫一次，让新日程的提醒马上可见（不必等下一个调度周期）
      await triggerScan()
      await refreshReminders()
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '创建日程失败'
      return false
    } finally {
      creating.value = false
    }
  }

  /**
   * 读取日程的**原始定义**。
   *
   * <p>编辑必须基于原始定义而不是列表里的「发生实例」：实例只带单次的时间，
   * 缺少重复截止、描述等字段；用实例去提交修改会把重复规则和描述意外抹掉。
   */
  async function loadEvent(id: string): Promise<CalendarEvent | null> {
    error.value = null
    try {
      return await getEvent(id)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '加载日程失败'
      return null
    }
  }

  /**
   * 修改日程。
   *
   * <p>提交时**总是成对给出起止时间** —— 后端要求两者同时提供，
   * 因为只改其中一个会让「结束早于开始」这类非法状态有可乘之机。
   */
  async function edit(id: string, payload: UpdateEventPayload): Promise<boolean> {
    error.value = null
    try {
      await updateEvent(id, payload)
      await loadOccurrences()
      await refreshReminders()
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '修改日程失败'
      return false
    }
  }

  async function remove(id: string): Promise<number | null> {
    error.value = null
    try {
      const result = await deleteEvent(id)
      await loadOccurrences()
      await refreshReminders()
      return result.deletedReminders
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '删除日程失败'
      return null
    }
  }

  async function triggerScan(): Promise<void> {
    try {
      await scanReminders()
    } catch {
      // 手动触发失败不是致命问题：定时扫描迟早会补上
    }
  }

  /**
   * 刷新待提醒。
   *
   * <b>失败时静默</b>：轮询是后台行为，后端短暂不可用时弹一堆错误只会干扰用户。
   * 真正的错误由用户主动操作（创建/删除）时的那条路径暴露。
   */
  async function refreshReminders(): Promise<void> {
    try {
      dueReminders.value = await listReminders(undefined, true)
    } catch {
      // 保持上一轮结果，不打断用户
    }
  }

  async function acknowledge(reminder: Reminder): Promise<void> {
    // 先从列表里移除，让点击立刻有反馈；标记失败时下一轮轮询会把它带回来
    dueReminders.value = dueReminders.value.filter((item) => item.id !== reminder.id)
    try {
      await markReminderRead(reminder.id)
    } catch {
      await refreshReminders()
    }
  }

  function startReminderPolling(): void {
    if (pollTimer !== null) {
      return
    }
    void refreshReminders()
    pollTimer = setInterval(() => {
      void refreshReminders()
    }, REMINDER_POLL_INTERVAL_MS)
  }

  function stopReminderPolling(): void {
    if (pollTimer !== null) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  return {
    occurrences,
    total,
    page,
    size,
    dueReminders,
    loadingOccurrences,
    creating,
    error,
    groupedByDay,
    loadOccurrences,
    goToPage,
    loadEvent,
    add,
    edit,
    remove,
    triggerScan,
    refreshReminders,
    acknowledge,
    startReminderPolling,
    stopReminderPolling,
  }
})
