<script setup lang="ts">
/**
 * 知识库页（阶段 18 改造，User Story 7；2026-10-26 拆分为三个 Tab 组件）。
 *
 * <p>本文件只剩 Tab 容器：文档 / 检索 / 问答三块各自成组件，
 * 各自持有自己的输入状态（检索词、问题），互不影响。
 *
 * <p>Tab 选择持久化到 localStorage（个人习惯，不进 URL——不可分享的状态没有 query 价值）。
 */
import { storeToRefs } from 'pinia'

import KnowledgeCaptures from '@/components/knowledge/KnowledgeCaptures.vue'
import KnowledgeGovernanceTab from '@/components/knowledge/KnowledgeGovernanceTab.vue'
import KnowledgeAskTab from '@/components/knowledge/KnowledgeAskTab.vue'
import KnowledgeDocumentsTab from '@/components/knowledge/KnowledgeDocumentsTab.vue'
import KnowledgeSearchTab from '@/components/knowledge/KnowledgeSearchTab.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useUiStore } from '@/stores/ui'

const uiStore = useUiStore()
const { knowledgeTab } = storeToRefs(uiStore)
</script>

<template>
  <div class="knowledge">
    <PageHeader size="page" title="知识库" />

    <el-tabs v-model="knowledgeTab" class="knowledge__tabs">
      <el-tab-pane label="文档" name="documents">
        <KnowledgeDocumentsTab />
      </el-tab-pane>
      <el-tab-pane label="检索" name="search">
        <KnowledgeSearchTab />
      </el-tab-pane>
      <el-tab-pane label="问答" name="ask">
        <KnowledgeAskTab />
      </el-tab-pane>
      <el-tab-pane label="网页采集" name="captures" lazy
        ><KnowledgeCaptures v-if="knowledgeTab === 'captures'"
      /></el-tab-pane>
      <el-tab-pane label="资料治理" name="governance" lazy>
        <KnowledgeGovernanceTab v-if="knowledgeTab === 'governance'" />
      </el-tab-pane>
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
