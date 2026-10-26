<script setup lang="ts">
/**
 * 对话页（阶段 18 改造）。
 *
 * 本版改动（User Story 3 / 4）：
 * - 消息内容走 MarkdownRenderer：Markdown 渲染 + 代码高亮 + DOMPurify 消毒（C-01）
 * - 气泡加头像 / 相对时间 / 复制按钮
 * - 新增「工具调用轨迹」折叠区：数据来自审计接口的 conversationId 过滤（T014），
 *   消息体与 SSE 帧都不带工具字段，这是不新增接口的唯一真实来源
 * - 会话列表按 今天/昨天/更早 分组 + 相对时间（C-04）
 */
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatLineRound,
  CopyDocument,
  QuestionFilled,
  User,
} from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { computed, nextTick, onMounted, ref, watch } from 'vue'

import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useChatStore } from '@/stores/chat'
import { clampTopK, conversationKnowledgeKey } from '@/lib/settings'
import { useSettingsStore } from '@/stores/settings'
import { groupSessionsByDay } from '@/lib/session-group'
import { relativeTime } from '@/lib/relative-time'
import type { ChatRole, ChatSession, TaskConfirmation } from '@/types/api'

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

// ---------------------------------------------------------------- 知识库增强（阶段 19）

/**
 * 当前会话的增强开关。
 *
 * 持久化按会话键存（`os.chat.knowledge.<会话id>`，C-03）；新会话（无键）
 * 取设置页的全局默认。刻意不用 watch 写回——切会话时 loadKnowledgePref 会
 * 改这个 ref，watch 紧接着把旧会话的值写到新会话的键上（静默串话）。
 * 写回只在 toggleKnowledge 里显式发生。
 */
const knowledgeOn = ref(false)

function loadKnowledgePref(): void {
  const key = conversationKnowledgeKey(currentConversationId.value)
  const stored = key ? localStorage.getItem(key) : null
  knowledgeOn.value = stored === null ? settings.value.knowledgeDefaultOn : stored === 'true'
}

watch(currentConversationId, () => loadKnowledgePref())

function toggleKnowledge(next: string | number | boolean): void {
  knowledgeOn.value = next === true
  const key = conversationKnowledgeKey(currentConversationId.value)
  if (key) {
    localStorage.setItem(key, String(knowledgeOn.value))
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

/**
 * 输入框键盘行为（FR-012 / 设置驱动）：
 * - enterSend（默认）：Enter 发送，Shift+Enter 换行
 * - 关闭时：Enter 换行，Ctrl/Cmd+Enter 发送
 */
function handleComposerKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter') {
    return
  }
  if (event.isComposing) {
    // 中文输入法选词期间的 Enter 不触发发送（否则打不了中文）
    return
  }
  if (settings.value.enterSend) {
    if (!event.shiftKey && !event.ctrlKey && !event.metaKey) {
      event.preventDefault()
      void handleSend()
    }
  } else if (event.ctrlKey || event.metaKey) {
    event.preventDefault()
    void handleSend()
  }
}

const canSend = computed(() => draft.value.trim().length > 0 && !streaming.value)

const currentTitle = computed(() => {
  const current = sessions.value.find((item) => item.conversationId === currentConversationId.value)
  return current ? current.title : '未选择会话'
})

/** 会话分组（C-04）：组内顺序即接口顺序，不重排 */
const sessionGroups = computed(() => groupSessionsByDay(sessions.value))

/** 工具轨迹的结果徽标映射（StatusTag 的 map，未命中回落 info） */
const OUTCOME_MAP: Record<string, { label: string; type: 'success' | 'danger' | 'warning' | 'info' }> = {
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  REJECTED: { label: '被拒绝', type: 'warning' },
  REQUIRES_CONFIRMATION: { label: '待确认', type: 'warning' },
  DUPLICATE: { label: '已去重', type: 'info' },
  IN_PROGRESS: { label: '执行中', type: 'info' },
}

