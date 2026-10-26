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

import KnowledgeAskTab from '@/components/knowledge/KnowledgeAskTab.vue'
import KnowledgeDocumentsTab from '@/components/knowledge/KnowledgeDocumentsTab.vue'
import KnowledgeSearchTab from '@/components/knowledge/KnowledgeSearchTab.vue'
import { useUiStore } from '@/stores/ui'

const uiStore = useUiStore()
const { knowledgeTab } = storeToRefs(uiStore)
</script>

<template>
  <div class="knowledge">
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
    </el-tabs>
  </div>
</template>

<style scoped>
.knowledge {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-4);
}
</style>
