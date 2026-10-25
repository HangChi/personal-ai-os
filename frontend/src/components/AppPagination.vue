<script setup lang="ts">
/**
 * 统一分页（阶段 18，contracts C-03）。
 *
 * 页码换算关在组件内：Element Plus 从 1 开始，后端从 0 开始——
 * 改造前每个页面都自己写 `current-page = page + 1` 与 `next - 1`，
 * 六个页面六次重复，且漏一处就出现「点页码跳错页」。
 */
defineProps<{
  /** 总条数 */
  total: number
  /** 当前页（0 起，与后端一致） */
  page: number
  /** 每页条数 */
  size: number
}>()

const emit = defineEmits<{
  /** current-page 换算成 0 起后抛出 */
  (event: 'change', page: number): void
}>()

function handleCurrentChange(next: number): void {
  emit('change', next - 1)
}
</script>

<template>
  <div v-if="total > 0" class="app-pagination">
    <span class="app-pagination__total">共 {{ total }} 条</span>
    <el-pagination
      layout="prev, pager, next"
      background
      :total="total"
      :current-page="page + 1"
      :page-size="size"
      @current-change="handleCurrentChange"
    />
  </div>
</template>

<style scoped>
.app-pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--os-space-4);
  margin-top: var(--os-space-3);
}

.app-pagination__total {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}
</style>