/** 高风险操作的中文名；未识别时直接展示原始动作码，避免"未知操作"这类无用提示 */
function actionLabel(action: string): string {
  switch (action) {
    case 'DELETE_TASK':
      return '删除任务'
    case 'SCHEDULE_TASK':
      return '修改截止时间'
    case 'SET_PRIORITY':
      return '修改优先级'
    case 'DELETE_CALENDAR_EVENT':
      return '删除日程'
    case 'MCP_TOOL_CALL':
      // server 名与工具名在 summary 里（服务端生成），标签只负责说明"这是外部工具调用"
      return '调用外部工具'
    default:
      return action
  }
}

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

/** 复制整条消息内容 */
async function copyMessage(content: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(content)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择复制')
  }
}

/** 工具耗时可读化：null 表示未真正执行（待确认），与 0ms 区分 */
function formatDuration(durationMs: number | null): string {
  return durationMs === null ? '—' : `${durationMs} ms`
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
    // 取消：不发起请求，无副作用
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
    <el-card class="chat__sessions" shadow="never">
      <template #header>
        <div class="chat__sessions-header">
          <span>会话</span>
          <div class="chat__sessions-actions">
            <el-button size="small" :loading="loadingSessions" @click="chatStore.loadSessions()">
              刷新
            </el-button>
            <el-button type="primary" size="small" @click="handleNewSession">新建</el-button>
          </div>
        </div>
        <el-input
          v-model="sessionKeyword"
          class="chat__sessions-filter"
          clearable
          size="small"
          placeholder="按标题搜索会话"
          @keydown.enter="chatStore.searchSessions()"
          @clear="chatStore.searchSessions()"
        />
      </template>

      <!-- 空态区分「一个都没有」与「筛掉了」，否则筛完看起来像历史被清空了 -->
      <el-empty
        v-if="sessions.length === 0"
        :description="sessionKeyword ? '没有匹配的会话' : '还没有会话'"
        :image-size="60"
      />

      <!-- 分组渲染：今天 / 昨天 / 更早（C-04），空组不出现 -->
      <div v-else class="chat__session-groups">
        <section v-for="group in sessionGroups" :key="group.key" class="chat__session-group">
          <div class="chat__session-group-label">{{ group.label }}</div>
          <ul class="chat__session-list">
            <li
              v-for="session in group.items"
              :key="session.conversationId"
              :class="[
                'chat__session',
                { 'chat__session--active': session.conversationId === currentConversationId },
              ]"
              @click="chatStore.selectSession(session.conversationId)"
            >
              <div class="chat__session-body">
                <div class="chat__session-title">{{ session.title }}</div>
                <div class="chat__session-meta">
                  {{ session.messageCount }} 条消息 · {{ relativeTime(session.updatedAt) }}
                </div>
              </div>
              <div class="chat__session-actions">
                <el-button link type="primary" size="small" @click.stop="handleRename(session)">
                  重命名
                </el-button>
                <el-button
                  link
                  type="danger"
                  size="small"
                  @click.stop="chatStore.removeSession(session.conversationId)"
                >
                  删除
                </el-button>
              </div>
            </li>
          </ul>
        </section>
      </div>

      <div v-if="sessionsTotal > 0" class="chat__sessions-pagination">
        <span class="chat__sessions-total">共 {{ sessionsTotal }} 个</span>
        <el-pagination
          layout="prev, pager, next"
          small
          background
          :total="sessionsTotal"
          :current-page="sessionsPage + 1"
          :page-size="sessionsSize"
          @current-change="(next: number) => chatStore.goToSessionPage(next - 1)"
        />
      </div>
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
          <div class="chat__message-avatar" :class="`chat__message-avatar--${message.role.toLowerCase()}`">
            <el-icon :size="14">
              <User v-if="message.role === 'USER'" />
              <ChatLineRound v-else />
            </el-icon>
          </div>
          <div class="chat__message-bubble">
            <div class="chat__message-meta">
              <span class="chat__message-role">{{ roleLabel(message.role) }}</span>
              <span class="chat__message-time">{{ relativeTime(message.createdAt) }}</span>
              <el-button
                class="chat__message-copy"
                link
                type="primary"
                size="small"
                title="复制这条消息"
                @click="copyMessage(message.content)"
              >
                <el-icon :size="12"><CopyDocument /></el-icon>
              </el-button>
            </div>
            <MarkdownRenderer class="chat__message-content" :content="message.content" />
            <!-- 知识库引用区（阶段 19，C-04）：默认折叠，>3 条时收起 -->
            <div v-if="message.citations.length > 0" class="chat__citations">
              <el-collapse>
                <el-collapse-item>
                  <template #title>
                    <span class="chat__citations-title">引用来源（{{ message.citations.length }}）</span>
                  </template>
                  <div
                    v-for="citation in message.citations"
                    :key="citation.index"
                    class="chat__citation"
                  >
                    <div class="chat__citation-head">
                      <span class="chat__citation-source">
                        [{{ citation.index }}] {{ citation.documentTitle }}
                        <span class="chat__citation-meta">
                          {{ citation.filename }} · #{{ citation.chunkIndex + 1 }}
                        </span>
                      </span>
                    </div>
                    <div class="chat__citation-content">{{ citation.content }}</div>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </div>
          </div>
        </div>

        <div
          v-if="streamingContent"
          class="chat__message chat__message--assistant chat__message--streaming"
        >
          <div class="chat__message-avatar chat__message-avatar--assistant">
            <el-icon :size="14"><ChatLineRound /></el-icon>
          </div>
          <div class="chat__message-bubble">
            <div class="chat__message-meta">
              <span class="chat__message-role">助手</span>
              <!-- 检索状态机提示（C-04）：检索中 / 已找到 N 条 / 未找到 -->
              <span v-if="retrievalHint" class="chat__message-time chat__message-time--status">
                {{ retrievalHint }}
              </span>
              <span v-else class="chat__message-time">正在输入</span>
            </div>
            <MarkdownRenderer :content="streamingContent" streaming />
          </div>
        </div>
      </div>

      <!-- 工具调用轨迹（T014）：审计接口按会话过滤的真实记录，默认折叠 -->
      <el-collapse v-if="toolTrail.length > 0" class="chat__trail">
        <el-collapse-item>
          <template #title>
            <span class="chat__trail-title">
              本会话工具调用（{{ toolTrail.length }}）
              <el-tooltip content="来自审计记录，展示助手在这个会话里真实调用过的工具" placement="top">
                <el-icon class="chat__trail-help"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <ul class="chat__trail-list">
            <li v-for="item in toolTrail" :key="item.id" class="chat__trail-item">
              <span class="chat__trail-operation">{{ item.operation }}</span>
              <StatusTag :status="item.outcome" :map="OUTCOME_MAP" />
              <span class="chat__trail-duration">{{ formatDuration(item.durationMs) }}</span>
              <span class="chat__trail-time">{{ relativeTime(item.createdAt) }}</span>
            </li>
          </ul>
        </el-collapse-item>
      </el-collapse>

      <div class="chat__confirmations" v-if="pendingConfirmations.length > 0">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="助手申请执行高风险操作，需要你确认后才会真正生效"
        />
        <div
          v-for="confirmation in pendingConfirmations"
          :key="confirmation.id"
          class="chat__confirmation"
        >
          <div class="chat__confirmation-body">
            <el-tag type="warning" size="small" effect="plain">
              {{ actionLabel(confirmation.action) }}
            </el-tag>
            <span class="chat__confirmation-summary">{{ confirmation.summary }}</span>
            <span class="chat__confirmation-meta">影响 {{ confirmation.affectedCount }} 项</span>
          </div>
          <div class="chat__confirmation-actions">
            <el-button
              size="small"
              :loading="resolvingId === confirmation.id"
              @click="handleConfirmation(confirmation, false)"
            >
              取消
            </el-button>
            <el-button
              size="small"
              type="danger"
              :loading="resolvingId === confirmation.id"
              @click="handleConfirmation(confirmation, true)"
            >
              确认执行
            </el-button>
          </div>
        </div>
      </div>

      <!-- 知识库增强控制条（阶段 19）：开关 + 检索条数，常驻不闪烁 -->
      <div class="chat__knowledge">
        <div class="chat__knowledge-toggle">
          <el-switch :model-value="knowledgeOn" @change="toggleKnowledge" />
          <span class="chat__knowledge-label">知识库增强</span>
          <el-tooltip content="开启后每条消息先检索知识库，助手基于检索结果回答并标注 [n] 引用" placement="top">
            <el-icon class="chat__knowledge-help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </div>
        <div v-if="knowledgeOn" class="chat__knowledge-topk">
          <span class="chat__knowledge-label">检索条数</span>
          <el-input-number v-model="topK" :min="1" :max="20" size="small" />
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
          :placeholder="
            settings.enterSend
              ? '输入消息；Enter 发送，Shift + Enter 换行'
              : '输入消息；Ctrl + Enter 发送，Enter 换行'
          "
          @keydown="handleComposerKeydown"
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
  gap: var(--os-space-4);
  align-items: start;
}

