<script setup lang="ts">
import { ref, watch } from 'vue'
import type { AgentQuestion } from '@/api/chat-runs'
const props = defineProps<{ question: AgentQuestion; busy?: boolean; error?: string | null }>()
const emit = defineEmits<{ (e: 'answer', answer: string): void }>()
const draft = ref('')
const submitted = ref(false)
watch(
  () => props.question.id,
  () => {
    draft.value = ''
    submitted.value = false
  },
)
watch(
  () => props.busy,
  (next, previous) => {
    if (!next && previous) submitted.value = false
  },
)
function answer(value: string): void {
  if (!value.trim() || props.busy || submitted.value) return
  submitted.value = true
  emit('answer', value.trim())
}
</script>
<template>
  <section class="user-question" aria-label="助手需要你补充信息">
    <div class="user-question__eyebrow">等待你的回答 · 回答后继续任务</div>
    <h3>{{ question.question }}</h3>
    <div v-if="question.options.length" class="user-question__options">
      <button
        v-for="(option, index) in question.options"
        :key="option.label"
        type="button"
        :disabled="busy || submitted"
        @click="answer(option.label)"
      >
        <span class="user-question__number">{{ index + 1 }}</span
        ><span
          ><strong>{{ option.label }}</strong
          ><small v-if="option.description">{{ option.description }}</small></span
        >
      </button>
    </div>
    <form @submit.prevent="answer(draft)">
      <label :for="`answer-${question.id}`">{{ question.options.length ? '也可以补充自己的想法' : '你的回答' }}</label>
      <el-input
        :id="`answer-${question.id}`"
        v-model="draft"
        type="textarea"
        :rows="2"
        maxlength="4000"
        :disabled="busy || submitted"
        placeholder="输入具体要求…"
      />
      <div class="user-question__footer">
        <span>这只补充任务意图，不会授权危险操作。</span
        ><el-button
          type="primary"
          size="small"
          native-type="submit"
          :disabled="!draft.trim() || busy || submitted"
          :loading="submitted"
          >提交回答</el-button
        >
      </div>
    </form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
  </section>
</template>
<style scoped>
.user-question {
  margin-left: 44px;
  max-width: 680px;
  padding: var(--os-space-4);
  border: 1px solid var(--el-border-color);
  border-radius: var(--os-radius-xl);
  background: var(--el-bg-color);
}
.user-question__eyebrow {
  color: var(--el-color-primary);
  font-size: var(--os-font-xs);
}
h3 {
  margin: var(--os-space-2) 0 var(--os-space-3);
  font-size: var(--os-font-md);
  line-height: 1.6;
}
.user-question__options {
  display: grid;
  gap: var(--os-space-2);
  margin-bottom: var(--os-space-4);
}
.user-question__options button {
  display: flex;
  align-items: flex-start;
  gap: var(--os-space-3);
  padding: var(--os-space-3);
  text-align: left;
  font: inherit;
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--os-radius-lg);
  cursor: pointer;
  min-height: 44px;
}
.user-question__options button:hover:not(:disabled) {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.user-question__number {
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
small {
  display: block;
  color: var(--el-text-color-secondary);
  margin-top: var(--os-space-1);
  line-height: 1.5;
}
label {
  display: block;
  font-size: var(--os-font-sm);
  margin-bottom: var(--os-space-2);
}
.user-question__footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  margin-top: var(--os-space-2);
}
.user-question__footer span {
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}
button:disabled {
  opacity: 0.65;
  cursor: default;
}
button:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
@media (max-width: 600px) {
  .user-question {
    margin-left: 0;
  }
}
</style>
