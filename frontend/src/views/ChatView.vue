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

import { downloadSessionMarkdown } from '@/api/chat'
import { matchMessageIds, nextMatchCursor } from '@/lib/chat-search'
import { confirmDanger } from '@/lib/confirm-box'
import ChatComposer from '@/components/chat/ChatComposer.vue'
import ChatConfirmationList from '@/components/chat/ChatConfirmationList.vue'
import ChatMessageItem from '@/components/chat/ChatMessageItem.vue'
import ChatModelPicker from '@/components/chat/ChatModelPicker.vue'
import ChatSessionList from '@/components/chat/ChatSessionList.vue'
import ChatStreamingItem from '@/components/chat/ChatStreamingItem.vue'
import ChatWelcome from '@/components/chat/ChatWelcome.vue'
import { useChatStore } from '@/stores/chat'
import { clampTopK, conversationKnowledgeKey, type ThinkingLevel } from '@/lib/settings'
import { defaultChatModel } from '@/lib/model-profile'
import { useModelProfileStore } from '@/stores/model-profile'
import { useSettingsStore } from '@/stores/settings'
import type { ChatAttachment, ChatMessage, ChatSession, TaskConfirmation } from '@/types/api'

const chatStore = useChatStore()
const settingsStore = useSettingsStore()
const modelProfileStore = useModelProfileStore()
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
  contextUsage,
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

/**
 * 是否「最后一条助手消息」—— 工具轨迹只挂在这一条上。
 *
 * 审计记录只有会话维度、没有消息维度，无法精确知道哪次调用属于哪条消息；
 * 挂在最新一条助手消息下是最接近事实的做法（用户刚看到的回复就是它产生的）。
 */
function isLastAssistant(index: number): boolean {
  if (index !== messages.value.length - 1) {
    return false
  }
  return messages.value[index]?.role === 'ASSISTANT'
}

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
    // 删除类走危险确认；「再想想」比「取消」更能表达「这不是拒绝执行，只是先别动」
    const ok = await confirmDanger({
      title: '高风险操作确认',
      message: `确认执行「${confirmation.summary}」？该操作不可撤销。`,
      confirmText: '确认执行',
      cancelText: '再想想',
    })
    if (!ok) {
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

/**
 * 「尾部消息的身份」：最后一条消息的 id + 内容长度。
 *
 * <p>用它而不是 `messages.length` 作为滚底触发源（审查 #15）：
 * 「加载更早」是**前插**，`length` 也会 +50，于是用户刚点「加载更早」就被甩回最底部，
 * 那个功能实际上不可用。而前插不改变**最后一条**消息，用尾部身份作 key，
 * 只有「追加 / 切会话 / 删尾部」这些真正把尾部顶走的情况才滚底。
 */
const lastMessageKey = computed(() => {
  const last = messages.value[messages.value.length - 1]
  return last ? `${last.id}:${last.content.length}` : 'empty'
})

/** 是否贴近底部（距离底部 80px 内算「在底部」） */
function isNearBottom(): boolean {
  const element = messageArea.value
  if (!element) {
    return true
  }
  return element.scrollHeight - element.scrollTop - element.clientHeight < 80
}

/**
 * 自动滚底：只有「原本就在底部附近」时才滚。
 *
 * <p>用户上滑回看历史时，流式每帧都会触发 watch —— 若无条件滚底，用户会被
 * 一次一次拽回最底部，根本没法读。判断「是否在底部」必须在 DOM 更新**之前**做：
 * 更新后 scrollHeight 已经变大，`scrollHeight - scrollTop - clientHeight` 会失真。
 */
async function autoScrollToBottom(): Promise<void> {
  const element = messageArea.value
  const shouldScroll = element ? isNearBottom() : true
  await nextTick()
  if (element && shouldScroll) {
    element.scrollTop = element.scrollHeight
  }
}

watch([lastMessageKey, streamingContent], () => {
  void autoScrollToBottom()
})

/**
 * 「加载更早」：前插会改变 scrollHeight，用差值补偿 scrollTop。
 *
 * <p>不补偿的话，新消息插到顶部会把用户正在看的那一行顶出视野 ——
 * 用户会看到内容「突然跳走」，还得自己滚回去找刚才看到哪了。
 */
async function handleLoadOlder(): Promise<void> {
  const element = messageArea.value
  const prevHeight = element?.scrollHeight ?? 0
  await chatStore.loadOlderMessages()
  await nextTick()
  if (element) {
    element.scrollTop += element.scrollHeight - prevHeight
  }
}

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
  // 模型清单来自生效配置：只有生效那套的端点与密钥是真在用的。
  // 不 await：它只影响顶栏那个下拉的选项，拿不到也只是「暂时没得选」，
  // 不该拖住首屏会话的加载。失败由 store 自己记错，页面不额外提示。
  void modelProfileStore.load()
  await chatStore.loadSessions()
  const first = sessions.value[0]
  if (!currentConversationId.value && first) {
    await chatStore.selectSession(first.conversationId)
  }
})

