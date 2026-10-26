<script setup lang="ts">
/**
 * 对话页（阶段 20 重设计）。
 *
 * 布局：全高对话列——消息区撑满可滚动，输入区停靠底部（不再有断裂空白带）。
 * 消息：助手消息去气泡（头像 + 内容直排），用户消息右对齐主色气泡；
 *       操作（复制 / 时间）悬停浮现。流式：首 token 前三点打字动画。
 * 输入区：工具行（知识库增强 chip + Enter 行为提示）+ 停靠 composer。
 * 空态：欢迎语 + 建议问题 chips。
 *
 * 阶段 19 功能全保留：知识库增强（会话级开关）、引用区、工具轨迹、待确认。
 */
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatLineRound, CopyDocument, QuestionFilled, User } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { computed, nextTick, onMounted, ref, watch } from 'vue'

import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useChatStore } from '@/stores/chat'
import { clampTopK, conversationKnowledgeKey } from '@/lib/settings'
import { useSettingsStore } from '@/stores/settings'
import { groupSessionsByDay } from '@/lib/session-group'
import { relativeTime } from '@/lib/relative-time'
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

/** 会话分组（C-04）：组内顺序即接口顺序，不重排 */
const sessionGroups = computed(() => groupSessionsByDay(sessions.value))

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

/** 工具调用结果徽标映射（StatusTag 的 map） */
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

/** 复制整条消息内容 */
async function copyMessage(content: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(content)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择复制')
  }
}

/** 工具耗时可读化：null 表示未真正执行（待确认），与 0ms 区分开 */
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

/**
 * 输入框键盘行为：
 * - enterSend（默认）：Enter 发送，Shift+Enter 换行
 * - 关闭时：Enter 换行，Ctrl/Cmd+Enter 发送
 */
function handleComposerKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.isComposing) {
    // isComposing：中文输入法选词期间的 Enter 不触发发送（否则打不了中文）
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
    <el-card class="chat__sessions" shadow="never">
      <template #header>
        <div class="chat__sessions-header">
          <span class="chat__sessions-title">会话</span>
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

      <el-empty
        v-if="sessions.length === 0"
        :description="sessionKeyword ? '没有匹配的会话' : '还没有会话'"
        :image-size="60"
      />

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
                  {{ session.messageCount }} 条 · {{ relativeTime(session.updatedAt) }}
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

    <!-- ============ 主对话列（全高） ============ -->
    <el-card class="chat__main" shadow="never">
      <template #header>
        <div class="chat__main-header">
          <div class="chat__main-heading">
            <span class="chat__main-title" :title="currentTitle">{{ currentTitle }}</span>
            <span class="chat__main-meta">{{ messages.length }} 条消息</span>
          </div>
          <el-tag v-if="streaming" type="warning" size="small" effect="plain">生成中</el-tag>
        </div>
      </template>

      <div class="chat__body">
        <!-- 消息滚动区 -->
        <div ref="messageArea" v-loading="loadingMessages" class="chat__messages">
          <el-alert
            v-if="error"
            class="chat__error"
            type="error"
            :closable="false"
            show-icon
            :title="error"
          />

          <!-- 空态：欢迎 + 建议问题 -->
          <div v-if="messages.length === 0 && !streamingContent && !loadingMessages" class="chat__welcome">
            <span class="chat__welcome-badge">AI</span>
            <p class="chat__welcome-title">有什么可以帮你？</p>
            <p class="chat__welcome-hint">我可以操作任务与日程、检索你的知识库、记住你说的事</p>
            <div class="chat__welcome-chips">
              <button
                v-for="item in suggestions"
                :key="item"
                type="button"
                class="chat__suggestion"
                @click="draft = item"
              >
                {{ item }}
              </button>
            </div>
          </div>

          <!-- 消息流 -->
          <div
            v-for="message in messages"
            :key="message.id"
            :class="['chat__msg', `chat__msg--${message.role.toLowerCase()}`]"
          >
            <!-- 助手：头像在左，内容直排 -->
            <template v-if="message.role !== 'USER'">
              <span class="chat__msg-avatar chat__msg-avatar--assistant">
                <el-icon :size="14"><ChatLineRound /></el-icon>
              </span>
              <div class="chat__msg-content">
                <MarkdownRenderer :content="message.content" />
                <!-- 引用区（阶段 19） -->
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
                <!-- 悬停操作行 -->
                <div class="chat__msg-actions">
                  <span class="chat__msg-time">{{ relativeTime(message.createdAt) }}</span>
                  <button type="button" class="chat__msg-action" @click="copyMessage(message.content)">
                    <el-icon :size="11"><CopyDocument /></el-icon>
                    复制
                  </button>
                </div>
              </div>
            </template>

            <!-- 用户：右侧主色气泡 -->
            <template v-else>
              <div class="chat__msg-content chat__msg-content--user">
                <div class="chat__msg-bubble">
                  <MarkdownRenderer :content="message.content" />
                </div>
                <div class="chat__msg-actions chat__msg-actions--user">
                  <span class="chat__msg-time">{{ relativeTime(message.createdAt) }}</span>
                  <button type="button" class="chat__msg-action" @click="copyMessage(message.content)">
                    <el-icon :size="11"><CopyDocument /></el-icon>
                    复制
                  </button>
                </div>
              </div>
              <span class="chat__msg-avatar chat__msg-avatar--user">
                <el-icon :size="14"><User /></el-icon>
              </span>
            </template>
          </div>

          <!-- 流式中的助手消息 -->
          <div v-if="streaming" class="chat__msg chat__msg--assistant">
            <span class="chat__msg-avatar chat__msg-avatar--assistant">
              <el-icon :size="14"><ChatLineRound /></el-icon>
            </span>
            <div class="chat__msg-content">
              <!-- 首 token 前：三点打字动画（检索中附状态文字） -->
              <div v-if="!streamingContent" class="chat__typing">
                <span class="chat__typing-dot" />
                <span class="chat__typing-dot" />
                <span class="chat__typing-dot" />
                <span v-if="retrievalHint" class="chat__typing-text">{{ retrievalHint }}</span>
              </div>
              <template v-else>
                <MarkdownRenderer :content="streamingContent" streaming />
                <div v-if="retrievalHint" class="chat__typing-text chat__typing-text--below">
                  {{ retrievalHint }}
                </div>
              </template>
            </div>
          </div>

          <!-- 工具调用轨迹（阶段 18/19） -->
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

          <!-- 待确认操作（阶段 2） -->
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
        </div>

        <!-- ============ 停靠输入区 ============ -->
        <div class="chat__dock">
          <div class="chat__dock-toolbar">
            <button
              type="button"
              class="chat__dock-chip"
              :class="{ 'chat__dock-chip--on': knowledgeOn }"
              :title="knowledgeOn ? '点击关闭知识库增强' : '开启后每条消息先检索知识库再回答'"
              @click="toggleKnowledge(!knowledgeOn)"
            >
              <span class="chat__dock-chip-dot" />
              知识库增强
              <template v-if="knowledgeOn">· {{ topK }} 条</template>
            </button>
            <span v-if="knowledgeOn" class="chat__dock-topk">
              条数
              <el-input-number v-model="topK" :min="1" :max="20" size="small" controls-position="right" />
            </span>
            <span class="chat__dock-hint">
              {{ settings.enterSend ? 'Enter 发送 · Shift+Enter 换行' : 'Ctrl+Enter 发送 · Enter 换行' }}
            </span>
          </div>
          <div class="chat__composer">
            <el-input
              v-model="draft"
              type="textarea"
              :rows="3"
              maxlength="4000"
              resize="none"
              :placeholder="knowledgeOn ? '输入消息，将基于知识库回答' : '输入消息'"
              @keydown="handleComposerKeydown"
            />
            <div class="chat__composer-actions">
              <span class="chat__composer-count">{{ draft.length }} / 4000</span>
              <el-button v-if="streaming" @click="chatStore.stopStreaming()">停止</el-button>
              <el-button v-else type="primary" :disabled="!canSend" @click="handleSend">
                发送
              </el-button>
            </div>
          </div>
        </div>
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

/* ---- 会话列表卡（全高，内部滚动） ---- */

.chat__sessions {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.chat__sessions :deep(.el-card__body) {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  padding: var(--os-space-3);
}

.chat__sessions-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chat__sessions-title {
  font-size: var(--os-font-sm);
  font-weight: 500;
}

.chat__sessions-actions {
  display: flex;
  align-items: center;
  gap: var(--os-space-1);
}

.chat__sessions-filter {
  margin-top: var(--os-space-2);
}

.chat__session-groups {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-4);
}

.chat__session-group-label {
  margin-bottom: var(--os-space-1);
  padding: 0 var(--os-space-1);
  color: var(--el-text-color-placeholder);
  font-size: 11px;
}

.chat__session-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.chat__session {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-2);
  padding: var(--os-space-2) 10px;
  border-radius: var(--os-radius-md);
  cursor: pointer;
  transition: background var(--os-duration-fast) var(--os-ease);
}

