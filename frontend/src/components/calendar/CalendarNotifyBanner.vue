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

defineProps<{
  permission: NotificationPermissionState
  requesting: boolean
}>()

const emit = defineEmits<{ (e: 'enable'): void }>()
</script>

<template>
  <el-card class="cal-notify" shadow="never">
    <div class="cal-notify__row">
      <template v-if="permission === 'granted'">
        <!-- 已授权时折叠为一行小条：不再占半屏（T032） -->
        <el-tag size="small" type="success" effect="plain">通知已开启</el-tag>
        <span class="cal-notify__compact">{{ pushSubscribed ? '后台推送已订阅' : '页面关闭后停止提醒' }}</span>
      </template>
      <template v-else-if="permission === 'default'">
        <el-tag size="small" type="info" effect="plain">通知未开启</el-tag>
        <el-button size="small" type="primary" :loading="requesting" @click="emit('enable')">
          开启浏览器通知
        </el-button>
      </template>
      <template v-else-if="permission === 'denied'">
        <el-tag size="small" type="warning" effect="plain">通知被阻止</el-tag>
        <span>请在浏览器站点设置中允许通知。</span>
      </template>
      <template v-else>
        <el-tag size="small" type="info" effect="plain">不支持通知</el-tag>
        <span>提醒仅在页面内显示。</span>
      </template>
    </div>
    <div v-if="pushEnabled && supportsPush()" class="cal-notify__row">
      <el-button v-if="!pushSubscribed" size="small" :loading="pushBusy" @click="subscribePush">
        订阅后台推送
      </el-button>
      <el-button v-else size="small" :loading="pushBusy" @click="unsubscribePush">取消后台推送</el-button>
    </div>
    <p v-if="pushError" role="status">{{ pushError }}</p>
  </el-card>
</template>

<style scoped>
.cal-notify {
  margin-bottom: var(--os-space-3);
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

/* 已授权时的一行小条：不换行、超出省略（T032） */
.cal-notify__compact {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