/** 当前会话记录着的模型（null = 跟随生效配置的默认模型） */
const sessionModel = computed(() => {
  const current = sessions.value.find((item) => item.conversationId === currentConversationId.value)
  return current?.chatModel ?? null
})

/** 生效配置里可选的对话模型清单 */
const availableChatModels = computed(() => modelProfileStore.activeProfile?.chatModels ?? [])

/** 生效配置的默认模型名，用于「跟随配置」的说明文案 */
const activeDefaultModel = computed(() => defaultChatModel(modelProfileStore.activeProfile))

/**
 * 切换本会话的模型。
 *
 * <p>成功不弹提示：下拉本身已经显示成新值，再弹一个「已切换」是噪音。
 * 失败必须提示 —— 界面还显示着旧值，不说一声用户会以为已经切了。
 */
async function handleModelChange(model: string | null): Promise<void> {
  if (!currentConversationId.value) {
    return
  }
  const success = await chatStore.setModel(currentConversationId.value, model)
  if (!success && chatStore.error) {
    ElMessage.error(chatStore.error)
  }
}

async function handleSend(attachments: ChatAttachment[] = []): Promise<void> {
  if (!canSend.value) {
    return
  }
  const content = draft.value
  draft.value = ''
  await chatStore.send(content, {
    useKnowledge: knowledgeOn.value,
    topK: topK.value,
    thinkingLevel: settings.value.thinkingLevel,
    attachments,
  })
}

async function handleNewSession(): Promise<void> {
  await chatStore.createSession()
}

/**
 * 行内重命名完成：标题已在列表里 trim 与校验，这里只负责落库。
 *
 * 改造前用 ElMessageBox.prompt 弹窗——样式与页面割裂，且「改个名字要弹一层模态」
 * 本身就很重。行内编辑的三个边界（点击穿透、失焦保存 vs Esc 取消、空标题）
 * 由 ChatSessionList 处理，父组件不掺和。
 */
async function handleRename(session: ChatSession, title: string): Promise<void> {
  const success = await chatStore.renameSession(session.conversationId, title)
  if (success) {
    ElMessage.success('已重命名')
  }
}

/**
 * 删除会话。
 *
 * 二次确认已由 ChatSessionList 的**行内确认条**完成（点删除 → 该行展开「删除？[取消][删除]」），
 * 父组件拿到的一定是用户明确确认过的结果，直接删即可。
 * 改造前这里还有一层 ElMessageBox 弹窗：样式与页面割裂、又重又丑，且与行内确认重复。
 */
async function handleRemove(conversationId: string): Promise<void> {
  await chatStore.removeSession(conversationId)
}

/**
 * 导出单个会话为 Markdown。
 *
 * <p>不弹二次确认：导出是只读操作，没有破坏性，多一次弹窗只增加摩擦。
 * 失败时提示文案直接来自后端（如会话已不存在）。
 */
/**
 * 编辑用户消息后重发。
 *
 * <p>用 prompt 而不是行内编辑：行内编辑要处理「保存 vs 取消」「失焦」「Enter 换行」
 * 一整套边界，与重命名输入框同一量级。这一版先保证能力可用；
 * 交互形式可以后续照搬重命名的行内做法。
 */
