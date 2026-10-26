<script setup lang="ts">
/**
 * 浏览器通知状态条（从 CalendarView 拆出，2026-10-26）。
 *
 * <p>四态各自给一句「现在能不能收到提醒、怎么才能收到」：
 * 授权后折叠成一行小条不再占地方；被拒绝时给出可操作的恢复路径，
 * 而不是只说「通知被阻止」。
 */
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
        <span class="cal-notify__compact">切到别的页面也能收到到点提醒（应用完全关闭时收不到）</span>
      </template>
      <template v-else-if="permission === 'default'">
        <el-tag size="small" type="info" effect="plain">通知未开启</el-tag>
        <span>开启后即使切到别的页面也能收到提醒。</span>
        <el-button size="small" type="primary" :loading="requesting" @click="emit('enable')">
          开启浏览器通知
        </el-button>
      </template>
      <template v-else-if="permission === 'denied'">
        <el-tag size="small" type="warning" effect="plain">通知被阻止</el-tag>
        <span>提醒只会在本页显示。恢复方法：点地址栏左侧的站点图标 → 通知 → 允许，然后刷新页面。</span>
      </template>
      <template v-else>
        <el-tag size="small" type="info" effect="plain">不支持通知</el-tag>
        <span>当前浏览器不支持系统通知，提醒只会在本页显示。</span>
      </template>
    </div>
  </el-card>
</template>

<style scoped>
.cal-notify {
  margin-bottom: 12px;
}

.cal-notify__row {
  display: flex;
  align-items: center;
  gap: 10px;
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
