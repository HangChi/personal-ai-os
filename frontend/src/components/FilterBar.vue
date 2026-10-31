<script setup lang="ts">
/**
 * 筛选栏（阶段 18，contracts C-03）。
 *
 * 只负责排列与响应式（横向 → 窄屏纵向堆叠），不带边框与业务——
 * 边框由所在卡片提供，业务由各页面的插槽内容决定。
 */
</script>

<template>
  <div class="filter-bar">
    <slot />
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
}

.filter-bar :deep(.el-button + .el-button) {
  margin-left: 0;
}

/* 窄屏：纵向堆叠，控件拉满宽度（FR-014） */
@media (max-width: 768px) {
  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-bar :deep(.el-select),
  .filter-bar :deep(.el-input),
  .filter-bar :deep(.el-date-editor) {
    width: 100%;
    min-width: 0;
  }
}
</style>
