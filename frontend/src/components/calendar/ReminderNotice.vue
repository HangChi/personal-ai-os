<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { useCalendarStore } from '@/stores/calendar'
import type { NoticePayload } from '@/notifications/reminder-notifier'

const props = defineProps<{ notice: NoticePayload }>()
const emit = defineEmits<{ close: []; open: [] }>()
const calendarStore = useCalendarStore()
const saving = ref(false)
const error = ref('')
const remainingIds = computed(() => {
  const dueIds = new Set(calendarStore.dueReminders.map((reminder) => reminder.id))
  return props.notice.reminderIds.filter((id) => dueIds.has(id))
})

// 日程页确认或其他窗口已读后，轮询也会收起对应弹框。
watch(
  remainingIds,
  (ids) => {
    if (ids.length === 0) emit('close')
  },
  { immediate: true },
)

async function acknowledge(): Promise<void> {
  if (saving.value) return
  saving.value = true
  error.value = ''
  try {
    const results = await Promise.all(remainingIds.value.map((id) => calendarStore.acknowledge({ id })))
    const failed = results.filter((ok) => !ok).length
    if (failed > 0) {
      error.value = `${failed} 条提醒确认失败，请重试`
    } else {
      emit('close')
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="reminder-notice" :aria-busy="saving">
    <p class="reminder-notice__body">{{ notice.body }}</p>
    <p v-if="error" class="reminder-notice__error" role="alert">{{ error }}</p>
    <div class="reminder-notice__actions">
      <el-button text @click="emit('open')">查看日程</el-button>
      <el-button type="primary" :loading="saving" @click="acknowledge">
        {{ notice.reminderIds.length > 1 ? '全部知道了' : '知道了' }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.reminder-notice__body {
  margin: 0;
  overflow-wrap: anywhere;
}

.reminder-notice__error {
  margin: var(--os-space-2) 0 0;
  color: var(--el-color-danger);
}

.reminder-notice__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--os-space-2);
  margin-top: var(--os-space-3);
}

.reminder-notice__actions .el-button + .el-button {
  margin-left: 0;
}
</style>
