<script setup lang="ts">
/**
 * 知识库 · 问答 Tab。
 *
 * <p>零命中时后端不调用模型（`answered=false`），这里必须把这个事实显式说出来：
 * 「知识库中没有找到相关内容」比让模型自由发挥要好得多。
 */
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import { ref } from 'vue'

import KnowledgeHitList from '@/components/knowledge/KnowledgeHitList.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useKnowledgeStore } from '@/stores/knowledge'

const knowledgeStore = useKnowledgeStore()
const { answer, asking, askError } = storeToRefs(knowledgeStore)

const question = ref('')
const askTopK = ref(4)

async function handleAsk(): Promise<void> {
  const text = question.value.trim()
  if (!text) {
    ElMessage.warning('请输入问题')
    return
  }
  await knowledgeStore.ask(text, askTopK.value)
}
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <PageHeader title="问答" description="基于知识库生成回答，附带引用来源" />
    </template>

    <div class="ka__query">
      <el-input
        v-model="question"
        placeholder="基于知识库提问，例如：支持哪些隔离级别？"
        maxlength="500"
        @keydown.enter="handleAsk"
      />
      <el-input-number v-model="askTopK" :min="1" :max="20" class="ka__topk" />
      <el-button type="primary" :loading="asking" @click="handleAsk">提问</el-button>
    </div>

    <!-- 提问失败必须显式提示（审查 #17）：改造前这里什么都不渲染，转圈结束页面纹丝不动，
         用户分不清是「失败」还是「没结果」 -->
    <el-alert
      v-if="askError"
      class="ka__error"
      type="error"
      :closable="false"
      show-icon
      title="问答失败"
      :description="askError"
    />

    <template v-if="answer">
      <el-alert
        v-if="!answer.answered"
        type="info"
        :closable="false"
        show-icon
        title="知识库中没有找到相关内容"
        description="未调用模型生成回答，以避免编造。"
      />
      <template v-else>
        <div class="ka__answer">{{ answer.answer }}</div>
        <div class="ka__citations">
          <div class="ka__citations-title">引用来源（{{ answer.citations.length }}）</div>
          <KnowledgeHitList :items="answer.citations" indexed bordered />
        </div>
      </template>
    </template>
  </el-card>
</template>

<style scoped>
.ka__query {
  display: flex;
  gap: var(--os-space-2);
  margin-bottom: var(--os-space-3);
}

.ka__error {
  margin-bottom: var(--os-space-3);
}

.ka__topk {
  width: 110px;
  flex: none;
}

.ka__answer {
  padding: 10px var(--os-space-3);
  border-radius: var(--os-radius-md);
  background: var(--el-color-primary-light-9);
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
}

.ka__citations {
  margin-top: var(--os-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
}

.ka__citations-title {
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}
</style>
