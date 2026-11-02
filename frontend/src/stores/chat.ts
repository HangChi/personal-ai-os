import { acceptHMRUpdate, defineStore } from 'pinia'
import { ref, watch } from 'vue'

import {
  createSession as createSessionApi,
  deleteSession as deleteSessionApi,
  fetchContextUsage,
  setContextBudget,
  listMessages,
  listSessions,
  renameSession as renameSessionApi,
  setSessionModel as setSessionModelApi,
  rewindMessages,
} from '@/api/chat'
import {
  answerQuestion,
  submitFollowup,
  cancelFollowup,
  finishConfirmations,
  editFollowup as editFollowupApi,
  steerFollowup as steerFollowupApi,
  followRun,
  latestRun,
  startRun,
  resumeRun,
  stopRun,
  type ChatRun,
  type RunActivity,
} from '@/api/chat-runs'
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
import { useModelProfileStore } from '@/stores/model-profile'
import { budgetPresetsFor } from '@/lib/context-meter'

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
  const activeRun = ref<ChatRun | null>(null)
  const liveActivities = ref<RunActivity[]>([])
  const reconnecting = ref(false)
  const submittingInput = ref(false)
  let followupAttempt: { signature: string; key: string } | null = null
  const sendAttempts = new Map<string, { signature: string; key: string }>()
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
  const liveRag = ref<import('@/types/api').RagRetrieval | null>(null)
  const liveCitations = ref<ChatCitation[]>([])
  /**
   * 本轮检索状态：idle 未开启 / searching 检索中 / done 已出结果（阶段 19）。
   *
   * 之所以不能只靠 liveCitations 判空：零命中时事件带着空数组到达，
   * 与「事件还没到」无法区分——状态机会。
   */
  const retrievalState = ref<'idle' | 'searching' | 'done'>('idle')

  /** 历史工具轨迹来自审计接口；执行中的活动由持久化事件即时展示。 */
  const toolTrail = ref<ToolInvocation[]>([])
  /** Records for loaded historical replies; a new message must not erase previous turns. */
  const toolHistory = ref<ToolInvocation[]>([])

  /**
   * 最近一轮的上下文用量（阶段 22 仪表盘，token 口径）。
   *
   * 由后端给出：前端拿不到拼装后的系统提示，自己估算等于给用户看假数字。
   * budgetTokens 是**用户选择的预算**（同时是窗口裁剪阈值），
   * contextWindow 是模型支持的最大上下文 —— 前者才是仪表盘的分母。
   */
  const contextUsage = ref<ContextUsage>({ promptTokens: 0, budgetTokens: 0, contextWindow: 0, estimated: true })

  let contextReqSeq = 0
  let contextThinkingLevel = 'off'

  function emptyContext(): ContextUsage {
    return { promptTokens: 0, budgetTokens: 0, contextWindow: 0, estimated: true }
  }

  /** 旧会话或旧模型的慢响应不能覆盖当前读数。失败时清空，避免冒充新模型窗口。 */
  async function refreshContextUsage(thinkingLevel?: string): Promise<void> {
    if (thinkingLevel !== undefined) contextThinkingLevel = thinkingLevel
    const id = currentConversationId.value
    const sessionSeq = sessionReqSeq
    const seq = ++contextReqSeq
    const previous = contextUsage.value
    try {
      let next = await fetchContextUsage(id, contextThinkingLevel)
      // 模型容量或安全输入上限改动后沿用所选档位，而不是沿用旧容量下的绝对 token 数。
      // 仅迁移同一个模型的标准档位；自定义预算与切换模型保持原有行为。
      if (
        seq === contextReqSeq &&
        sessionSeq === sessionReqSeq &&
        id === currentConversationId.value &&
        previous.modelName &&
        previous.modelName === next.modelName &&
        (previous.contextWindow !== next.contextWindow || previous.inputLimitTokens !== next.inputLimitTokens)
      ) {
        const tier = budgetPresetsFor(previous.contextWindow, previous.inputLimitTokens).indexOf(previous.budgetTokens)
        const tokens = budgetPresetsFor(next.contextWindow, next.inputLimitTokens)[tier]
        if (tokens !== undefined && tokens !== next.budgetTokens) {
          next = await setContextBudget(tokens, id, contextThinkingLevel)
        }
      }
      if (seq === contextReqSeq && sessionSeq === sessionReqSeq && id === currentConversationId.value) {
        contextUsage.value = next
      }
    } catch {
      if (seq === contextReqSeq && sessionSeq === sessionReqSeq && id === currentConversationId.value) {
        contextUsage.value = emptyContext()
      }
    }
  }

  async function changeContextBudget(tokens: number): Promise<void> {
    const id = currentConversationId.value
    const sessionSeq = sessionReqSeq
    const seq = ++contextReqSeq
    const next = await setContextBudget(tokens, id, contextThinkingLevel)
    if (seq === contextReqSeq && sessionSeq === sessionReqSeq && id === currentConversationId.value) {
      contextUsage.value = next
    }
  }

  let abortController: AbortController | null = null
  /**
   * 会话切换的请求序号（2026-10-27 审查 #14 竞态防护）。
   *
   * <p>连点 A→B 时，A 的 `listMessages` 可能比 B 的**更晚返回**。若在 await 之后
   * 无条件赋值，就会出现「标题是 B、消息内容是 A」的错位——更糟的是此时
   * `currentConversationId` 已是 B，用户接着发消息会写进 B 会话，而界面显示的是 A 的内容。
   * 自增序号 + await 后校验，只有「仍是最新一次请求」的那轮才有资格写回状态。
   */
  let sessionReqSeq = 0

  // 配置在设置页保存时，对话页已经卸载。由 store 持续监听，避免保留旧窗口快照。
  const modelProfileStore = useModelProfileStore()
  let pendingContextRefresh = false
  watch(
    () => modelProfileStore.activeProfile,
    () => {
      if (streaming.value) {
        pendingContextRefresh = true
      } else {
        void refreshContextUsage()
      }
    },
    { deep: true },
  )
  watch(streaming, (running) => {
    if (!running && pendingContextRefresh) {
      pendingContextRefresh = false
      void refreshContextUsage()
    }
  })

  let sessionsRequestSeq = 0
  async function loadSessions(targetPage: number = sessionsPage.value): Promise<void> {
    const requestSeq = ++sessionsRequestSeq
    loadingSessions.value = true
    try {
      const result = await listSessions({
        keyword: sessionKeyword.value.trim() || undefined,
        page: targetPage,
        size: sessionsSize.value,
      })
      if (requestSeq !== sessionsRequestSeq) return
      sessions.value = result.items
      sessionsPage.value = result.page
      sessionsSize.value = result.size
      sessionsTotal.value = result.total
    } catch (cause) {
      if (requestSeq !== sessionsRequestSeq) return
      error.value = cause instanceof Error ? cause.message : '加载会话列表失败'
    } finally {
      if (requestSeq === sessionsRequestSeq) loadingSessions.value = false
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
      // 流式中禁止切换。改造前这里是静默 return —— 用户以为点击失效而反复点。
      // 走 error 通道给一句明确提示（ChatView 的 el-alert 会显示），
      // 让用户知道要「先停止再切换」，而不是猜是不是自己没点中。
      error.value = '正在生成回复，请先停止再切换会话'
      return
    }
    // 本轮请求的序号：await 之后据此判断「我还是不是最新一次选择」。
    // 提前自增并记住，而不是 await 后再比 currentConversationId ——
    // 那样挡不住「A→B→A」这种绕一圈回来的情况（id 相同但已是不同一轮）。
    const reqSeq = ++sessionReqSeq
    currentConversationId.value = conversationId
    activeRun.value = null
    liveActivities.value = []
    toolTrail.value = []
    toolHistory.value = []
    contextUsage.value = emptyContext()
    loadingMessages.value = true
    error.value = null
    streamingContent.value = ''
    liveCitations.value = []
    liveRag.value = null
    retrievalState.value = 'idle'
    try {
      const page = await listMessages(conversationId, { limit: MESSAGE_PAGE_SIZE })
      // 竞态防护：await 期间用户切走了，这次返回的结果作废，一个字节都不能写回
      if (reqSeq !== sessionReqSeq) {
        return
      }
      messages.value = page
      // 刚好装满一页 ⇒ 很可能还有更早的；不足一页 ⇒ 已经到头
      hasOlderMessages.value = page.length === MESSAGE_PAGE_SIZE
      // 仪表盘数据：done 事件只在当轮有效，刷新页面后靠这里补一次
      await refreshContextUsage()
      if (reqSeq !== sessionReqSeq) {
        return
      }
      await loadConfirmations()
      await loadToolTrail()
      try {
        const run = await latestRun(conversationId)
        if (reqSeq !== sessionReqSeq) return
        activeRun.value = run
        if (run?.status === 'RUNNING') void trackRun(run)
      } catch {
        /* 执行记录查询失败不清空已加载的消息 */
      }
    } catch (cause) {
      if (reqSeq !== sessionReqSeq) {
        return
      }
      messages.value = []
      hasOlderMessages.value = false
      pendingConfirmations.value = []
      toolTrail.value = []
      contextUsage.value = { promptTokens: 0, budgetTokens: 0, contextWindow: 0, estimated: true }
      error.value = cause instanceof Error ? cause.message : '加载消息失败'
    } finally {
      // 只有自己这轮仍是「最新」时才复位加载态；否则别动 —— 后一轮还在加载，
      // 这里无条件复位会把它的 loading 误关掉，界面提前露出旧内容。
      if (reqSeq === sessionReqSeq) {
        loadingMessages.value = false
      }
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
    const reqSeq = sessionReqSeq
    loadingOlder.value = true
    try {
      const older = await listMessages(conversationId, {
        limit: MESSAGE_PAGE_SIZE,
        beforeSeq: oldest.sequenceNo,
      })
      // 竞态防护：翻页期间切走了会话，这批更早消息属于旧会话，不能拼到新会话上
      if (reqSeq !== sessionReqSeq || conversationId !== currentConversationId.value) {
        return
      }
      messages.value = [...older, ...messages.value]
      hasOlderMessages.value = older.length === MESSAGE_PAGE_SIZE
      await loadToolTrail()
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
    const reqSeq = sessionReqSeq
    const tail = await listMessages(conversationId, { limit: MESSAGE_PAGE_SIZE })
    // 竞态防护：刷新期间切走了会话，这批尾部属于旧会话，不能写回
    if (reqSeq !== sessionReqSeq || conversationId !== currentConversationId.value) {
      return
    }
    const oldestTailSeq = tail.length > 0 ? tail[0].sequenceNo : Number.POSITIVE_INFINITY
    const kept = messages.value.filter((message) => message.sequenceNo < oldestTailSeq)
    messages.value = [...kept, ...tail]
    if (kept.length === 0) {
      hasOlderMessages.value = tail.length === MESSAGE_PAGE_SIZE
    }
  }

  /** 拉取当前会话的待确认操作 */
  let confirmationReqSeq = 0
  async function loadConfirmations(): Promise<void> {
    const id = currentConversationId.value
    const sessionSeq = sessionReqSeq
    const seq = ++confirmationReqSeq
    if (!id) {
      pendingConfirmations.value = []
      return
    }
    try {
      const items = await listConfirmations(id)
      if (seq === confirmationReqSeq && sessionSeq === sessionReqSeq && id === currentConversationId.value) {
        pendingConfirmations.value = items
      }
    } catch {
      // 待确认列表拉取失败不应影响对话本身
      if (seq === confirmationReqSeq && sessionSeq === sessionReqSeq && id === currentConversationId.value) {
        pendingConfirmations.value = []
      }
    }
  }

  /**
   * Load audit pages covering the loaded message window, including earlier replies.
   * Keep latest-turn statistics separate from historical records.
   */
  let toolTrailReqSeq = 0
  async function loadToolTrail(): Promise<void> {
    const id = currentConversationId.value
    const sessionSeq = sessionReqSeq
    const seq = ++toolTrailReqSeq
    if (!id) {
      toolTrail.value = []
      toolHistory.value = []
      return
    }
    try {
      const oldestUser = messages.value.find((message) => message.role === 'USER')
      const from = oldestUser ? Date.parse(oldestUser.createdAt) : Number.NaN
      const records = new Map<number, ToolInvocation>()
      for (let page = 0; ; page++) {
        const result = await listToolInvocations({ conversationId: id, size: 100, page })
        if (seq !== toolTrailReqSeq || sessionSeq !== sessionReqSeq || id !== currentConversationId.value) return
        for (const item of result.items) if (!records.has(item.id)) records.set(item.id, item)
        if (
          !result.items.length ||
          !Number.isFinite(from) ||
          result.items.some((item) => Date.parse(item.createdAt) < from) ||
          (page + 1) * 100 >= (result.total ?? result.items.length)
        )
          break
      }
      if (seq !== toolTrailReqSeq || sessionSeq !== sessionReqSeq || id !== currentConversationId.value) return
      toolHistory.value = [...records.values()]
      const lastUser = [...messages.value].reverse().find((message) => message.role === 'USER')
      const startedAt = lastUser ? Date.parse(lastUser.createdAt) : Number.NaN
      const items = toolHistory.value.filter((item) => Date.parse(item.createdAt) >= startedAt)
      const turnId = items.find((item) => item.turnId)?.turnId
      toolTrail.value = turnId ? items.filter((item) => item.turnId === turnId) : items
    } catch {
      if (seq === toolTrailReqSeq && sessionSeq === sessionReqSeq && id === currentConversationId.value)
        toolTrail.value = []
    }
  }

  /**
   * 处理一条待确认操作。
   *
   * @param approve true 确认执行，false 取消
   * @returns 执行结果描述，失败时返回 null
   */
  async function resolveConfirmation(confirmation: TaskConfirmation, approve: boolean): Promise<string | null> {
    const id = currentConversationId.value
    const sessionSeq = sessionReqSeq
    let resolved = false
    error.value = null
    try {
      if (!approve) {
        await cancelOperation(confirmation.id, confirmation.token)
        resolved = true
        return '已取消'
      }
      const result = await confirmOperation(confirmation.id, confirmation.token)
      resolved = true
      return result.summary
    } catch (cause) {
      if (id === currentConversationId.value && sessionSeq === sessionReqSeq) {
        error.value = cause instanceof Error ? cause.message : '处理确认失败'
      }
      return null
    } finally {
      if (id === currentConversationId.value && sessionSeq === sessionReqSeq) {
        await Promise.all([loadConfirmations(), loadToolTrail()])
        if (resolved && !pendingConfirmations.value.length && activeRun.value?.status === 'WAITING_CONFIRMATION') {
          const run = activeRun.value
          try {
            const finished = await finishConfirmations(run.conversationId, run.id)
            if (id === currentConversationId.value && sessionSeq === sessionReqSeq) {
              activeRun.value = finished
              const next = await latestRun(run.conversationId)
              if (id === currentConversationId.value && sessionSeq === sessionReqSeq && next) {
                activeRun.value = next
                if (next.id !== run.id && next.status === 'RUNNING') void trackRun(next)
              }
            }
          } catch {
            // 人工操作已成功；状态刷新失败不改写操作结果，也不再次请求模型。
          }
        }
      }
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
   * 设置当前会话使用的对话模型（V23）。
   *
   * <p>与 {@link renameSession} 同一套路：成功后就地替换列表项，不重拉列表
   * （后端刻意不刷新 `updatedAt`，换模型不会改变列表顺序）。
   *
   * <p>传 `null` 表示改回「跟随生效配置的默认模型」。
   *
   * <p>**只在非流式期间允许**：模型是在发请求那一刻解析的，
   * 流式途中切换不会影响正在进行的这一轮，却会让会话记录与实际用的模型
   * 不一致 —— 这是最难排查的一类困惑。
   *
   * @returns 是否成功，供调用方决定是否提示
   */
  async function setModel(conversationId: string, model: string | null): Promise<boolean> {
    if (streaming.value) {
      error.value = '正在生成回复，请等这一轮结束后再切换模型'
      return false
    }
    error.value = null
    try {
      const updated = await setSessionModelApi(conversationId, model)
      sessions.value = sessions.value.map((item) => (item.conversationId === conversationId ? updated : item))
      if (currentConversationId.value === conversationId) {
        contextUsage.value = emptyContext()
        await refreshContextUsage()
      }
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '切换模型失败'
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
    if (activeRun.value?.status === 'WAITING_USER' && activeRun.value.question) {
      await respondToQuestion(trimmed)
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
    const signature = JSON.stringify([trimmed, options])
    let attempt = sendAttempts.get(conversationId)
    if (attempt?.signature !== signature) {
      attempt = { signature, key: crypto.randomUUID() }
      sendAttempts.set(conversationId, attempt)
    }
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
    toolTrail.value = []
    streamingContent.value = ''
    liveCitations.value = []
    liveRag.value = null
    retrievalState.value = options?.useKnowledge ? 'searching' : 'idle'
    streaming.value = true
    error.value = null
    liveActivities.value = []
    try {
      // 同一次发送失败后的重试复用标识；服务端先返回已存在的执行，避免重复副作用。
      const run = await startRun(conversationId, trimmed, options, attempt.key)
      sendAttempts.delete(conversationId)
      await trackRun(run)
    } catch (cause) {
      if (currentConversationId.value === conversationId) {
        messages.value = messages.value.filter((message) => message.id !== -1 || message.sequenceNo !== nextSequenceNo)
      }
      streaming.value = false
      error.value = cause instanceof Error ? cause.message : '发送失败'
    }
  }

  async function trackRun(run: ChatRun): Promise<void> {
    const conversationId = run.conversationId
    const controller = new AbortController()
    abortController = controller
    activeRun.value = run
    streaming.value = true
    reconnecting.value = false
    streamingContent.value = ''
    liveActivities.value = []
    try {
      await followRun(
        run,
        {
          onRun: (value) => {
            activeRun.value = value
          },
          onConnection: (value) => {
            reconnecting.value = value
          },
          onReset: () => {
            streamingContent.value = ''
            liveActivities.value = []
            error.value = null
          },
          onActivity: (activity) => {
            const index = liveActivities.value.findIndex((item) => item.id === activity.id)
            if (index < 0) liveActivities.value.push(activity)
            else liveActivities.value[index] = activity
            if (activity.status === 'REQUIRES_CONFIRMATION') void loadConfirmations()
          },
          onRag: (retrieval) => {
            liveRag.value = retrieval
          },
          onCitations: (citations) => {
            liveCitations.value = citations
            retrievalState.value = 'done'
          },
          onDelta: (chunk) => {
            streamingContent.value += chunk
          },
          onDone: (_content, context) => {
            if (context) contextUsage.value = { ...contextUsage.value, promptTokens: context.promptTokens }
          },
          onError: (message) => {
            error.value = message
          },
        },
        controller.signal,
      )
    } catch (cause) {
      if (!controller.signal.aborted)
        error.value = cause instanceof Error ? cause.message : '连接中断，可重新打开会话查看后台进度'
    } finally {
      if (abortController === controller) {
        streaming.value = false
        reconnecting.value = false
        abortController = null
        retrievalState.value = 'idle'
        try {
          await refreshTail(conversationId)
          streamingContent.value = ''
        } catch {
          /* 保留已收到的内容，刷新后可重新读取执行记录 */
        }
        await Promise.all([loadSessions(), refreshContextUsage(), loadConfirmations(), loadToolTrail()])
        try {
          const next = await latestRun(conversationId)
          if (currentConversationId.value === conversationId && next) {
            activeRun.value = next
            if (next.id !== run.id && next.status === 'RUNNING') void trackRun(next)
          }
        } catch {
          /* 重新打开会话可追赶已持久化的排队任务 */
        }
      }
    }
  }

  async function respondToQuestion(answer: string): Promise<boolean> {
    const run = activeRun.value
    if (!run?.question || streaming.value || submittingInput.value || !answer.trim()) return false
    submittingInput.value = true
    error.value = null
    try {
      const next = await answerQuestion(run.conversationId, run.id, run.question.id, answer)
      activeRun.value = next
      void trackRun(next)
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '提交回答失败'
      return false
    } finally {
      submittingInput.value = false
    }
  }

  async function sendFollowup(
    content: string,
    mode: 'QUEUE' | 'STEER',
    options?: import('@/api/chat').StreamChatOptions,
  ): Promise<boolean> {
    const run = activeRun.value
    if (!run || !content.trim() || submittingInput.value) return false
    submittingInput.value = true
    error.value = null
    try {
      const signature = JSON.stringify([run.id, mode, content.trim(), options])
      if (followupAttempt?.signature !== signature) followupAttempt = { signature, key: crypto.randomUUID() }
      const next = await submitFollowup(run.conversationId, run.id, mode, content.trim(), options, followupAttempt.key)
      followupAttempt = null
      activeRun.value = next
      if (!streaming.value) {
        const latest = await latestRun(run.conversationId)
        if (latest && ['RUNNING', 'COMPLETED'].includes(latest.status)) void trackRun(latest)
      }
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '跟进消息发送失败'
      return false
    } finally {
      submittingInput.value = false
    }
  }

  async function removeFollowup(inputId: string): Promise<void> {
    const run = activeRun.value
    if (!run || submittingInput.value) return
    submittingInput.value = true
    try {
      activeRun.value = await cancelFollowup(run.conversationId, run.id, inputId)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '移除失败'
    } finally {
      submittingInput.value = false
    }
  }

  async function editFollowup(inputId: string, content: string): Promise<boolean> {
    const run = activeRun.value
    if (!run || submittingInput.value || !content.trim()) return false
    submittingInput.value = true
    error.value = null
    try {
      activeRun.value = await editFollowupApi(run.conversationId, run.id, inputId, content.trim())
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '编辑失败'
      return false
    } finally {
      submittingInput.value = false
    }
  }

  async function steerFollowup(inputId: string): Promise<void> {
    const run = activeRun.value
    if (!run || submittingInput.value) return
    submittingInput.value = true
    error.value = null
    try {
      const next = await steerFollowupApi(run.conversationId, run.id, inputId)
      activeRun.value = next
      if (next.status === 'RUNNING' && (!streaming.value || next.id !== run.id)) {
        abortController?.abort()
        void trackRun(next)
      }
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '引导失败'
    } finally {
      submittingInput.value = false
    }
  }

  async function continueRun(): Promise<void> {
    const run = activeRun.value
    if (!run || streaming.value) return
    error.value = null
    try {
      await trackRun(await resumeRun(run.conversationId, run.id))
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '继续执行失败'
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
  async function editAndResend(message: ChatMessage, newContent: string): Promise<boolean> {
    const conversationId = currentConversationId.value
    const content = newContent.trim()
    if (!conversationId || streaming.value || message.role !== 'USER' || content.length === 0) {
      return false
    }
    if (content.length > 4000) {
      error.value = '消息不能超过 4000 字'
      return false
    }
    const sessionSeq = sessionReqSeq
    try {
      await rewindMessages(conversationId, message.sequenceNo)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '重发失败'
      return false
    }
    if (sessionSeq !== sessionReqSeq || conversationId !== currentConversationId.value) return false
    // 回退成功后立即移除已删除内容；回退到首条时尾页为空，通用合并逻辑会保留旧消息。
    messages.value = messages.value.filter((item) => item.sequenceNo < message.sequenceNo)
    try {
      await refreshTail(conversationId)
    } catch {
      // 回退已经完成，沿用本地保留的历史发送；流结束后再从服务端刷新。
    }
    if (sessionSeq !== sessionReqSeq || conversationId !== currentConversationId.value) return false
    await send(content)
    return true
  }

  /** 显式停止后台任务，由服务端取消上游输出；单纯断开页面不会停止任务。 */
  async function stopStreaming(): Promise<void> {
    const run = activeRun.value
    if (!run) return
    try {
      await stopRun(run.conversationId, run.id)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '停止失败'
      return
    }
    // 先接收终态并刷新服务端消息，由 trackRun 统一收尾。
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
    activeRun,
    submittingInput,
    respondToQuestion,
    sendFollowup,
    removeFollowup,
    editFollowup,
    steerFollowup,
    liveActivities,
    reconnecting,
    continueRun,
    streamingContent,
    streaming,
    loadingSessions,
    loadingMessages,
    error,
    pendingConfirmations,
    toolTrail,
    toolHistory,
    contextUsage,
    refreshContextUsage,
    changeContextBudget,
    liveCitations,
    liveRag,
    retrievalState,
    loadSessions,
    searchSessions,
    goToSessionPage,
    createSession,
    selectSession,
    loadOlderMessages,
    removeSession,
    renameSession,
    setModel,
    send,
    regenerate,
    editAndResend,
    stopStreaming,
    loadConfirmations,
    loadToolTrail,
    resolveConfirmation,
  }
})

// 开发热更新保留会话数据，同时替换 action，避免页面仍执行旧的工具统计逻辑。
if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useChatStore, import.meta.hot))
}