.chat__sessions-header,
.chat__main-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chat__sessions-actions {
  display: flex;
  align-items: center;
  gap: var(--os-space-1);
}

.chat__sessions-filter {
  margin-top: var(--os-space-2);
}

.chat__sessions-pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--os-space-2);
  margin-top: var(--os-space-2);
}

.chat__sessions-total {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.chat__main-title {
  font-weight: 600;
}

/* ---- 会话分组（阶段 18）---- */

.chat__session-groups {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-3);
}

.chat__session-group-label {
  margin-bottom: var(--os-space-1);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.chat__session-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--os-space-1);
}

.chat__session {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-2);
  padding: var(--os-space-2) 10px;
  border-radius: var(--os-radius-md);
  cursor: pointer;
  background: var(--el-fill-color-light);
  /* 操作区绝对定位，让标题能占满宽度（否则两个文字按钮会把标题压成几个字） */
  position: relative;
}

.chat__session:hover {
  background: var(--el-fill-color);
}

.chat__session--active {
  background: var(--el-color-primary-light-9);
  outline: 1px solid var(--el-color-primary-light-5);
}

.chat__session-actions {
  position: absolute;
  right: var(--os-space-2);
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  gap: var(--os-space-1);
  padding-left: var(--os-space-3);
  /* 用父项的背景遮住标题尾部，避免按钮与文字叠在一起 */
  background: inherit;
  opacity: 0;
  transition: opacity var(--os-duration-fast) var(--os-ease);
}

