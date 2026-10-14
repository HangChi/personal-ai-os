import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { createEvent, deleteEvent, listOccurrences } from '@/api/calendar'
import { listReminders, markReminderRead, scanReminders } from '@/api/reminders'
import type { CreateEventPayload } from '@/api/calendar'
import type { CalendarOccurrence, Reminder } from '@/types/api'

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

  async function loadOccurrences(from?: string, to?: string): Promise<void> {
    loadingOccurrences.value = true
    error.value = null
    try {
      occurrences.value = await listOccurrences(from, to)
    } catch (cause) {
      occurrences.value = []
      error.value = cause instanceof Error ? cause.message : '加载日程失败'
    } finally {
      loadingOccurrences.value = false
    }
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
    dueReminders,
    loadingOccurrences,
    creating,
    error,
    groupedByDay,
    loadOccurrences,
    add,
    remove,
    triggerScan,
    refreshReminders,
    acknowledge,
    startReminderPolling,
    stopReminderPolling,
  }
})
