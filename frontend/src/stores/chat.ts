import { defineStore } from 'pinia'
import { ref } from 'vue'

import {
  createSession as createSessionApi,
  deleteSession as deleteSessionApi,
  fetchContextUsage,
  setContextBudget,
  listMessages,
  listSessions,
  renameSession as renameSessionApi,
  streamChat,
  rewindMessages,
} from '@/api/chat'
import { listToolInvocations } from '@/api/activity'
import { cancelOperation, confirmOperation, listConfirmations } from '@/api/confirmations'
import type { ContextUsage } from '@/api/chat'
import type {
  ChatAttachment,
  ChatCitation,
  ChatMessage,
  ChatSession,
  TaskConfirmation,
  ToolInvocation,
} from '@/types/api'
import type { ThinkingLevel } from '@/lib/settings'

/**
 * 首屏与每次「加载更早」的消息条数（阶段 21）。
 *
 * 50 是折中：一屏大约 6~8 轮对话，50 条足够填满并且留有余量；
 * 再大就失去了分页的意义，再小则频繁翻页。
 */
const MESSAGE_PAGE_SIZE = 50

/**
 * 聊天状态：会话列表 + 当前会话消息 + 流式中的临时内容。
 *
 * 流式过程中助手消息尚未落库，因此用 `streamingContent` 单独承接增量，
 * 等 `done` 之后再从服务端拉取权威数据（含消息 id 与顺序号）。
 */
