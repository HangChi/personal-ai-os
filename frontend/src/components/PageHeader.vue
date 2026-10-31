<script setup lang="ts">
/**
 * 页面头（阶段 18，contracts C-03；阶段 23 重做排版层级）。
 *
 * 统一「标题 + 可选描述 + 右侧操作区」结构，替代各页面 card header 里
 * 手写的 flex space-between（改造前 8 个页面重复了 8 遍）。
 *
 * 阶段 23 变化：原先标题 15px、卡片内正文 13px —— 只差 2px，层级几乎看不出来，
 * 于是每张卡片都在「一样大的一堆字」里靠自己找重点。现在分出三档：
 * - `size="page"`：页面级标题（22px），用于页面顶部主标题；
 * - `size="card"`（默认）：卡片级标题（17px），用于 el-card 的 header；
 * - `size="section"`：区块标题（14px + 字重），用于卡片内部再分组。
 *
 * 窄屏行为：操作区自动换行到标题下方，不遮挡标题（FR-014）。
 */
withDefaults(
  defineProps<{
    title: string
    description?: string
    /** 标题层级：page（页面）/ card（卡片）/ section（区块） */
    size?: 'page' | 'card' | 'section'
  }>(),
  // description 显式给 undefined 默认值：vue/require-default-prop 要求可选 prop 有默认值
  { description: undefined, size: 'card' },
)
</script>

<template>
  <div :class="['page-header', `page-header--${size}`]">
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
  flex: 1 1 240px;
  min-width: 0;
}

.page-header__title {
  margin: 0;
  font-size: var(--os-font-lg);
  font-weight: 600;
  line-height: 1.4;
  /*
   * 长标题（如全局搜索页把用户的整条关键词放进标题）必须能换行。
   * 原先只有搜索页单独写了这条 —— 那是个通用性质，不该由调用方各写一遍：
   * 任务标题、记忆摘要、文档名都可能很长。`break-word` 而不是 `break-all`，
   * 英文单词仍按词断开，只是超长单词才强制拆。
   */
  overflow-wrap: break-word;
  word-break: break-word;
}

.page-header__description {
  max-width: 72ch;
  margin: var(--os-space-1) 0 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
  line-height: 1.6;
  overflow-wrap: break-word;
}

.page-header__actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  flex: none;
}

.page-header__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

@media (max-width: 600px) {
  .page-header__actions {
    width: 100%;
  }
}

/* ---- 三档层级 ---- */

.page-header--page .page-header__title {
  font-size: var(--os-font-2xl);
  font-weight: 650;
  letter-spacing: -0.2px;
  line-height: 1.3;
}

.page-header--card .page-header__title {
  font-size: var(--os-font-xl);
}

/* 卡片档的描述比标题小两级，避免「17 / 13」两种字号互相打架 */
.page-header--card .page-header__description {
  font-size: var(--os-font-xs);
}

.page-header--section .page-header__title {
  font-size: var(--os-font-md);
  font-weight: 600;
  color: var(--el-text-color-regular);
}

.page-header--section .page-header__description {
  font-size: var(--os-font-xs);
}
</style>
