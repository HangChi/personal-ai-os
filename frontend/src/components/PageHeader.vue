<script setup lang="ts">
/**
 * 页面头（阶段 18，contracts C-03）。
 *
 * 统一「标题 + 可选描述 + 右侧操作区」结构，替代各页面 card header 里
 * 手写的 flex space-between（改造前 8 个页面重复了 8 遍）。
 *
 * 窄屏行为：操作区自动换行到标题下方，不遮挡标题（FR-014）。
 */
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <div class="page-header">
    <div class="page-header__text">
      <h2 class="page-header__title">{{ title }}</h2>
      <p v-if="description" class="page-header__description">{{ description }}</p>
    </div>
    <div v-if="$slots.default" class="page-header__actions">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--os-space-3);
}

.page-header__text {
  min-width: 0;
}

.page-header__title {
  margin: 0;
  font-size: var(--os-font-lg);
  font-weight: 600;
  line-height: 1.4;
}

.page-header__description {
  margin: 2px 0 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.5;
}

.page-header__actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  flex: none;
}
</style>
