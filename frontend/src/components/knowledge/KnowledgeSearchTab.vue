<script setup lang="ts">
/**
 * 知识库 · 检索 Tab。
 *
 * <p>不经过模型：这里要回答的是「检索到了什么」，多一层生成会把问题
 * 变成「模型答得怎么样」，反而看不清检索质量。
 */
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import { ref } from 'vue'

import RagTrace from '@/components/knowledge/RagTrace.vue'
import KnowledgeHitList from '@/components/knowledge/KnowledgeHitList.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useSettingsStore } from '@/stores/settings'
import { useKnowledgeStore } from '@/stores/knowledge'

const knowledgeStore = useKnowledgeStore()
const { hits, searching, searchError, searched, retrieval } = storeToRefs(knowledgeStore)

const searchQuery = ref('')
const searchTopK = ref(useSettingsStore().settings.knowledgeTopK)

async function handleSearch(): Promise<void> {
  const query = searchQuery.value.trim()
  if (!query) {
    ElMessage.warning('请输入检索词')
    return
  }
  await knowledgeStore.search(query, searchTopK.value)
}
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <PageHeader title="检索资料" description="直接查找相关片段，不调用模型生成回答。" />
    </template>

    <form class="ks__query" @submit.prevent="handleSearch">
      <el-input v-model="searchQuery" aria-label="检索词" placeholder="检索词，例如：隔离级别" maxlength="500" />
      <el-input-number v-model="searchTopK" aria-label="检索片段条数" :min="1" :max="20" class="ks__topk" />
      <el-button type="primary" native-type="submit" :loading="searching" :disabled="!searchQuery.trim()"
        >检索</el-button
      >
    </form>

    <!-- 三态（审查 #17）：失败 / 已搜无结果 / 有结果。
         改造前 `hits.length === 0` 同时表达「还没搜」与「搜了没结果」，
         失败也被渲染成「暂无检索结果」，把错误静默成空态 -->
    <el-alert
      v-if="searchError"
      class="ks__error"
      type="error"
      :closable="false"
      show-icon
      title="检索失败"
      :description="searchError"
    />

    <RagTrace :retrieval="retrieval" />
    <el-empty
      v-if="!searchError && !searching && searched && hits.length === 0"
      description="没有匹配的片段，换个检索词试试"
      :image-size="60"
    />
    <KnowledgeHitList v-else-if="hits.length > 0" :items="hits" preview-only />
  </el-card>
</template>

<style scoped>
.ks__query {
  display: flex;
  gap: var(--os-space-2);
  margin-bottom: var(--os-space-3);
}

.ks__error {
  margin-bottom: var(--os-space-3);
}

.ks__topk {
  width: 110px;
  flex: none;
}
@media (max-width: 600px) {
  .ks__query {
    flex-wrap: wrap;
  }
  .ks__query > :deep(.el-input) {
    flex: 1 1 100%;
  }
  .ks__query > :deep(.el-button) {
    flex: 1;
  }
}
</style>
