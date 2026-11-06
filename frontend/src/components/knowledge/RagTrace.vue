<script setup lang="ts">
import { computed } from 'vue'
import { ArrowRight, CircleCheck, InfoFilled, Warning } from '@element-plus/icons-vue'
import type { KnowledgeHit, RagRetrieval } from '@/types/api'
import RagDisagreements from './RagDisagreements.vue'

const props = defineProps<{ retrieval?: RagRetrieval | null; embedded?: boolean }>()
const guardedClass = 'rag-trace--guarded'
const names: Record<string, string> = {
  recall: '候选召回',
  rewrite: '查询改写',
  rerank: '候选重排',
  evidence: '证据精审',
  answer: '逐项作答与核对',
}
const states: Record<string, string> = {
  READY: '已找到可用证据',
  CONFLICT: '来源存在分歧',
  CLARIFY: '需要澄清适用条件',
  UNVERIFIED: '证据核对未完成',
  INSUFFICIENT: '没有足够证据',
}
const stageStates: Record<string, string> = { OK: '完成', FALLBACK: '已降级', EMPTY: '无结果' }
const warnings = computed(() => [...new Set(props.retrieval?.warnings.filter(Boolean) ?? [])])
const sources = computed(() => {
  const seen = new Set<string>()
  return (props.retrieval?.hits ?? []).filter((hit) => {
    if (seen.has(hit.documentId)) return false
    seen.add(hit.documentId)
    return true
  })
})
const guarded = computed(() => ['CONFLICT', 'CLARIFY', 'UNVERIFIED'].includes(props.retrieval?.status ?? ''))
const statusIcon = computed(() =>
  props.retrieval?.status === 'READY' ? CircleCheck : guarded.value ? Warning : InfoFilled,
)
function sourceMeta(hit: KnowledgeHit) {
  const s = hit.source
  if (!s) return ''
  const period =
    s.effectiveFrom && s.effectiveUntil
      ? s.effectiveFrom + ' 至 ' + s.effectiveUntil
      : s.effectiveFrom
        ? s.effectiveFrom + ' 起生效'
        : s.effectiveUntil
          ? '有效至 ' + s.effectiveUntil
          : ''
  return [s.spaceName, s.version, s.scope, period].filter(Boolean).join(' · ')
}
</script>

<template>
  <details
    v-if="retrieval && (retrieval.stages.length || guarded || warnings.length || retrieval.clarification)"
    class="rag-trace"
    :class="guarded ? guardedClass : null"
  >
    <summary class="rag-trace__summary">
      <el-icon class="rag-trace__status-icon" aria-hidden="true"><component :is="statusIcon" /></el-icon>
      <span class="rag-trace__status" role="status">{{ states[retrieval.status] ?? retrieval.status }}</span>
      <span v-if="!embedded && (sources.length || warnings.length)" class="rag-trace__counts">
        <template v-if="sources.length">{{ sources.length }} 篇来源</template
        ><span v-if="warnings.length">{{ sources.length ? ' · ' : '' }}{{ warnings.length }} 条提示</span>
      </span>
      <span class="rag-trace__action">检索详情</span>
      <el-icon class="rag-trace__chevron" aria-hidden="true"><ArrowRight /></el-icon>
    </summary>
    <div class="rag-trace__body">
      <div v-if="warnings.length || (!embedded && retrieval.clarification)" class="rag-trace__notices">
        <p v-if="!embedded && retrieval.clarification">{{ retrieval.clarification }}</p>
        <p v-for="warning in warnings" :key="warning">{{ warning }}</p>
      </div>
      <RagDisagreements v-if="!embedded" :disagreements="retrieval.disagreements" />
      <section class="rag-trace__section">
        <h4>检索过程</h4>
        <ol class="rag-trace__stages">
          <li v-for="stage in retrieval.stages" :key="stage.name">
            <div class="rag-trace__stage-title">
              <span>{{ names[stage.name] ?? stage.name }}</span>
              <span class="rag-trace__stage-state">{{ stageStates[stage.status] ?? stage.status }}</span>
            </div>
            <span class="rag-trace__stage-meta"
              >{{ stage.inputCount }} → {{ stage.outputCount }} · {{ stage.durationMs }} ms</span
            >
          </li>
        </ol>
        <p class="rag-trace__queries">检索问题：{{ retrieval.queries.join('；') }}</p>
      </section>
      <section v-if="sources.length && !embedded" class="rag-trace__section">
        <h4>适用来源</h4>
        <ul class="rag-trace__sources">
          <li v-for="hit in sources" :key="hit.documentId">
            <span>{{ hit.documentTitle }}</span>
            <span v-if="sourceMeta(hit)" class="rag-trace__source-meta">{{ sourceMeta(hit) }}</span>
          </li>
        </ul>
      </section>
    </div>
  </details>
