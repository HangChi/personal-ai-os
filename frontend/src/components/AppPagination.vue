<script setup lang="ts">
/**
 * 统一分页（阶段 18，contracts C-03；2026-10-29 收敛剩余三种写法）。
 *
 * <p>页码换算关在组件内：Element Plus 从 1 开始，后端从 0 开始——
 * 改造前每个页面都自己写 `current-page = page + 1` 与 `next - 1`，
 * 六个页面六次重复，且漏一处就出现「点页码跳错页」。
 *
 * <p>2026-10-29 之前仍有四处「同名不同样」的分页，看起来像四个组件：
 * <table>
 *   <tr><th>位置</th><th>计数文案</th><th>对齐</th><th>底色</th></tr>
 *   <tr><td>任务 / 知识文档</td><td>共 N 条</td><td>右</td><td>有</td></tr>
 *   <tr><td>活动</td><td>共 N 条（EP 内建）</td><td>右</td><td>无</td></tr>
 *   <tr><td>日程列表</td><td>区间内共 N 次发生</td><td>右</td><td>有</td></tr>
 *   <tr><td>会话列表</td><td>共 N 个</td><td>右</td><td>无（small）</td></tr>
 * </table>
 * 「共 N 条」里那个「条」在会话列表里是错的（会话论「个」），在日程里更错
 * （论「次发生」）—— 这不是排版洁癖，是文案在说假话。
 * 现在文案由 `totalLabel` 传入，样式由 `compact` 二选一，页码换算只有一份。
 */
withDefaults(
  defineProps<{
    /** 总条数 */
    total: number
    /** 当前页（0 起，与后端一致） */
    page: number
    /** 每页条数 */
    size: number
    /**
     * 计数文案，`{n}` 会被替换成总数。
     *
     * <p>默认「共 {n} 条」。量词必须由调用方给 —— 会话是「个」、日程是
     * 「次发生」，统一写「条」会在两个页面里说错话。
     */
    totalLabel?: string
    /**
     * 紧凑变体：居中、无计数文案、页码块不用实心底色。
     *
     * <p>给 260px 宽的会话侧栏用。那里放不下「共 N 个」+ 页码块，
     * 且实心蓝底的页码在窄栏里视觉过重。紧凑是**尺寸**上的变体，
     * 不是另一套实现 —— 页码换算、事件契约与默认变体完全一致。
     */
    compact?: boolean
  }>(),
  { totalLabel: '共 {n} 条', compact: false },
)

const emit = defineEmits<{
  /** current-page 换算成 0 起后抛出 */
  (event: 'change', page: number): void
}>()

function handleCurrentChange(next: number): void {
  emit('change', next - 1)
}
</script>

<template>
  <div v-if="total > 0" :class="['app-pagination', { 'app-pagination--compact': compact }]">
    <span v-if="!compact" class="app-pagination__total">{{ totalLabel.replace('{n}', String(total)) }}</span>
    <el-pagination
      layout="prev, pager, next"
      :small="compact"
      :background="!compact"
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

/*
 * 紧凑变体：居中，并取消容器外边距的上半部分 —— 侧栏底部空间本就紧张，
 * 默认那 12px 会在列表与分页之间留出一条无效空白。
 */
.app-pagination--compact {
  justify-content: center;
  margin-top: var(--os-space-2);
}
</style>
