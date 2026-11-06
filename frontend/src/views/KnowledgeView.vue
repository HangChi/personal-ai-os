<script setup lang="ts">
/** 资料与长期记忆共用工作区入口，具体位置可复制、刷新与回退。 */
import { computed, defineAsyncComponent, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isKnowledgeTab, type KnowledgeTab } from '@/lib/workspace-navigation'

import KnowledgeCaptures from '@/components/knowledge/KnowledgeCaptures.vue'
import KnowledgeGovernanceTab from '@/components/knowledge/KnowledgeGovernanceTab.vue'
import KnowledgeAskTab from '@/components/knowledge/KnowledgeAskTab.vue'
import KnowledgeDocumentsTab from '@/components/knowledge/KnowledgeDocumentsTab.vue'
import KnowledgeSearchTab from '@/components/knowledge/KnowledgeSearchTab.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useUiStore } from '@/stores/ui'

const uiStore = useUiStore()
const MemoryView = defineAsyncComponent(() => import('@/views/MemoryView.vue'))
const route = useRoute()
const router = useRouter()
// 将本地起始页固定到首次历史记录，返回时不会被后来选择的标签覆盖。
watch(
  [() => route.path, () => route.query.tab],
  ([path, tab]) => {
    if (path === '/knowledge' && !isKnowledgeTab(tab)) {
      void router.replace({ query: { ...route.query, tab: uiStore.knowledgeTab } })
    }
  },
  { immediate: true },
)
const knowledgeTab = computed<KnowledgeTab>({
  get: () => (isKnowledgeTab(route.query.tab) ? route.query.tab : uiStore.knowledgeTab),
  set: (tab) => {
    void router.push({ query: { ...route.query, tab } })
  },
})
watch(
  knowledgeTab,
  (tab) => {
    uiStore.knowledgeTab = tab
  },
  { immediate: true },
)
</script>

<template>
  <div class="knowledge">
    <PageHeader size="page" title="知识库" description="收集资料、检索答案，在同一处管理助手的长期记忆。" />

    <el-tabs v-model="knowledgeTab" class="knowledge__tabs os-module-tabs">
      <el-tab-pane label="文档" name="documents" lazy>
        <KnowledgeDocumentsTab />
      </el-tab-pane>
      <el-tab-pane label="检索" name="search" lazy>
        <KnowledgeSearchTab />
      </el-tab-pane>
      <el-tab-pane label="问答" name="ask" lazy>
        <KnowledgeAskTab />
      </el-tab-pane>
      <el-tab-pane label="网页采集" name="captures" lazy
        ><KnowledgeCaptures v-if="knowledgeTab === 'captures'"
      /></el-tab-pane>
      <el-tab-pane label="资料治理" name="governance" lazy>
        <KnowledgeGovernanceTab v-if="knowledgeTab === 'governance'" />
      </el-tab-pane>
      <el-tab-pane label="长期记忆" name="memory" lazy><MemoryView embedded /></el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.knowledge {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-5);
}

/* 页头由 PageHeader 统一渲染（size="page"） */

/*
 * Tab 条与内容之间拉开距离：EP 默认 tab 内容紧贴下划线（间距 16px），
 * 而内容区第一块通常是带边框的卡片，贴太近会像「卡片顶到选中标签下」。
 */
.knowledge__tabs :deep(.el-tabs__content) {
  padding-top: var(--os-space-4);
}

/* 顶部 Tab 的下划线加粗一档，选中态更明确 */
.knowledge__tabs :deep(.el-tabs__item) {
  font-size: var(--os-font-md);
}

.knowledge__tabs :deep(.el-tabs__item.is-active) {
  font-weight: 600;
}
</style>
