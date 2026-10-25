<script setup lang="ts">
/**
 * 主题切换按钮（阶段 18，User Story 1）。
 *
 * 图标只表达「点下去会变成什么」的直觉：当前深色 → 显示太阳（点了变浅）。
 * 悬停文案说明当前状态，避免「为什么点了没反应」的困惑。
 */
import { Moon, Sunny } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { computed } from 'vue'

import { useUiStore } from '@/stores/ui'

const uiStore = useUiStore()
const { resolvedTheme, theme } = storeToRefs(uiStore)

const isDark = computed(() => resolvedTheme.value === 'dark')

/** system 偏好时悬停提示写清楚，否则用户以为按钮坏了 */
const tooltip = computed(() => {
  if (isDark.value) {
    return theme.value === 'system' ? '当前深色（跟随系统），点击切换为浅色' : '切换为浅色'
  }
  return theme.value === 'system' ? '当前浅色（跟随系统），点击切换为深色' : '切换为深色'
})
</script>

<template>
  <el-tooltip :content="tooltip" placement="bottom">
    <el-button class="theme-toggle" link :aria-label="tooltip" @click="uiStore.toggleTheme()">
      <el-icon :size="18">
        <Sunny v-if="isDark" />
        <Moon v-else />
      </el-icon>
    </el-button>
  </el-tooltip>
</template>

<style scoped>
.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--os-radius-md);
  color: var(--el-text-color-regular);
}

.theme-toggle:hover {
  background: var(--el-fill-color);
  color: var(--el-color-primary);
}
</style>
