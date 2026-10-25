<script setup lang="ts">
/**
 * 统一空态（阶段 18，contracts C-03）。
 *
 * 封装 el-empty：固定 image-size 与插槽布局，默认插槽放引导文案 / 操作按钮。
 */
defineProps<{
  description: string
}>()
</script>

<template>
  <el-empty :description="description" :image-size="70">
    <slot />
  </el-empty>
</template>

<style scoped>
/* 插槽内容（引导文案 / 操作按钮）在 EP 默认插槽里居中 */
:deep(.el-empty__bottom) {
  margin-top: var(--os-space-2);
}
</style>