export const useChatStore = defineStore('chat', () => {
  const sessions = ref<ChatSession[]>([])
  /** 符合条件的会话总数（分页响应带来） */
  const sessionsTotal = ref(0)
  /** 当前页码，从 0 开始 */
  const sessionsPage = ref(0)
  /** 每页条数 */
  const sessionsSize = ref(20)
  /** 会话标题关键词 */
  const sessionKeyword = ref('')
  const currentConversationId = ref<string | null>(null)
  const messages = ref<ChatMessage[]>([])
  /** 更早的消息是否还可能存在（阶段 21：首屏只取最近一页，向前翻页靠它判断） */
  const hasOlderMessages = ref(false)
  const loadingOlder = ref(false)
  const streamingContent = ref('')
  const streaming = ref(false)
  const loadingSessions = ref(false)
  const loadingMessages = ref(false)
  const error = ref<string | null>(null)

  /**
   * 当前会话的待确认操作。
   *
   * 助手只能**申请**执行高风险操作；真正执行必须由人在这里点确认。
   * 理论上模型也可以「在回复里说已删除」，但只有这里才真的会删。
   */
  const pendingConfirmations = ref<TaskConfirmation[]>([])

  /**
   * 本轮（正在流式输出的这一轮）知识库检索命中（阶段 19）。
   *
   * 来源是 SSE citations 事件；done 之后消息列表会带落库后的引用接管，
   * 这个 ref 只在流式中用于「已找到 N 条依据」的即时展示。
   */
  const liveCitations = ref<ChatCitation[]>([])
  /**
   * 本轮检索状态：idle 未开启 / searching 检索中 / done 已出结果（阶段 19）。
   *
   * 之所以不能只靠 liveCitations 判空：零命中时事件带着空数组到达，
   * 与「事件还没到」无法区分——状态机会。
   */
  const retrievalState = ref<'idle' | 'searching' | 'done'>('idle')

  /**
   * 当前会话的工具调用轨迹（阶段 18，User Story 3 / T014）。
   *
   * 数据源是审计接口的 conversationId 过滤——该参数后端一直支持，
   * 只是活动页没用过。消息体本身不带工具字段（ChatMessage 只有
   * role/content/sequenceNo），SSE 也只有 start/delta/done/error 四种帧，
   * 所以这是**不新增接口**能拿到真实轨迹的唯一途径。
   *
   * 失败时静默留空：轨迹是增强信息，不该阻塞对话本身。
   */
  const toolTrail = ref<ToolInvocation[]>([])

  /**
   * 最近一轮的上下文用量（阶段 22 仪表盘，token 口径）。
   *
   * 由后端给出：前端拿不到拼装后的系统提示，自己估算等于给用户看假数字。
   * budgetTokens 是**用户选择的预算**（同时是窗口裁剪阈值），
   * contextWindow 是模型支持的最大上下文 —— 前者才是仪表盘的分母。
   */
  const contextUsage = ref<ContextUsage>({ promptTokens: 0, budgetTokens: 0, contextWindow: 0, estimated: true })

  /** 设置上下文预算（token）；服务端会收敛到 [1, 模型窗口] */
  async function changeContextBudget(tokens: number): Promise<void> {
    const next = await setContextBudget(tokens)
    contextUsage.value = { ...contextUsage.value, budgetTokens: next.budgetTokens, contextWindow: next.contextWindow }
  }

  let abortController: AbortController | null = null

  async function loadSessions(targetPage: number = sessionsPage.value): Promise<void> {
    loadingSessions.value = true
    try {
      const result = await listSessions({
        keyword: sessionKeyword.value.trim() || undefined,
        page: targetPage,
        size: sessionsSize.value,
      })
      sessions.value = result.items
      sessionsPage.value = result.page
      sessionsSize.value = result.size
      sessionsTotal.value = result.total
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '加载会话列表失败'
    } finally {
      loadingSessions.value = false
    }
  }

  /** 关键词变化：先归零页码，否则会停在越界页看到空列表 */
  async function searchSessions(): Promise<void> {
    sessionsPage.value = 0
    await loadSessions(0)
  }

  async function goToSessionPage(next: number): Promise<void> {
    await loadSessions(next)
  }

  async function createSession(title?: string): Promise<void> {
    error.value = null
    try {
      const created = await createSessionApi(title)
      await loadSessions()
      await selectSession(created.conversationId)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '创建会话失败'
    }
  }

  async function selectSession(conversationId: string): Promise<void> {
    if (streaming.value) {
      return
    }
    currentConversationId.value = conversationId
    loadingMessages.value = true
    error.value = null
    streamingContent.value = ''
    liveCitations.value = []
    retrievalState.value = 'idle'
    try {
      const page = await listMessages(conversationId, { limit: MESSAGE_PAGE_SIZE })
      messages.value = page
      // 刚好装满一页 ⇒ 很可能还有更早的；不足一页 ⇒ 已经到头
      hasOlderMessages.value = page.length === MESSAGE_PAGE_SIZE
      // 仪表盘数据：done 事件只在当轮有效，刷新页面后靠这里补一次
      try {
        contextUsage.value = await fetchContextUsage(conversationId)
      } catch {
        // 仪表盘是增强信息，查不到就不显示，不该影响会话打开
      }
      await loadConfirmations()
      await loadToolTrail()
    } catch (cause) {
      messages.value = []
      hasOlderMessages.value = false
      pendingConfirmations.value = []
      toolTrail.value = []
      contextUsage.value = { promptTokens: 0, budgetTokens: 0, contextWindow: 0, estimated: true }
      error.value = cause instanceof Error ? cause.message : '加载消息失败'
    } finally {
      loadingMessages.value = false
    }
  }

  /**
   * 加载更早的消息（阶段 21）。
   *
   * <p>以当前最旧一条的顺序号作为游标向前翻，而不是用 offset：
   * 翻页期间新消息只会追加在尾部，offset 会因此错位（重复或漏项），
   * 顺序号游标不会。
   */
  async function loadOlderMessages(): Promise<void> {
    const conversationId = currentConversationId.value
    const oldest = messages.value[0]
    if (!conversationId || !oldest || !hasOlderMessages.value || loadingOlder.value) {
      return
    }
    loadingOlder.value = true
    try {
      const older = await listMessages(conversationId, {
        limit: MESSAGE_PAGE_SIZE,
        beforeSeq: oldest.sequenceNo,
      })
      messages.value = [...older, ...messages.value]
      hasOlderMessages.value = older.length === MESSAGE_PAGE_SIZE
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '加载更早消息失败'
    } finally {
      loadingOlder.value = false
    }
  }

  /**
   * 用服务端的权威数据刷新「尾部」，同时保留已经加载的更早历史。
   *
   * <p>不能整体替换成最近一页：用户可能已经向上翻过几页，
   * 整体替换会让视图突然跳回最新的一页。
   */
  async function refreshTail(conversationId: string): Promise<void> {
    const tail = await listMessages(conversationId, { limit: MESSAGE_PAGE_SIZE })
    const oldestTailSeq = tail.length > 0 ? tail[0].sequenceNo : Number.POSITIVE_INFINITY
    const kept = messages.value.filter((message) => message.sequenceNo < oldestTailSeq)
    messages.value = [...kept, ...tail]
    if (kept.length === 0) {
      hasOlderMessages.value = tail.length === MESSAGE_PAGE_SIZE
    }
  }

  /** 拉取当前会话的待确认操作 */
  async function loadConfirmations(): Promise<void> {
    if (!currentConversationId.value) {
      pendingConfirmations.value = []
      return
    }
    try {
      pendingConfirmations.value = await listConfirmations(currentConversationId.value)
    } catch {
      // 待确认列表拉取失败不应影响对话本身
      pendingConfirmations.value = []
    }
  }

  /**
   * 拉取当前会话的工具调用轨迹（阶段 18）。
   *
   * 与 loadConfirmations 同款容错：失败静默留空。
   * 拉最近 20 条足够覆盖「本轮助手做了什么」。
   */
  async function loadToolTrail(): Promise<void> {
    if (!currentConversationId.value) {
      toolTrail.value = []
      contextUsage.value = { promptTokens: 0, budgetTokens: 0, contextWindow: 0, estimated: true }
      return
    }
    try {
      const result = await listToolInvocations({
        conversationId: currentConversationId.value,
        size: 20,
      })
      toolTrail.value = result.items
    } catch {
      toolTrail.value = []
      contextUsage.value = { promptTokens: 0, budgetTokens: 0, contextWindow: 0, estimated: true }
    }
  }

  /**
   * 处理一条待确认操作。
   *
   * @param approve true 确认执行，false 取消
   * @returns 执行结果描述，失败时返回 null
   */
  async function resolveConfirmation(confirmation: TaskConfirmation, approve: boolean): Promise<string | null> {
    error.value = null
    try {
      if (!approve) {
        await cancelOperation(confirmation.id, confirmation.token)
        return '已取消'
      }
      const result = await confirmOperation(confirmation.id, confirmation.token)
      return result.summary
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '处理确认失败'
      return null
    } finally {
      await loadConfirmations()
    }
  }

  async function removeSession(conversationId: string): Promise<void> {
    error.value = null
    try {
      await deleteSessionApi(conversationId)
      if (currentConversationId.value === conversationId) {
        currentConversationId.value = null
        messages.value = []
      }
      await loadSessions()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '删除会话失败'
    }
  }

  /**
   * 重命名会话。
   *
   * 成功后就地替换列表中的那一项，**不重拉列表**：改名既不改变集合也不改变顺序
   * （服务端刻意不刷新 `updatedAt`），重拉只会带来一次可见的闪烁，没有任何信息收益。
   * 失败时保持本地状态不动 —— 因为没做乐观更新，也就没有需要回滚的东西。
   *
   * @returns 是否成功，供调用方决定是否提示
   */
  async function renameSession(conversationId: string, title: string): Promise<boolean> {
    error.value = null
    try {
      const updated = await renameSessionApi(conversationId, title)
      sessions.value = sessions.value.map((item) => (item.conversationId === conversationId ? updated : item))
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '重命名会话失败'
      return false
    }
  }

  /**
   * 发送一条消息并流式接收回复。
   *
   * 用户消息先乐观追加到本地列表（否则会出现「点发送后界面毫无反应」的观感），
   * 助手回复逐段追加到 `streamingContent`；结束后统一以服务端数据为准刷新。
   *
   * @param options 阶段 19：知识库增强参数。useKnowledge 为 false / 省略时
   *                不带该字段，行为与阶段 1 一致
   */
  async function send(
    content: string,
    options?: {
      useKnowledge?: boolean
      topK?: number
      thinkingLevel?: ThinkingLevel
      attachments?: ChatAttachment[]
    },
  ): Promise<void> {
    const trimmed = content.trim()
    if (trimmed.length === 0 || streaming.value) {
      return
    }
    if (!currentConversationId.value) {
      await createSession()
      if (!currentConversationId.value) {
        return
      }
    }

    const conversationId = currentConversationId.value
    // 乐观消息的顺序号必须取「当前最大 + 1」而不是「条数 + 1」：
    // 分页之后列表可能只是会话的尾部一段，条数与顺序号已经不再相等
    const nextSequenceNo = messages.value.reduce((max, message) => Math.max(max, message.sequenceNo), 0) + 1
    const optimisticUserMessage: ChatMessage = {
      id: -1,
      role: 'USER',
      content: trimmed,
      sequenceNo: nextSequenceNo,
      createdAt: new Date().toISOString(),
      citations: [],
      attachments: options?.attachments ?? [],
    }
    messages.value = [...messages.value, optimisticUserMessage]
    streamingContent.value = ''
    liveCitations.value = []
    retrievalState.value = options?.useKnowledge ? 'searching' : 'idle'
    streaming.value = true
    error.value = null
    abortController = new AbortController()

    try {
      await streamChat(
        conversationId,
        trimmed,
        {
          onCitations: (citations) => {
            liveCitations.value = citations
            retrievalState.value = 'done'
          },
          onDelta: (chunk) => {
            streamingContent.value += chunk
          },
          onDone: (_content, context) => {
            // done 事件只带本轮用量；预算与模型窗口以选中会话时查到的为准
            if (context && context.promptTokens >= 0 && contextUsage.value.contextWindow > 0) {
              contextUsage.value = { ...contextUsage.value, promptTokens: context.promptTokens }
            }
            // 清空临时内容，随后由服务端数据接管，避免出现重复渲染
            streamingContent.value = ''
          },
          onError: (message) => {
            error.value = message
            streamingContent.value = ''
          },
        },
        abortController.signal,
        options,
      )
    } catch (cause) {
      // abort 也会走到这里；若已主动停止则不再报错
      if (!(cause instanceof DOMException && cause.name === 'AbortError')) {
        error.value = cause instanceof Error ? cause.message : '发送失败'
      }
      streamingContent.value = ''
    } finally {
      streaming.value = false
      abortController = null
      retrievalState.value = 'idle'
      if (conversationId) {
        try {
          await refreshTail(conversationId)
        } catch {
          // 刷新失败不影响本轮已展示的内容
        }
        await loadSessions()
        // 助手可能刚生成了待确认操作，本轮结束后立即拉一次
        await loadConfirmations()
        // 本轮助手可能调了工具，轨迹一起刷新
        await loadToolTrail()
      }
    }
  }

  /**
   * 重新生成最后一条回答。
   *
   * <p>做法是「回退到那条用户消息 + 用原内容重发」，而不是原地替换助手消息：
   * 后者会留下「第 5 条改过、第 6 条还是旧回答引发的」这种断裂对话。
   * 重发走的是既有 {@link send}，因此思考档位、知识库开关、附件等行为完全一致。
   */
  async function regenerate(): Promise<void> {
    const conversationId = currentConversationId.value
    if (!conversationId || streaming.value) {
      return
    }
    const lastUser = [...messages.value].reverse().find((message) => message.role === 'USER')
    if (!lastUser) {
      return
    }
    try {
      await rewindMessages(conversationId, lastUser.sequenceNo)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '重新生成失败'
      return
    }
    await refreshTail(conversationId)
    await send(lastUser.content)
  }

  /**
   * 编辑用户消息后重发：回退到该消息（含）并删除其后所有内容，再用新内容发送。
   */
  async function editAndResend(message: ChatMessage, newContent: string): Promise<void> {
    const conversationId = currentConversationId.value
    const content = newContent.trim()
    if (!conversationId || streaming.value || content.length === 0) {
      return
    }
    try {
      await rewindMessages(conversationId, message.sequenceNo)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '重发失败'
      return
    }
    await refreshTail(conversationId)
    await send(content)
  }

  /** 停止生成（客户端断开，服务端会取消上游订阅） */
  function stopStreaming(): void {
    abortController?.abort()
  }

  return {
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
    contextUsage,
    changeContextBudget,
    liveCitations,
    retrievalState,
    loadSessions,
    searchSessions,
    goToSessionPage,
    createSession,
    selectSession,
    loadOlderMessages,
    removeSession,
    renameSession,
    send,
    regenerate,
    editAndResend,
    stopStreaming,
    loadConfirmations,
    loadToolTrail,
    resolveConfirmation,
  }
})
