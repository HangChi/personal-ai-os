<script setup lang="ts">
import { ElNotification } from 'element-plus'
import { getCurrentInstance, h, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'

import ReminderNotice from '@/components/calendar/ReminderNotice.vue'
import { useCalendarStore } from '@/stores/calendar'

const calendarStore = useCalendarStore()
const router = useRouter()
const appContext = getCurrentInstance()!.appContext
const notifications = new Set<ReturnType<typeof ElNotification>>()

watch(
  () => calendarStore.inlineNotices,
  () => {
    for (const notice of calendarStore.consumeInlineNotices()) {
      if (!notice.reminderIds.some((id) => calendarStore.dueReminders.some((reminder) => reminder.id === id))) continue
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
          }),
          type: 'warning',
          duration: 0,
          position: 'bottom-right',
          onClose: () => {
            if (notification) notifications.delete(notification)
          },
        },
        appContext,
      )
      notifications.add(notification)
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  for (const notification of notifications) notification.close()
  notifications.clear()
})
</script>

<template>
  <!-- 提醒由 Element Plus 挂到 body，卸载时清理。右上角关闭仅收起，不标记已读。 -->
  <span hidden aria-hidden="true" />
</template>
