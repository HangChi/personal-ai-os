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

import ChatSources from '@/components/chat/ChatSources.vue'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useSettingsStore } from '@/stores/settings'
import { useKnowledgeStore } from '@/stores/knowledge'

const knowledgeStore = useKnowledgeStore()
const { answer, asking, askError } = storeToRefs(knowledgeStore)

const question = ref('')
const askTopK = ref(useSettingsStore().settings.knowledgeTopK)
const claimStatus = { SUPPORTED: '证据支持', PARTIAL: '部分支持', UNSUPPORTED: '证据不足' }

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
      <PageHeader title="基于资料提问" />
    </template>

    <form class="ka__query" @submit.prevent="handleAsk">
      <el-input
        v-model="question"
        aria-label="知识库问题"
        placeholder="基于知识库提问，例如：支持哪些隔离级别？"
        maxlength="500"
      />
      <el-input-number v-model="askTopK" aria-label="检索片段条数" :min="1" :max="20" class="ka__topk" />
      <el-button type="primary" native-type="submit" :loading="asking" :disabled="!question.trim()">提问</el-button>
    </form>

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
        v-if="
          !answer.answered &&
          !answer.citations.length &&
          (!answer.retrieval || answer.retrieval.status === 'INSUFFICIENT')
        "
        type="info"
        :closable="false"
        show-icon
        title="知识库中没有找到相关内容"
      />
      <template v-else>
        <div class="ka__answer">
          <MarkdownRenderer :content="answer.answer" :citations="answer.citations" hide-source-list />
        </div>
        <details v-if="answer.citationAudit" class="ka__citations">
          <summary class="ka__citations-title">
            模型逐条核对（{{
              answer.citationAudit.status === 'CHECKED' ? answer.citationAudit.claims.length + ' 条' : '未完成'
            }}）
          </summary>
          <ul v-if="answer.citationAudit.claims.length">
            <li v-for="check in answer.citationAudit.claims" :key="check.claim">
              <strong>{{ claimStatus[check.status] }}</strong> · {{ check.claim }}
              <blockquote v-if="check.quote">{{ check.quote }}</blockquote>
            </li>
          </ul>
          <p v-else>本轮未获得可核对的结论记录。</p>
        </details>
      </template>
      <ChatSources :citations="answer.citations" :retrieval="answer.retrieval" />
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
  cursor: pointer;
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}
@media (max-width: 600px) {
  .ka__query {
    flex-wrap: wrap;
  }
  .ka__query > :deep(.el-input) {
    flex: 1 1 100%;
  }
  .ka__query > :deep(.el-button) {
    flex: 1;
  }
}
</style>