</template>

<style scoped>
.rag-trace {
  margin-block: var(--os-space-3);
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--os-radius-lg);
  background: var(--el-fill-color-extra-light);
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.rag-trace__summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  min-height: 44px;
  padding: var(--os-space-2) var(--os-space-3);
  cursor: pointer;
  list-style: none;
}
.rag-trace__summary::-webkit-details-marker {
  display: none;
}
.rag-trace__summary:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 3px;
  border-radius: inherit;
}
.rag-trace__summary:hover {
  color: var(--el-text-color-primary);
}
.rag-trace__status-icon {
  flex-shrink: 0;
  font-size: var(--os-font-sm);
  color: var(--el-text-color-regular);
}
.rag-trace--guarded .rag-trace__status-icon {
  color: var(--el-color-warning-dark-2);
}
.rag-trace__status {
  font-weight: 500;
  color: var(--el-text-color-primary);
}
.rag-trace__action {
  margin-left: auto;
  white-space: nowrap;
}
.rag-trace__chevron {
  flex-shrink: 0;
  transition: transform 0.18s ease;
}
.rag-trace[open] .rag-trace__chevron {
  transform: rotate(90deg);
}
.rag-trace__hint {
  flex-basis: 100%;
}
.rag-trace__body {
  padding: var(--os-space-3);
  border-top: 1px solid var(--el-border-color-light);
}
.rag-trace__notices {
  border-left: 2px solid var(--el-border-color);
  padding-left: var(--os-space-3);
}
.rag-trace__notices p {
  margin: 0 0 var(--os-space-2);
}
.rag-trace__section + .rag-trace__section,
.rag-trace__notices + .rag-trace__section {
  margin-top: var(--os-space-4);
}
.rag-trace__section h4 {
  margin: 0 0 var(--os-space-2);
  color: var(--el-text-color-primary);
  font-size: inherit;
  font-weight: 600;
}
.rag-trace__stages {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--os-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}
.rag-trace__stages li {
  padding: var(--os-space-2) var(--os-space-3);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--os-radius-lg);
}
.rag-trace__stage-title {
  display: flex;
  justify-content: space-between;
  gap: var(--os-space-2);
}
.rag-trace__stage-state,
.rag-trace__stage-meta,
.rag-trace__source-meta,
.rag-trace__queries {
  color: var(--el-text-color-regular);
}
.rag-trace__stage-meta {
  display: block;
  margin-top: var(--os-space-1);
}
.rag-trace__queries {
  margin: var(--os-space-2) 0 0;
}
.rag-trace__sources {
  margin: 0;
  padding: 0;
  list-style: none;
}
.rag-trace__sources li + li {
  margin-top: var(--os-space-2);
}
.rag-trace__source-meta {
  display: block;
  margin-top: var(--os-space-1);
}
.rag-trace__conflict + .rag-trace__conflict {
  margin-top: var(--os-space-3);
}
.rag-trace__conflict p {
  margin: 0 0 var(--os-space-2);
}
.rag-trace__conflict blockquote {
  margin: var(--os-space-2) 0;
  border-left: 2px solid var(--el-border-color);
  padding-left: var(--os-space-3);
}
.rag-trace__conflict cite {
  display: block;
  margin-bottom: var(--os-space-1);
  font-style: normal;
  font-weight: 500;
}
@media (max-width: 600px) {
  .rag-trace__summary {
    column-gap: var(--os-space-2);
    row-gap: 0;
  }
  .rag-trace__counts {
    width: 100%;
    padding-left: calc(var(--os-font-sm) + var(--os-space-2));
    order: 1;
  }
  .rag-trace__hint {
    order: 2;
    margin-top: var(--os-space-1);
  }
  .rag-trace__action {
    display: none;
  }
  .rag-trace__chevron {
    margin-left: auto;
  }
  .rag-trace__stages {
    grid-template-columns: 1fr;
  }
}
@media (prefers-reduced-motion: reduce) {
  .rag-trace__chevron {
    transition: none;
  }
}
</style>