async function handleEditMessage(message: ChatMessage): Promise<void> {
  try {
    const result = await ElMessageBox.prompt('修改后将从这条消息重新开始，其后的对话会被删除。', '编辑并重发', {
      inputValue: message.content,
      inputType: 'textarea',
      confirmButtonText: '重发',
      cancelButtonText: '取消',
    })
    await chatStore.editAndResend(message, result.value ?? '')
  } catch {
    // 取消编辑不是错误
  }
}

async function handleExportSession(session: ChatSession): Promise<void> {
  try {
    const filename = await downloadSessionMarkdown(session.conversationId)
    ElMessage.success(`已导出 ${filename}`)
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '导出失败，请稍后重试')
  }
}

// ------------------------------------------------------------------ 会话内搜索
/*
 * 只搜「已加载的消息」而不是整个会话：长会话按页加载，服务端全文搜索还要处理
 * 「命中在第 300 条、但界面上只有最近 50 条」的跳转问题（得再翻好几页）——
 * 那属于另一个功能。这里解决的是「刚才那句在哪儿」，用户要找的几乎总在眼前这段。
 * 界面上如实写明范围，避免用户以为搜过了全部。
 */
const searchKeyword = ref('')
const matchCursor = ref(0)

const matchIds = computed(() => matchMessageIds(messages.value, searchKeyword.value))

watch(searchKeyword, () => {
  matchCursor.value = 0
})

