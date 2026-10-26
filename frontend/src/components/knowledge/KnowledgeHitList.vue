<script setup lang="ts">
/**
 * 检索命中列表（检索 Tab 与问答 Tab 共用）。
 *
 * <p>两处展示的是同一种东西：文档标题 + 片段序号 + 命中来源徽标 + 片段正文。
 * 差异只有「要不要显示 [n] 编号」—— 问答的引用需要编号与正文里的标注对齐，
 * 检索结果则没有编号可言。用一个 `indexed` 开关表达，避免维护两份模板。
 */
import { hitBadge } from '@/lib/format'
import type { KnowledgeHit } from '@/types/api'

const props = defineProps<{
  items: KnowledgeHit[]
  /** 是否在标题前显示 [n] 编号（问答引用用） */
  indexed?: boolean
  /** 是否给每条加左侧主色边条（问答引用用，强调「这是一段被引用的原文」） */
  bordered?: boolean
}>()

/**
 * 编号取引用自带的 index（与正文里的 [n] 标注对齐）；
 * 没有 index 时退化为列表序号 —— 检索结果本就没有编号，只是补齐视觉一致。
 */
function labelOf(hit: KnowledgeHit, index: number): string {
  const explicit = (hit as KnowledgeHit & { index?: number }).index
  return `[${explicit ?? index + 1}]`
}
</script>

<template>
  <ul class="kh">
    <li
      v-for="(hit, index) in props.items"
      :key="`${hit.documentId}-${hit.chunkIndex}-${index}`"
      :class="['kh__item', { 'kh__item--bordered': props.bordered }]"
    >
      <div class="kh__head">
        <span class="kh__source">
          <template v-if="props.indexed">{{ labelOf(hit, index) }} </template>
          {{ hit.documentTitle }}
          <span class="kh__meta">#{{ hit.chunkIndex + 1 }}</span>
        </span>
        <el-tag size="small" :type="hitBadge(hit).type" effect="plain">{{ hitBadge(hit).text }}</el-tag>
      </div>
      <div class="kh__content">{{ hit.content }}</div>
    </li>
  </ul>
</template>

<style scoped>
.kh {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
}

/* 引用态：左侧主色边条，与「检索结果」在视觉上区分开 */
.kh__item--bordered {
  border-left: 3px solid var(--el-color-primary-light-5);
  padding-left: 10px;
}

.kh__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-2);
}

.kh__source {
  font-size: var(--os-font-xs);
  font-weight: 600;
}

.kh__meta {
  margin-left: var(--os-space-1);
  color: var(--el-text-color-secondary);
  font-weight: 400;
  font-size: var(--os-font-xs);
}

.kh__content {
  margin-top: var(--os-space-1);
  padding: var(--os-space-2) 10px;
  border-radius: var(--os-radius-sm);
  background: var(--el-fill-color-light);
  font-size: var(--os-font-xs);
  white-space: pre-wrap;
}
</style>
