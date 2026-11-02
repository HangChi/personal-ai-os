<script setup lang="ts">
import type { RagRetrieval } from '@/types/api'
defineProps<{ retrieval?: RagRetrieval | null }>()
const names: Record<string, string> = {
  recall: '候选召回',
  rewrite: '查询改写',
  rerank: '候选重排',
  evidence: '证据精审',
}
const states: Record<string, string> = {
  READY: '证据可用于作答',
  CONFLICT: '来源存在分歧',
  CLARIFY: '需要澄清适用条件',
  UNVERIFIED: '证据核对未完成',
  INSUFFICIENT: '没有足够证据',
}
</script>
<template>
  <div v-if="retrieval?.stages.length" class="rag-trace">
    <el-alert
      :title="states[retrieval.status] ?? retrieval.status"
      :type="retrieval.status === 'READY' ? 'info' : 'warning'"
      :closable="false"
      show-icon
    />
    <p v-for="warning in retrieval.warnings" :key="warning">{{ warning }}</p>
    <details>
      <summary>查看检索过程与适用来源</summary>
      <p>检索问题：{{ retrieval.queries.join('；') }}</p>
      <ul>
        <li v-for="stage in retrieval.stages" :key="stage.name">
          {{ names[stage.name] ?? stage.name }}：{{ stage.status === 'OK' ? '完成' : '已降级' }} ·
          {{ stage.durationMs }}ms · {{ stage.inputCount }} → {{ stage.outputCount }}
        </li>
      </ul>
      <p v-for="hit in retrieval.hits.filter((h) => h.source)" :key="`${hit.documentId}-${hit.chunkIndex}`">
        {{ hit.documentTitle }} · {{ hit.source?.spaceName }} · {{ hit.source?.version || '版本未填写' }} ·
        {{ hit.source?.scope || '范围未填写' }} · {{ hit.source?.effectiveFrom || '起始未填写' }} 至
        {{ hit.source?.effectiveUntil || '截止未填写' }}
      </p>
    </details>
  </div>
</template>
<style scoped>
.rag-trace {
  margin-block: var(--os-space-3);
  font-size: var(--os-font-xs);
  line-height: 1.6;
  overflow-wrap: anywhere;
}
summary {
  cursor: pointer;
  padding-block: var(--os-space-2);
}
</style>