.chat__session:hover {
  background: var(--el-fill-color);
}

/* 当前会话：主色左边条 + 浅底（阶段 20，FR-009） */
.chat__session--active {
  background: var(--el-color-primary-light-9);
}

.chat__session--active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 20%;
  bottom: 20%;
  width: 3px;
  border-radius: 2px;
  background: var(--el-color-primary);
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
  background: inherit;
  opacity: 0;
  transition: opacity var(--os-duration-fast) var(--os-ease);
}

.chat__session:hover .chat__session-actions,
.chat__session:focus-within .chat__session-actions {
  opacity: 1;
}

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
  margin-top: 1px;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
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
  font-size: 11px;
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
  font-size: var(--os-font-sm);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat__main-meta {
  flex: none;
  color: var(--el-text-color-placeholder);
  font-size: 11px;
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
  gap: var(--os-space-5);
  padding: var(--os-space-5) var(--os-space-5) var(--os-space-3);
}

.chat__error {
  flex: none;
}

/* ---- 空态欢迎 ---- */

.chat__welcome {
  margin: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: var(--os-space-5);
}

.chat__welcome-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--os-radius-lg);
  background: var(--el-color-primary);
  color: var(--el-color-white);
  font-size: var(--os-font-md);
  font-weight: 500;
}

.chat__welcome-title {
  margin: var(--os-space-3) 0 0;
  font-size: var(--os-font-lg);
  font-weight: 500;
}

.chat__welcome-hint {
  margin: var(--os-space-1) 0 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.chat__welcome-chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--os-space-2);
  margin-top: var(--os-space-4);
}

.chat__suggestion {
  padding: 6px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 999px;
  background: var(--el-bg-color);
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
  cursor: pointer;
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    color var(--os-duration-fast) var(--os-ease);
}

.chat__suggestion:hover {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}

/* ---- 消息：助手（无气泡） / 用户（主色气泡） ---- */

.chat__msg {
  display: flex;
  align-items: flex-start;
  gap: var(--os-space-3);
}

.chat__msg--user {
  justify-content: flex-end;
}

.chat__msg-avatar {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin-top: 2px;
  border-radius: 50%;
}

