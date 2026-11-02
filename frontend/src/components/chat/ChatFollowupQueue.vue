<script setup lang="ts">
import { ref, watch } from 'vue'
import { EditPen, Close } from '@element-plus/icons-vue'
import type { AgentFollowup } from '@/api/chat-runs'
const props = defineProps<{ items: AgentFollowup[]; busy?: boolean; error?: string | null }>()
const emit = defineEmits<{
  (e: 'remove', id: string): void
  (e: 'edit', id: string, content: string, done: (success: boolean) => void): void
  (e: 'steer', id: string): void
}>()
const editingId = ref<string | null>(null)
const draft = ref('')
watch(
  () => props.items,
  (items) => {
    if (!items.some((item) => item.id === editingId.value && item.mode === 'QUEUE')) editingId.value = null
  },
)
function edit(item: AgentFollowup): void {
  editingId.value = item.id
  draft.value = item.request.content
}
function save(id: string): void {
  if (props.busy || !draft.value.trim()) return
  emit('edit', id, draft.value.trim(), (success) => {
    if (success) editingId.value = null
  })
}
</script>
<template>
  <section v-if="items.length" class="followups" aria-label="待处理的跟进消息">
    <div class="followups__title">待处理消息 · {{ items.length }}</div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <ol>
      <li v-for="item in items" :key="item.id">
        <div class="followups__row">
          <span class="followups__mode">{{ item.mode === 'STEER' ? '等待引导当前对话' : '排队等待中' }}</span>
          <span v-if="editingId !== item.id" class="followups__content">{{ item.request.content }}</span>
          <span v-if="item.request.attachments?.length" class="followups__mode"
            >{{ item.request.attachments.length }} 个附件</span
          >
          <div v-if="editingId !== item.id" class="followups__actions">
            <el-button
              v-if="item.mode === 'QUEUE'"
              text
              size="small"
              :disabled="busy"
              class="followups__icon-action"
              title="编辑"
              :aria-label="`编辑排队消息：${item.request.content}`"
              @click="edit(item)"
              ><el-icon aria-hidden="true"><EditPen /></el-icon
            ></el-button>
            <el-button
              v-if="item.mode === 'QUEUE'"
              text
              size="small"
              :disabled="busy"
              :aria-label="`用此消息引导当前对话：${item.request.content}`"
              title="引导当前对话"
              @click="emit('steer', item.id)"
              >引导</el-button
            >
            <el-button
              text
              size="small"
              :disabled="busy"
              class="followups__icon-action"
              title="取消"
              :aria-label="`取消跟进消息：${item.request.content}`"
              @click="emit('remove', item.id)"
              ><el-icon aria-hidden="true"><Close /></el-icon
            ></el-button>
          </div>
        </div>
        <form
          v-if="editingId === item.id"
          :aria-label="`编辑排队消息：${item.request.content}`"
          @submit.prevent="save(item.id)"
        >
          <el-input
            v-model="draft"
            type="textarea"
            :rows="2"
            maxlength="4000"
            aria-label="排队消息内容"
            :disabled="busy"
            @keydown.esc="editingId = null"
          />
          <div class="followups__edit-actions">
            <span class="followups__mode">保存后仍保留排队位置和附件。</span
            ><el-button
              text
              size="small"
              class="followups__icon-action"
              title="取消编辑"
              aria-label="取消编辑"
              :disabled="busy"
              @click="editingId = null"
              ><el-icon aria-hidden="true"><Close /></el-icon></el-button
            ><el-button type="primary" size="small" native-type="submit" :disabled="busy || !draft.trim()"
              >保存修改</el-button
            >
          </div>
        </form>
      </li>
    </ol>
  </section>
</template>
<style scoped>
.followups {
  margin: var(--os-space-2) var(--os-chat-gutter);
  padding: var(--os-space-3);
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--os-radius-lg);
  background: var(--el-fill-color-lighter);
}
.followups__title,
.followups__mode {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}
ol {
  padding: 0;
  margin: var(--os-space-2) 0 0;
  list-style: none;
}
li + li {
  margin-top: var(--os-space-2);
  padding-top: var(--os-space-2);
  border-top: 1px solid var(--el-border-color-lighter);
}
.followups__row,
.followups__actions,
.followups__edit-actions {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  flex-wrap: wrap;
}
.followups__icon-action {
  width: 28px;
  height: 28px;
  padding: 0;
}
.followups__actions {
  margin-left: auto;
}
.followups__content {
  flex: 1;
  min-width: 120px;
  overflow-wrap: anywhere;
  font-size: var(--os-font-sm);
  white-space: pre-wrap;
}
form {
  margin-top: var(--os-space-2);
}
.followups__edit-actions {
  margin-top: var(--os-space-2);
  justify-content: flex-end;
}
.followups__edit-actions > span {
  margin-right: auto;
}
</style>
