<script setup lang="ts">
import type { RagRetrieval, KnowledgeHit } from '@/types/api'
import type { InlineCitation } from '@/lib/citations'

const props = defineProps<{
  disagreements: RagRetrieval['disagreements']
  citations?: readonly InlineCitation[]
}>()
const emit = defineEmits<{ selectSource: [index: number] }>()

function citationFor(hit: KnowledgeHit) {
  return props.citations?.find((item) => item.documentId === hit.documentId && item.chunkIndex === hit.chunkIndex)
}
function citationLabel(hit: KnowledgeHit) {
  const citation = citationFor(hit)
  return citation ? `[${citation.index}] ${hit.documentTitle}` : hit.documentTitle
}
function sourceMeta(hit: KnowledgeHit) {
  const source = hit.source
  if (!source) return ''
  const period =
    source.effectiveFrom && source.effectiveUntil
      ? `${source.effectiveFrom} 至 ${source.effectiveUntil}`
      : source.effectiveFrom
        ? `${source.effectiveFrom} 起生效`
        : source.effectiveUntil
          ? `有效至 ${source.effectiveUntil}`
          : ''
  return [source.version, source.scope, period].filter(Boolean).join(' · ')
}
</script>

<template>
  <section v-if="disagreements.length" class="rag-disagreements" aria-label="来源分歧">
    <div class="rag-disagreements__heading">
      <h4>
        来源分歧 <span>{{ disagreements.length }}</span>
      </h4>
      <a href="/knowledge?tab=governance">资料治理 <span aria-hidden="true">→</span></a>
    </div>
    <article
      v-for="(conflict, index) in disagreements"
      :key="index"
      class="rag-trace__conflict rag-disagreements__item"
    >
      <h5>{{ conflict.reason || '同一事项存在不同说法' }}</h5>
      <blockquote v-for="side in ['left', 'right'] as const" :key="side">
        <cite>
          <button
            v-if="citationFor(conflict[side])"
            type="button"
            @click="emit('selectSource', citationFor(conflict[side])!.index)"
          >
            {{ citationLabel(conflict[side]) }}
          </button>
          <template v-else>{{ conflict[side].documentTitle }}</template>
        </cite>
        <p>{{ side === 'left' ? conflict.leftQuote : conflict.rightQuote }}</p>
        <small v-if="sourceMeta(conflict[side])">{{ sourceMeta(conflict[side]) }}</small>
      </blockquote>
    </article>
  </section>
</template>

<style scoped>
.rag-disagreements {
  margin-bottom: var(--os-space-5);
  overflow-wrap: anywhere;
}
.rag-disagreements__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-3);
  flex-wrap: wrap;
}
.rag-disagreements h4 {
  margin: 0;
  font-size: var(--os-font-sm);
}
.rag-disagreements h4 span {
  margin-left: var(--os-space-1);
  color: var(--el-text-color-secondary);
  font-weight: 400;
}
.rag-disagreements__heading a {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-1);
  min-height: 36px;
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
  text-decoration: none;
}
.rag-disagreements__heading a:hover {
  color: var(--el-color-primary);
}
.rag-disagreements__heading a:focus-visible,
.rag-disagreements cite button:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 3px;
  border-radius: var(--os-radius-sm);
}
.rag-disagreements__item + .rag-disagreements__item {
  margin-top: var(--os-space-4);
  padding-top: var(--os-space-3);
  border-top: 1px solid var(--el-border-color-lighter);
}
.rag-disagreements h5 {
  margin: var(--os-space-2) 0;
  font-size: var(--os-font-sm);
  font-weight: 500;
  line-height: 1.6;
}
.rag-disagreements blockquote {
  margin: var(--os-space-2) 0;
  padding: var(--os-space-3);
  border-left: 2px solid var(--el-border-color);
  border-radius: 0 var(--os-radius-sm) var(--os-radius-sm) 0;
  background: var(--el-fill-color-extra-light);
  font-size: var(--os-font-sm);
  line-height: 1.7;
}
.rag-disagreements cite {
  display: block;
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
  font-style: normal;
}
.rag-disagreements cite button {
  display: block;
  min-height: 36px;
  padding: 0;
  border: 0;
  border-radius: var(--os-radius-sm);
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.rag-disagreements cite button:hover {
  color: var(--el-color-primary);
  text-decoration: underline;
}
.rag-disagreements blockquote p {
  margin: var(--os-space-1) 0 0;
  white-space: pre-wrap;
}
.rag-disagreements small {
  display: block;
  margin-top: var(--os-space-2);
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
}
@media (pointer: coarse) {
  .rag-disagreements__heading a,
  .rag-disagreements cite button {
    min-height: 44px;
  }
}
</style>