.chat__msg-avatar--assistant {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.chat__msg-avatar--user {
  background: var(--el-color-primary);
  color: var(--el-color-white);
}

.chat__msg-content {
  min-width: 0;
  flex: 1;
  font-size: var(--os-font-sm);
  line-height: 1.75;
}

.chat__msg-content--user {
  flex: 0 1 auto;
  max-width: 75%;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.chat__msg-bubble {
  padding: var(--os-space-2) var(--os-space-3);
  border-radius: var(--os-radius-lg);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.chat__msg-bubble :deep(.md__body) {
  font-size: var(--os-font-sm);
  line-height: 1.7;
}

/* 悬停操作行：默认隐形，不抢版面（FR-003） */
.chat__msg-actions {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  margin-top: var(--os-space-1);
  opacity: 0;
  transition: opacity var(--os-duration-fast) var(--os-ease);
}

.chat__msg-actions--user {
  justify-content: flex-end;
}

.chat__msg:hover .chat__msg-actions,
.chat__msg:focus-within .chat__msg-actions {
  opacity: 1;
}

@media (hover: none) {
  .chat__msg-actions {
    opacity: 1;
  }
}

.chat__msg-time {
  color: var(--el-text-color-placeholder);
  font-size: 11px;
}

.chat__msg-action {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0;
  border: none;
  background: none;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  cursor: pointer;
}

.chat__msg-action:hover {
  color: var(--el-color-primary);
}

/* ---- 流式打字动画（阶段 20，FR-004） ---- */

.chat__typing {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: var(--os-space-2) 0;
}

.chat__typing-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-text-color-placeholder);
  animation: chat-typing-bounce 1.2s infinite ease-in-out;
}

.chat__typing-dot:nth-child(2) {
  animation-delay: 0.15s;
}

.chat__typing-dot:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes chat-typing-bounce {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  30% {
    transform: translateY(-4px);
    opacity: 1;
  }
}

.chat__typing-text {
  margin-left: var(--os-space-2);
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.chat__typing-text--below {
  margin: var(--os-space-1) 0 0;
}

/* ---- 引用区（阶段 19，随新布局收紧） ---- */

.chat__citations {
  margin-top: var(--os-space-3);
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
  font-weight: 500;
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
  max-height: 120px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---- 工具轨迹 ---- */

.chat__trail {
  margin-top: var(--os-space-3);
  border-top: 1px solid var(--el-border-color-lighter);
}

.chat__trail-title {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-1);
  font-size: 11px;
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
  flex-wrap: wrap;
  gap: var(--os-space-2);
  font-size: 11px;
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

/* ---- 待确认 ---- */

.chat__confirmations {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
}

.chat__confirmation {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-3);
  padding: var(--os-space-2) var(--os-space-3);
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
  font-size: 11px;
  white-space: nowrap;
}

.chat__confirmation-actions {
  flex: none;
}

/* ---- 停靠输入区 ---- */

.chat__dock {
  flex: none;
  padding: var(--os-space-3) var(--os-space-5) var(--os-space-4);
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

.chat__dock-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-3);
  margin-bottom: var(--os-space-2);
}

.chat__dock-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 999px;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font-size: 11px;
  cursor: pointer;
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    color var(--os-duration-fast) var(--os-ease);
}

.chat__dock-chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-text-color-placeholder);
}

.chat__dock-chip--on {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}

.chat__dock-chip--on .chat__dock-chip-dot {
  background: var(--el-color-primary);
}

.chat__dock-topk {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.chat__dock-topk :deep(.el-input-number) {
  width: 76px;
}

.chat__dock-hint {
  margin-left: auto;
  color: var(--el-text-color-placeholder);
  font-size: 11px;
}

.chat__composer-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--os-space-3);
  margin-top: var(--os-space-2);
}

.chat__composer-count {
  margin-right: auto;
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

/* ---- 窄屏：单列，高度不强行全高 ---- */

@media (max-width: 900px) {
  .chat {
    grid-template-columns: 1fr;
    height: auto;
  }

  .chat__sessions {
    max-height: 300px;
  }

  .chat__messages {
    min-height: 50vh;
  }

  .chat__msg-content--user {
    max-width: 85%;
  }
}

/* 尊重系统「减少动态效果」偏好 */
@media (prefers-reduced-motion: reduce) {
  .chat__typing-dot {
    animation: none;
  }
}
</style>
