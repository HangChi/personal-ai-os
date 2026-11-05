<script setup lang="ts">
/**
 * 浏览器通知状态条（从 CalendarView 拆出，2026-10-26）。
 *
 * <p>四态各自给一句「现在能不能收到提醒、怎么才能收到」：
 * 授权后折叠成一行小条不再占地方；被拒绝时给出可操作的恢复路径，
 * 而不是只说「通知被阻止」。
 */
import {
  pushEnabled,
  pushSubscribed,
  pushBusy,
  pushError,
  supportsPush,
  subscribePush,
  unsubscribePush,
} from '@/notifications/web-push'
import type { NotificationPermissionState } from '@/notifications/permission'
import { Bell, BellFilled } from '@element-plus/icons-vue'

defineProps<{
  permission: NotificationPermissionState
  requesting: boolean
}>()

const emit = defineEmits<{ (e: 'enable'): void }>()
</script>

<template>
  <div class="cal-notify" aria-label="日程通知">
    <div class="cal-notify__row">
      <template v-if="permission === 'granted'">
        <!-- 已授权时折叠为一行小条：不再占半屏（T032） -->
        <span class="cal-notify__state"
          ><el-icon aria-hidden="true"><BellFilled /></el-icon>通知已开启</span
        >
        <span class="cal-notify__compact">{{ pushSubscribed ? '后台推送已订阅' : '页面关闭后停止提醒' }}</span>
      </template>
      <template v-else-if="permission === 'default'">
        <el-button plain :icon="Bell" :loading="requesting" @click="emit('enable')"> 开启浏览器通知 </el-button>
      </template>
      <template v-else-if="permission === 'denied'">
        <span class="cal-notify__state"
          ><el-icon aria-hidden="true"><Bell /></el-icon>通知被阻止</span
        >
        <span>请在浏览器站点设置中允许通知。</span>
      </template>
      <template v-else>
        <span class="cal-notify__state"
          ><el-icon aria-hidden="true"><Bell /></el-icon>不支持通知</span
        >
        <span>提醒仅在页面内显示。</span>
      </template>
    </div>
    <div v-if="pushEnabled && supportsPush()" class="cal-notify__row">
      <el-button v-if="!pushSubscribed" size="small" :loading="pushBusy" @click="subscribePush">
        订阅后台推送
      </el-button>
      <el-button v-else size="small" :loading="pushBusy" @click="unsubscribePush">取消后台推送</el-button>
    </div>
    <p v-if="pushError" class="cal-notify__error" role="status">{{ pushError }}</p>
  </div>
</template>

<style scoped>
.cal-notify {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: var(--os-space-2) var(--os-space-3);
  margin-left: auto;
  max-width: 100%;
}

.cal-notify__row {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  flex-wrap: wrap;
  font-size: var(--os-font-xs);
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.cal-notify__state {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-2);
  color: var(--el-text-color-regular);
}

.cal-notify__error {
  flex-basis: 100%;
  margin: 0;
  font-size: var(--os-font-xs);
  color: var(--el-color-danger);
  overflow-wrap: anywhere;
}

.cal-notify__compact {
  overflow-wrap: anywhere;
}
</style>
