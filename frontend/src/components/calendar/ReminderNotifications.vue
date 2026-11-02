<script setup lang="ts">
import { ElNotification } from 'element-plus'
import { getCurrentInstance, h, nextTick, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'

import ReminderNotice from '@/components/calendar/ReminderNotice.vue'
import { useCalendarStore } from '@/stores/calendar'
import type { NoticePayload } from '@/notifications/reminder-notifier'

const calendarStore = useCalendarStore()
const router = useRouter()
const appContext = getCurrentInstance()!.appContext
const queue: NoticePayload[] = []
let activeNotification: ReturnType<typeof ElNotification> | undefined
let disposed = false
let closing = false

/** 每次只展示一条，确认或关闭后再展示下一条；队列内已读项直接跳过。 */
function showNext(): void {
  if (disposed || closing || activeNotification) return
  while (queue.length > 0) {
    const notice = queue.shift()!
    if (!calendarStore.dueReminders.some((reminder) => reminder.id === notice.reminderIds[0])) continue
    let notification: ReturnType<typeof ElNotification> | undefined
    notification = ElNotification(
      {
        title: notice.title,
        message: h(ReminderNotice, {
          notice,
          onClose: () => notification?.close(),
          onOpen: () => {
            void router.push('/calendar')
          },
          // 等前一条退场并卸载后再展示下一条，避免动画期间同时出现两个弹框。
          onVnodeUnmounted: () => {
            closing = false
            void nextTick(showNext)
          },
        }),
        type: 'warning',
        duration: 0,
        position: 'bottom-right',
        onClose: () => {
          closing = true
          activeNotification = undefined
        },
      },
      appContext,
    )
    activeNotification = notification
    return
  }
}

watch(
  () => calendarStore.inlineNotices,
  () => {
    queue.push(...calendarStore.consumeInlineNotices())
    showNext()
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  disposed = true
  queue.length = 0
  activeNotification?.close()
  activeNotification = undefined
})
</script>

<template>
  <!-- 提醒由 Element Plus 挂到 body，卸载时清理。右上角关闭仅收起，不标记已读。 -->
  <span hidden aria-hidden="true" />
</template>
