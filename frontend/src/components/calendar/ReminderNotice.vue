<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { useCalendarStore } from '@/stores/calendar'
import type { NoticePayload } from '@/notifications/reminder-notifier'

const props = defineProps<{ notice: NoticePayload }>()
const emit = defineEmits<{ close: []; open: [] }>()
const calendarStore = useCalendarStore()
const saving = ref(false)
const error = ref('')
const reminderId = computed(() => props.notice.reminderIds[0])
const pending = computed(() => calendarStore.dueReminders.some((reminder) => reminder.id === reminderId.value))

// 日程页确认或其他窗口已读后，轮询也会收起对应弹框。
watch(
  pending,
  (value) => {
    if (!value) emit('close')
  },
  { immediate: true },
)

async function acknowledge(): Promise<void> {
  if (saving.value || !reminderId.value) return
  saving.value = true
  error.value = ''
  try {
    if (!(await calendarStore.acknowledge({ id: reminderId.value }))) {
      error.value = '确认提醒失败，请重试'
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
      <el-button type="primary" :loading="saving" @click="acknowledge">知道了</el-button>
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
