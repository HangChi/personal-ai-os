<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, nextTick, onMounted, ref, watch } from 'vue'

import { useChatStore } from '@/stores/chat'
import type { ChatRole } from '@/types/api'

const chatStore = useChatStore()
const {
  sessions,
  currentConversationId,
  messages,
  streamingContent,
  streaming,
  loadingMessages,
  error,
} = storeToRefs(chatStore)

const draft = ref('')
const messageArea = ref<HTMLElement | null>(null)

const canSend = computed(() => draft.value.trim().length > 0 && !streaming.value)

const currentTitle = computed(() => {
  const current = sessions.value.find((item) => item.conversationId === currentConversationId.value)
  return current ? current.title : '未选择会话'
})

function roleLabel(role: ChatRole): string {
  switch (role) {
    case 'USER':
      return '我'
    case 'ASSISTANT':
      return '助手'
    default:
      return '系统'
  }
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const element = messageArea.value
  if (element) {
    element.scrollTop = element.scrollHeight
  }
}

watch([() => messages.value.length, streamingContent], () => {
  void scrollToBottom()
})

onMounted(async () => {
  await chatStore.loadSessions()
  const first = sessions.value[0]
  if (!currentConversationId.value && first) {
    await chatStore.selectSession(first.conversationId)
  }
})

async function handleSend(): Promise<void> {
  if (!canSend.value) {
    return
  }
  const content = draft.value
  draft.value = ''
  await chatStore.send(content)
}

async function handleNewSession(): Promise<void> {
  await chatStore.createSession()
}
</script>

<template>
  <div class="chat">
    <el-card class="chat__sessions" shadow="never">
      <template #header>
        <div class="chat__sessions-header">
          <span>会话</span>
          <el-button type="primary" size="small" @click="handleNewSession">新建</el-button>
        </div>
      </template>

      <el-empty v-if="sessions.length === 0" description="还没有会话" :image-size="60" />
      <ul v-else class="chat__session-list">
        <li
          v-for="session in sessions"
          :key="session.conversationId"
          :class="[
            'chat__session',
            { 'chat__session--active': session.conversationId === currentConversationId },
          ]"
          @click="chatStore.selectSession(session.conversationId)"
        >
          <div class="chat__session-body">
            <div class="chat__session-title">{{ session.title }}</div>
            <div class="chat__session-meta">{{ session.messageCount }} 条消息</div>
          </div>
          <el-button
            link
            type="danger"
            size="small"
            @click.stop="chatStore.removeSession(session.conversationId)"
          >
            删除
          </el-button>
        </li>
      </ul>
    </el-card>

    <el-card class="chat__main" shadow="never">
      <template #header>
        <div class="chat__main-header">
          <span class="chat__main-title">{{ currentTitle }}</span>
          <el-tag v-if="streaming" type="warning" size="small">生成中…</el-tag>
        </div>
      </template>

      <el-alert
        v-if="error"
        class="chat__error"
        type="error"
        :closable="false"
        show-icon
        :title="error"
      />

      <div ref="messageArea" v-loading="loadingMessages" class="chat__messages">
        <el-empty
          v-if="messages.length === 0 && !streamingContent"
          description="发一条消息开始对话"
          :image-size="70"
        />

        <div
          v-for="message in messages"
          :key="message.id"
          :class="['chat__message', `chat__message--${message.role.toLowerCase()}`]"
        >
          <div class="chat__message-role">{{ roleLabel(message.role) }}</div>
          <div class="chat__message-content">{{ message.content }}</div>
        </div>

        <div
          v-if="streamingContent"
          class="chat__message chat__message--assistant chat__message--streaming"
        >
          <div class="chat__message-role">助手</div>
          <div class="chat__message-content">{{ streamingContent }}</div>
        </div>
      </div>

      <div class="chat__composer">
        <el-input
          v-model="draft"
          type="textarea"
          :rows="3"
          maxlength="4000"
          show-word-limit
          resize="none"
          placeholder="输入消息；Enter 发送，Shift + Enter 换行"
          @keydown.enter.exact.prevent="handleSend"
        />
        <div class="chat__composer-actions">
          <el-button v-if="streaming" @click="chatStore.stopStreaming()">停止</el-button>
          <el-button type="primary" :disabled="!canSend" :loading="streaming" @click="handleSend">
            发送
          </el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.chat {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 16px;
  align-items: start;
}

.chat__sessions-header,
.chat__main-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chat__main-title {
  font-weight: 600;
}

.chat__session-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.chat__session {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  background: var(--el-fill-color-light);
}

.chat__session:hover {
  background: var(--el-fill-color);
}

.chat__session--active {
  background: var(--el-color-primary-light-9);
  outline: 1px solid var(--el-color-primary-light-5);
}

.chat__session-body {
  min-width: 0;
}

.chat__session-title {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat__session-meta {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.chat__error {
  margin-bottom: 12px;
}

.chat__messages {
  height: 52vh;
  min-height: 280px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-right: 6px;
}

.chat__message {
  max-width: 88%;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}

.chat__message--user {
  align-self: flex-end;
  background: var(--el-color-primary-light-9);
}

.chat__message--assistant {
  align-self: flex-start;
}

.chat__message--streaming {
  border: 1px dashed var(--el-color-warning);
}

.chat__message-role {
  margin-bottom: 4px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.chat__message-content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.65;
}

.chat__composer {
  margin-top: 12px;
}

.chat__composer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

@media (max-width: 900px) {
  .chat {
    grid-template-columns: 1fr;
  }
}
</style>
