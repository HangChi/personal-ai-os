<script setup lang="ts">
import { InfoFilled, ArrowRight } from '@element-plus/icons-vue'
import type { StreamWarning } from '@/api/chat'
defineProps<{ warnings: StreamWarning[]; busy?: boolean }>()
</script>
<template>
  <details v-if="warnings.length" class="turn-warnings">
    <summary>
      <el-icon aria-hidden="true"><InfoFilled /></el-icon>
      <span>{{ warnings.length }} 条执行提示</span>
      <span class="turn-warnings__hint">{{ busy ? '正在继续处理' : '查看本轮处理说明' }}</span>
      <el-icon class="turn-warnings__chevron" aria-hidden="true"><ArrowRight /></el-icon>
    </summary>
    <ul>
      <li v-for="warning in warnings" :key="warning.code + warning.message">{{ warning.message }}</li>
    </ul>
  </details>
</template>
<style scoped>
.turn-warnings {
  margin-block: var(--os-space-3);
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.turn-warnings summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  min-height: 44px;
  cursor: pointer;
  list-style: none;
  border-radius: var(--os-radius-md);
}
.turn-warnings summary::-webkit-details-marker {
  display: none;
}
.turn-warnings summary:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
.turn-warnings__chevron {
  transition: transform 0.18s ease;
}
.turn-warnings[open] .turn-warnings__chevron {
  transform: rotate(90deg);
}
.turn-warnings ul {
  margin: 0;
  padding-left: var(--os-space-5);
}
.turn-warnings li + li {
  margin-top: var(--os-space-1);
}
@media (max-width: 600px) {
  .turn-warnings__hint {
    display: none;
  }
}
@media (prefers-reduced-motion: reduce) {
  .turn-warnings__chevron {
    transition: none;
  }
}
</style>
