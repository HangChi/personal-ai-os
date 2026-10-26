<script setup lang="ts">
/**
 * 对话页（阶段 20 重设计；2026-10-26 按职责拆分为子组件）。
 *
 * <p>本文件只保留「编排」：会话与消息来自 store，滚动与键盘行为、
 * 二次确认弹窗在这里；具体的会话列表、消息气泡、流式占位、工具轨迹、
 * 待确认卡片、输入区各自成组件（`components/chat/`）。
 *
 * <p>布局：全高对话列——消息区撑满可滚动，输入区停靠底部（不再有断裂空白带）。
 * 阶段 19 功能全保留：知识库增强（会话级开关）、引用区、工具轨迹、待确认。
 */
import { ElMessage, ElMessageBox } from 'element-plus'
import { storeToRefs } from 'pinia'
import { computed, nextTick, onMounted, ref, watch } from 'vue'

import ChatComposer from '@/components/chat/ChatComposer.vue'
import ChatConfirmationList from '@/components/chat/ChatConfirmationList.vue'
import ChatMessageItem from '@/components/chat/ChatMessageItem.vue'
import ChatSessionList from '@/components/chat/ChatSessionList.vue'
import ChatStreamingItem from '@/components/chat/ChatStreamingItem.vue'
import ChatToolTrail from '@/components/chat/ChatToolTrail.vue'
import ChatWelcome from '@/components/chat/ChatWelcome.vue'
import { useChatStore } from '@/stores/chat'
import { clampTopK, conversationKnowledgeKey } from '@/lib/settings'
import { useSettingsStore } from '@/stores/settings'
import type { ChatSession, TaskConfirmation } from '@/types/api'

const chatStore = useChatStore()
const settingsStore = useSettingsStore()
const {
  sessions,
  sessionsTotal,
  sessionsPage,
  sessionsSize,
  sessionKeyword,
  currentConversationId,
  messages,
  hasOlderMessages,
  loadingOlder,
  streamingContent,
  streaming,
  loadingSessions,
  loadingMessages,
  error,
  pendingConfirmations,
  toolTrail,
  liveCitations,
  retrievalState,
} = storeToRefs(chatStore)
const { settings } = storeToRefs(settingsStore)

const draft = ref('')
const messageArea = ref<HTMLElement | null>(null)
const resolvingId = ref<string | null>(null)

const canSend = computed(() => draft.value.trim().length > 0 && !streaming.value)

const currentTitle = computed(() => {
  const current = sessions.value.find((item) => item.conversationId === currentConversationId.value)
  return current ? current.title : '新对话'
})

/** 空态建议问题：点击只填输入框，不自动发送（用户还要改） */
const suggestions = [
  '支持哪些隔离级别？',
  '帮我创建一个周五前交周报的任务',
  '我的周报什么时候交？',
  '总结一下我知识库里的主要内容',
]

/**
 * 处理待确认操作。
 *
 * 删除类操作额外弹一次二次确认 —— 服务端的确认接口是「人做出的决定」，
 * 这里再确认一次是为了防止误点，而不是替代服务端的确认流程。
 */
async function handleConfirmation(confirmation: TaskConfirmation, approve: boolean): Promise<void> {
  if (approve && confirmation.action === 'DELETE_TASK') {
    try {
      await ElMessageBox.confirm(`确认执行「${confirmation.summary}」？该操作不可撤销。`, '高风险操作确认', {
        type: 'warning',
        confirmButtonText: '确认执行',
        cancelButtonText: '再想想',
      })
    } catch {
      return
    }
  }

  resolvingId.value = confirmation.id
  try {
    const result = await chatStore.resolveConfirmation(confirmation, approve)
    if (result) {
      ElMessage.success(result)
    }
  } finally {
    resolvingId.value = null
  }
}