.chat__session:hover .chat__session-actions,
.chat__session:focus-within .chat__session-actions {
  opacity: 1;
}

/*
 * 触摸设备没有 hover：常显并回到流内布局。
 * 代价是标题可用宽度变窄，但「看不到、也点不到入口」比标题窄严重得多。
 */
@media (hover: none) {
  .chat__session-actions {
    position: static;
    transform: none;
    opacity: 1;
    background: transparent;
    padding-left: 0;
  }
}

.chat__session-body {
  min-width: 0;
  flex: 1;
}

.chat__session-title {
  font-size: var(--os-font-xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat__session-meta {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.chat__error {
  margin-bottom: var(--os-space-3);
}

/* ---- 消息气泡（阶段 18：头像 / 元信息行 / Markdown）---- */

.chat__messages {
  height: 52vh;
  min-height: 280px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--os-space-3);
  padding-right: var(--os-space-1);
}

.chat__message {
  display: flex;
  align-items: flex-start;
  gap: var(--os-space-2);
  max-width: 88%;
}

.chat__message--user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.chat__message--assistant {
  align-self: flex-start;
}

.chat__message-avatar {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  margin-top: 18px;
  border-radius: 50%;
  background: var(--el-fill-color);
  color: var(--el-text-color-regular);
}

.chat__message-avatar--user {
  background: var(--el-color-primary);
  color: var(--el-color-white);
}

.chat__message-avatar--assistant {
  background: var(--el-color-primary-light-8);
  color: var(--el-color-primary);
}

.chat__message-bubble {
  min-width: 0;
  padding: var(--os-space-2) var(--os-space-3);
  border-radius: var(--os-radius-lg);
  background: var(--el-fill-color-light);
}

.chat__message--user .chat__message-bubble {
  background: var(--el-color-primary-light-9);
}

.chat__message--streaming .chat__message-bubble {
  border: 1px dashed var(--el-color-warning);
}

.chat__message-meta {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  margin-bottom: var(--os-space-1);
}

.chat__message-role {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.chat__message-time {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}

.chat__message-copy {
  margin-left: auto;
  opacity: 0;
  transition: opacity var(--os-duration-fast) var(--os-ease);
}

.chat__message:hover .chat__message-copy,
.chat__message:focus-within .chat__message-copy {
  opacity: 1;
}

/* ---- 工具调用轨迹（T014）---- */

.chat__trail {
  margin-top: var(--os-space-3);
  border-top: 1px solid var(--el-border-color-lighter);
}

.chat__trail-title {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-1);
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}

.chat__trail-help {
  cursor: help;
}

.chat__trail-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--os-space-1);
}

