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

import KnowledgeHitList from '@/components/knowledge/KnowledgeHitList.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useKnowledgeStore } from '@/stores/knowledge'

const knowledgeStore = useKnowledgeStore()
const { hits, searching, searchError, searched } = storeToRefs(knowledgeStore)

const searchQuery = ref('')
const searchTopK = ref(4)

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
      <PageHeader title="检索" description="不经过模型，直接看检索到了什么" />
    </template>

    <div class="ks__query">
      <el-input
        v-model="searchQuery"
        placeholder="检索词，例如：隔离级别"
        maxlength="500"
        @keydown.enter="handleSearch"
      />
      <el-input-number v-model="searchTopK" :min="1" :max="20" class="ks__topk" />
      <el-button :loading="searching" @click="handleSearch">检索</el-button>
    </div>

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

    <el-empty v-else-if="searched && hits.length === 0" description="没有匹配的片段，换个检索词试试" :image-size="60" />
    <KnowledgeHitList v-else-if="hits.length > 0" :items="hits" />
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
</style>
