<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowRight, Document, InfoFilled } from '@element-plus/icons-vue'
import RagTrace from '@/components/knowledge/RagTrace.vue'
import { safeCitationUrl, type InlineCitation } from '@/lib/citations'
import type { RagRetrieval } from '@/types/api'

const props = defineProps<{ citations?: readonly InlineCitation[]; retrieval?: RagRetrieval | null }>()
const open = ref(false)
const guarded = computed(() => ['CONFLICT', 'CLARIFY', 'UNVERIFIED'].includes(props.retrieval?.status ?? ''))
const hasNotice = computed(
  () =>
    guarded.value || !!props.retrieval?.warnings.some((warning) => warning.trim()) || !!props.retrieval?.clarification,
)
const groups = computed(() => {
  const result = new Map<string, { title: string; url?: string; meta: string; citations: InlineCitation[] }>()
  for (const citation of props.citations ?? []) {
    const url = safeCitationUrl(citation.url)
    const documentId = citation.documentId
    const hit = props.retrieval?.hits.find((item) => item.documentId === documentId)
    const source = hit?.source ?? citation.source
    const period = hit?.source ? [hit.source.effectiveFrom, hit.source.effectiveUntil].filter(Boolean).join(' 至 ') : ''
    const key = documentId || url?.href || `${citation.filename}:${citation.documentTitle}`
    let group = result.get(key)
    if (!group) {
      group = {
        title: citation.documentTitle || citation.filename || '来源',
        url: url?.href,
        meta: [url?.hostname, citation.filename, hit?.source?.spaceName, source?.version, source?.scope, period]
          .filter(Boolean)
          .join(' · '),
        citations: [],
      }
      result.set(key, group)
    }
    group.citations.push(citation)
  }
  return [...result.values()]
})
</script>

<template>
  <div v-if="groups.length || hasNotice" class="chat-sources">
    <button
      type="button"
      class="chat-sources__trigger"
      aria-haspopup="dialog"
      :aria-expanded="open"
      @click="open = true"
    >
      <el-icon aria-hidden="true"><Document v-if="groups.length" /><InfoFilled v-else /></el-icon>
      <span>{{ groups.length ? `${groups.length} 个来源` : '检索说明' }}</span>
      <span v-if="groups.length && hasNotice" class="chat-sources__notice">{{ guarded ? '需核对' : '有说明' }}</span>
      <el-icon aria-hidden="true"><ArrowRight /></el-icon>
    </button>
    <el-drawer v-model="open" title="来源与检索" size="min(440px, 100vw)" append-to-body destroy-on-close>
      <div class="chat-sources__drawer">
        <section v-for="(group, index) in groups" :key="index" class="chat-sources__source">
          <h3>
            <a v-if="group.url" :href="group.url" target="_blank" rel="noopener noreferrer">{{ group.title }} ↗</a>
            <span v-else>{{ group.title }}</span>
          </h3>
          <p v-if="group.meta" class="chat-sources__meta">{{ group.meta }}</p>
          <details v-for="citation in group.citations" :key="citation.index" class="chat-sources__excerpt">
            <summary>
              [{{ citation.index }}] {{ citation.url ? '网页摘录' : `片段 ${citation.chunkIndex + 1}` }}
            </summary>
            <p>{{ citation.content }}</p>
          </details>
        </section>
        <RagTrace :retrieval="retrieval" embedded />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.chat-sources {
  margin-top: var(--os-space-2);
}
.chat-sources__trigger {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-2);
  min-height: 36px;
  max-width: 100%;
  padding: 0 var(--os-space-3);
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--os-radius-xl);
  background: var(--el-fill-color-extra-light);
  color: var(--el-text-color-regular);
  font: inherit;
  font-size: var(--os-font-xs);
  cursor: pointer;
}
.chat-sources__trigger:hover {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}
.chat-sources__trigger:focus-visible,
.chat-sources__excerpt summary:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 3px;
}
.chat-sources__notice {
  color: var(--el-color-warning-dark-2);
}
.chat-sources__drawer {
  overflow-wrap: anywhere;
}
.chat-sources__source + .chat-sources__source {
  margin-top: var(--os-space-5);
  padding-top: var(--os-space-4);
  border-top: 1px solid var(--el-border-color-lighter);
}
.chat-sources__source h3 {
  margin: 0;
  font-size: var(--os-font-md);
  line-height: 1.5;
}
.chat-sources__source a {
  color: var(--el-text-color-primary);
  text-decoration: none;
}
.chat-sources__source a:hover {
  text-decoration: underline;
}
.chat-sources__meta {
  margin: var(--os-space-1) 0 var(--os-space-2);
  font-size: var(--os-font-xs);
  color: var(--el-text-color-regular);
}
.chat-sources__excerpt {
  font-size: var(--os-font-sm);
  line-height: 1.7;
}
.chat-sources__excerpt summary {
  min-height: 36px;
  align-content: center;
  cursor: pointer;
  border-radius: var(--os-radius-sm);
}
.chat-sources__excerpt p {
  white-space: pre-wrap;
  margin: var(--os-space-2) 0 var(--os-space-3);
  padding-left: var(--os-space-3);
  border-left: 2px solid var(--el-border-color);
}
@media (pointer: coarse) {
  .chat-sources__trigger,
  .chat-sources__excerpt summary {
    min-height: 44px;
  }
}
</style>