.chat__trail-item {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  font-size: var(--os-font-xs);
}

.chat__trail-operation {
  font-family: 'SFMono-Regular', Consolas, monospace;
  color: var(--el-text-color-regular);
}

.chat__trail-duration,
.chat__trail-time {
  color: var(--el-text-color-secondary);
}

.chat__trail-time {
  margin-left: auto;
}

.chat__composer {
  margin-top: var(--os-space-3);
}

/* ---- 知识库增强控制条（阶段 19）---- */

.chat__knowledge {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-4);
  margin-top: var(--os-space-3);
  padding: var(--os-space-2) var(--os-space-3);
  border-radius: var(--os-radius-md);
  background: var(--el-fill-color-lighter);
}

.chat__knowledge-toggle,
.chat__knowledge-topk {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
}

.chat__knowledge-label {
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
}

.chat__knowledge-help {
  cursor: help;
  color: var(--el-text-color-secondary);
}

/* ---- 消息引用区（阶段 19，C-04）---- */

.chat__citations {
  margin-top: var(--os-space-2);
  border-top: 1px dashed var(--el-border-color-lighter);
}

.chat__citations-title {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.chat__citation {
  padding: var(--os-space-1) 0 var(--os-space-1) 10px;
  border-left: 3px solid var(--el-color-primary-light-5);
}

.chat__citation + .chat__citation {
  margin-top: var(--os-space-2);
}

.chat__citation-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-2);
}

.chat__citation-source {
  font-size: 11px;
  font-weight: 600;
}

.chat__citation-meta {
  margin-left: var(--os-space-1);
  color: var(--el-text-color-secondary);
  font-weight: 400;
}

.chat__citation-content {
  margin-top: 2px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1.5;
  /* 片段可能很长：默认限高，避免把消息流撑爆 */
  max-height: 120px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

.chat__message-time--status {
  color: var(--el-color-primary);
}

.chat__confirmations {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
  margin-top: var(--os-space-3);
}

.chat__confirmation {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-3);
  padding: 10px var(--os-space-3);
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: var(--os-radius-md);
  background: var(--el-color-warning-light-9);
}

.chat__confirmation-body {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  min-width: 0;
}

.chat__confirmation-summary {
  font-size: var(--os-font-xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat__confirmation-meta {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  white-space: nowrap;
}

.chat__confirmation-actions {
  flex: none;
}

.chat__composer-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--os-space-2);
  margin-top: var(--os-space-2);
}

@media (max-width: 900px) {
  .chat {
    grid-template-columns: 1fr;
  }
}
</style>