/** 复制整条消息内容 */
async function copyMessage(content: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(content)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择复制')
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

// ---------------------------------------------------------------- 知识库增强（阶段 19）

/**
 * 当前会话的增强开关。
 *
 * 持久化按会话键存（`os.chat.knowledge.<会话id>`）；新会话（无键）取设置页
 * 的全局默认。刻意不用 watch 写回——切会话时 loadKnowledgePref 会改这个 ref，
 * watch 紧接着把旧会话的值写到新会话的键上（静默串话）。
 */
const knowledgeOn = ref(false)

function loadKnowledgePref(): void {
  const key = conversationKnowledgeKey(currentConversationId.value)
  const stored = key ? localStorage.getItem(key) : null
  knowledgeOn.value = stored === null ? settings.value.knowledgeDefaultOn : stored === 'true'
}

watch(currentConversationId, () => loadKnowledgePref())

function toggleKnowledge(next: boolean): void {
  knowledgeOn.value = next
  const key = conversationKnowledgeKey(currentConversationId.value)
  if (key) {
    localStorage.setItem(key, String(next))
  }
}

/** 检索条数：直接绑定设置页默认值（clamp 在 setter 里，非法输入进不了 store） */
const topK = computed({
  get: () => settings.value.knowledgeTopK,
  set: (value: number | null) => {
    settings.value.knowledgeTopK = clampTopK(value)
  },
})

/** 流式中的检索提示（C-04）：状态机区分「还没查到」与「查了但没有」 */
const retrievalHint = computed(() => {
  if (retrievalState.value === 'searching') {
    return '正在检索知识库…'
  }
  if (retrievalState.value === 'done' && liveCitations.value.length === 0) {
    return '未找到相关依据'
  }
  if (retrievalState.value === 'done') {
    return `已找到 ${liveCitations.value.length} 条依据`
  }
  return null
})

onMounted(async () => {
  loadKnowledgePref()
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
  await chatStore.send(content, {
    useKnowledge: knowledgeOn.value,
    topK: topK.value,
  })
}

async function handleNewSession(): Promise<void> {
  await chatStore.createSession()
}

/**
 * 重命名会话。
 *
 * 用对话框而不是列表内联编辑：列表项本身点击即切换会话，
 * 内联输入框要额外处理点击穿透、失焦保存、Esc 退出三类边界，容易点出「改名字却切走了会话」。
 *
 * 校验口径与后端一致：按 `trim()` 后的长度判定（后端也是先去空白再算长度）。
 */
async function handleRename(session: ChatSession): Promise<void> {
  let newTitle: string
  try {
    const { value } = await ElMessageBox.prompt('输入新的会话标题', '重命名会话', {
      inputValue: session.title,
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValidator: (input: string) => {
        const trimmed = input.trim()
        if (trimmed.length === 0) {
          return '标题不能为空'
        }
        if (trimmed.length > 200) {
          return '标题长度不能超过 200'
        }
        return true
      },
    })
    newTitle = value.trim()
  } catch {
    return
  }

  const success = await chatStore.renameSession(session.conversationId, newTitle)
  if (success) {
    ElMessage.success('已重命名')
  }
}
</script>

<template>
  <div class="chat">
    <!-- ============ 会话列表 ============ -->
    <ChatSessionList
      v-model:keyword="sessionKeyword"
      :sessions="sessions"
      :current-id="currentConversationId"
      :total="sessionsTotal"
      :page="sessionsPage"
      :size="sessionsSize"
      :loading="loadingSessions"
      @refresh="chatStore.loadSessions()"
      @create="handleNewSession"
      @select="chatStore.selectSession($event)"
      @rename="handleRename"
      @remove="chatStore.removeSession($event)"
      @search="chatStore.searchSessions()"
      @page-change="chatStore.goToSessionPage($event)"
    />

    <!-- ============ 主对话列（全高） ============ -->
    <el-card class="chat__main" shadow="never">
      <template #header>
        <div class="chat__main-header">
          <div class="chat__main-heading">
            <span class="chat__main-title" :title="currentTitle">{{ currentTitle }}</span>
            <span class="chat__main-meta">
              {{ hasOlderMessages ? `已加载 ${messages.length} 条` : `${messages.length} 条消息` }}
            </span>
          </div>
          <el-tag v-if="streaming" type="warning" size="small" effect="plain">生成中</el-tag>
        </div>
      </template>

      <div class="chat__body">
        <!-- 消息滚动区 -->
        <div ref="messageArea" v-loading="loadingMessages" class="chat__messages">
          <el-alert v-if="error" class="chat__error" type="error" :closable="false" show-icon :title="error" />

          <!-- 空态：欢迎 + 建议问题 -->
          <ChatWelcome
            v-if="messages.length === 0 && !streamingContent && !loadingMessages"
            :suggestions="suggestions"
            @pick="draft = $event"
          />

          <!-- 更早的消息入口（阶段 21）：首屏只取最近一页，向上翻是显式动作 -->
          <div v-if="hasOlderMessages || loadingOlder" class="chat__load-older">
            <el-button text size="small" :loading="loadingOlder" @click="chatStore.loadOlderMessages()">
              加载更早的消息
            </el-button>
          </div>

          <!-- 消息流 -->
          <ChatMessageItem v-for="message in messages" :key="message.id" :message="message" @copy="copyMessage" />

          <!-- 流式中的助手消息 -->
          <ChatStreamingItem v-if="streaming" :content="streamingContent" :hint="retrievalHint" />

          <!-- 工具调用轨迹（阶段 18/19） -->
          <ChatToolTrail v-if="toolTrail.length > 0" :items="toolTrail" />

          <!-- 待确认操作（阶段 2） -->
          <ChatConfirmationList
            v-if="pendingConfirmations.length > 0"
            :items="pendingConfirmations"
            :resolving-id="resolvingId"
            @resolve="handleConfirmation"
          />
        </div>

        <!-- ============ 停靠输入区 ============ -->
        <ChatComposer
          v-model="draft"
          :streaming="streaming"
          :knowledge-on="knowledgeOn"
          :top-k="topK"
          :enter-send="settings.enterSend"
          @toggle-knowledge="toggleKnowledge"
          @update:top-k="(value: number | null) => (topK = value)"
          @send="handleSend"
          @stop="chatStore.stopStreaming()"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
/*
 * 全高对话列（FR-001）。
 * 156px = 顶栏 60 + main 上距 16 + main 下距 20（EP el-main 默认）+ 页脚 60
 * （2026-10-26 实测；少算那 20px 会让页面多出 20px 滚动）。
 * 改 App.vue 骨架高度时同步改这里。
 */
.chat {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: var(--os-space-4);
  height: calc(100vh - 156px);
  align-items: stretch;
}

/* ---- 主对话卡（全高列） ---- */

.chat__main {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.chat__main :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 0;
}

.chat__main-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-3);
}

.chat__main-heading {
  display: flex;
  align-items: baseline;
  gap: var(--os-space-3);
  min-width: 0;
}

.chat__main-title {
  font-size: var(--os-font-md);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat__main-meta {
  flex: none;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.chat__body {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/* ---- 消息滚动区 ---- */

.chat__messages {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  /* 气泡自带内边距后，20px 的间距显得过散；收到 16px 并保留底部余量 */
  gap: var(--os-space-4);
  padding: var(--os-space-4) var(--os-space-5) var(--os-space-4);
}

.chat__error {
  flex: none;
}

/* 「加载更早」停靠在消息流顶部，居中且不抢视觉（它是可选动作，不是主流程） */
.chat__load-older {
  display: flex;
  justify-content: center;
  padding-bottom: var(--os-space-2);
}

/* ---- 窄屏：单列，高度不强行全高 ---- */

@media (max-width: 900px) {
  .chat {
    grid-template-columns: 1fr;
    height: auto;
  }

  .chat__messages {
    min-height: 50vh;
  }
}
</style>