/** 跳到上一处 / 下一处，首尾循环 */
async function jumpToMatch(step: number): Promise<void> {
  if (matchIds.value.length === 0) {
    return
  }
  matchCursor.value = nextMatchCursor(matchCursor.value, matchIds.value.length, step)
  await nextTick()
  const selector = `[data-message-id="${matchIds.value[matchCursor.value]}"]`
  const target = messageArea.value?.querySelector(selector)
  target?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  // 短暂描边：不做文本级高亮 —— 消息正文是渲染后的 Markdown，
  // 在里面插 <mark> 要改动渲染管线，代价远大于收益
  target?.classList.add('chat__msg-hit')
  window.setTimeout(() => target?.classList.remove('chat__msg-hit'), 1800)
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
      @remove="handleRemove"
      @export="handleExportSession"
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
            <!--
              会话级模型选择（V23）。只在新会话存在时显示：
              没有会话就没有「这个会话用哪个模型」可言。
            -->
            <ChatModelPicker
              v-if="currentConversationId"
              :model-value="sessionModel"
              :models="availableChatModels"
              :default-model="activeDefaultModel"
              :disabled="streaming"
              @update:model-value="handleModelChange"
            />
          </div>
          <div class="chat__header-actions">
            <div class="chat__search">
              <el-input
                v-model="searchKeyword"
                size="small"
                clearable
                class="chat__search-input"
                placeholder="在已加载的消息中查找"
                aria-label="在已加载的消息中查找"
                @keyup.enter="jumpToMatch(1)"
              />
              <template v-if="searchKeyword.trim()">
                <span class="chat__search-count">
                  {{ matchIds.length ? `${matchCursor + 1}/${matchIds.length}` : '无匹配' }}
                </span>
                <el-button
                  link
                  size="small"
                  :disabled="matchIds.length === 0"
                  aria-label="上一处匹配"
                  @click="jumpToMatch(-1)"
                >
                  ↑
                </el-button>
                <el-button
                  link
                  size="small"
                  :disabled="matchIds.length === 0"
                  aria-label="下一处匹配"
                  @click="jumpToMatch(1)"
                >
                  ↓
                </el-button>
              </template>
            </div>
            <el-tag v-if="streaming" type="warning" size="small" effect="plain" role="status">生成中</el-tag>
          </div>
        </div>
      </template>

      <div class="chat__body">
        <!-- 消息滚动区 -->
        <!--
          无障碍（P3）：整个消息区是一个 live region。
          - role="log" 表示「持续追加的记录」，读屏会按新增顺序播报；
          - aria-busy 在流式期间为 true：模型的每个 delta 都会改 DOM，
            不 busy 的话读屏会不停打断自己去读半句话，体验比不播报还差。
            置 busy 后它会等这一轮结束（streaming=false）再朗读新增内容。
        -->
        <div
          ref="messageArea"
          v-loading="loadingMessages"
          class="chat__messages"
          role="log"
          aria-live="polite"
          aria-relevant="additions"
          aria-label="对话消息"
          :aria-busy="streaming"
        >
          <el-alert v-if="error" class="chat__error" type="error" :closable="false" show-icon :title="error" />

          <!-- 空态：欢迎 + 建议问题 -->
          <ChatWelcome
            v-if="messages.length === 0 && !streamingContent && !loadingMessages"
            :suggestions="suggestions"
            @pick="draft = $event"
          />

          <!-- 更早的消息入口（阶段 21）：首屏只取最近一页，向上翻是显式动作 -->
          <div v-if="hasOlderMessages || loadingOlder" class="chat__load-older">
            <el-button text size="small" :loading="loadingOlder" @click="handleLoadOlder"> 加载更早的消息 </el-button>
          </div>

          <!-- 消息流：工具轨迹内联在最后一条助手消息里，不单独占一行 -->
          <ChatMessageItem
            v-for="(message, index) in messages"
            :key="message.id"
            :message="message"
            :tool-trail="isLastAssistant(index) ? toolTrail : []"
            @copy="copyMessage"
            @regenerate="chatStore.regenerate()"
            @edit="handleEditMessage"
          />

          <!-- 流式中的助手消息 -->
          <ChatStreamingItem v-if="streaming" :content="streamingContent" :hint="retrievalHint" />

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
          :thinking-level="settings.thinkingLevel"
          :enter-send="settings.enterSend"
          :context-usage="contextUsage"
          @toggle-knowledge="toggleKnowledge"
          @update:top-k="(value: number | null) => (topK = value)"
          @update:thinking-level="(value: ThinkingLevel) => (settings.thinkingLevel = value)"
          @change-budget="chatStore.changeContextBudget"
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
 *
 * 高度用 100% 而不是 calc(100vh - 156px)：外壳 .app-shell__main 已是
 * flex:1 的列容器，这里只要铺满它即可。魔法数字在顶栏/页脚高度变化时必然失准，
 * 表现为页面底部空一条（2026-10-26 用户反馈「没铺满」的根因之一）。
 */
.chat {
  display: grid;
  grid-template-columns: 260px 1fr;
  /* 两栏间距收窄到 10px：原先 16px 在宽屏下像一道沟 */
  gap: var(--os-space-3);
  /* flex:1 撑满 main 的剩余高度；不写 height:100%（父链上有 auto 高度元素时它不解析） */
  flex: 1;
  min-height: 0;
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
  /* 消息间距与内边距双双收窄：让内容尽量铺开，减少「周围一圈白」 */
  gap: var(--os-space-3);
  padding: var(--os-space-3) var(--os-space-4) var(--os-space-3);
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
    flex: none;
  }

  /*
   * 侧栏限高：不限制时它会把会话列表全部展开（实测 860px 视口里占 300px），
   * 把对话区挤到下半屏。卡片内部本来就会滚动，限高后对话区立刻回到首屏。
   * 200px 是量出来的：再高就把空态的输入框顶出首屏（手机上第一件事是打字）。
   */
  .chat-sessions {
    max-height: 200px;
  }

  /*
   * 消息区在窄屏的最小高度。
   * 50vh 会把空态欢迎区撑得很高，输入框被顶出首屏 —— 手机上第一件事是打字，
   * 看不到输入框等于空转。收到 30vh：有消息时靠内容自然撑高，空态时输入框紧跟其后。
   */
  .chat__messages {
    min-height: 30vh;
  }
}
/* ---- 会话内搜索 ---- */
.chat__header-actions {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
}

.chat__search {
  display: flex;
  align-items: center;
  gap: 4px;
}

.chat__search-input {
  width: 200px;
}

/* 计数用等宽数字：切换匹配时宽度不跳，视线不会被带着走 */
.chat__search-count {
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
  min-width: 34px;
  text-align: right;
}

/* 命中定位：只在跳转后短暂描边，不改变消息本身的排版 */
.chat__messages :deep(.chat__msg-hit) {
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 4px;
  border-radius: var(--os-radius-sm);
}
</style>
