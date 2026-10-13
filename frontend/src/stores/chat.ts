import { defineStore } from 'pinia'
import { ref } from 'vue'

import {
  createSession as createSessionApi,
  deleteSession as deleteSessionApi,
  listMessages,
  listSessions,
  streamChat,
} from '@/api/chat'
import {
  cancelOperation,
  confirmOperation,
  listConfirmations,
} from '@/api/confirmations'
import type { ChatMessage, ChatSession, TaskConfirmation } from '@/types/api'

/**
 * 聊天状态：会话列表 + 当前会话消息 + 流式中的临时内容。
 *
 * 流式过程中助手消息尚未落库，因此用 `streamingContent` 单独承接增量，
 * 等 `done` 之后再从服务端拉取权威数据（含消息 id 与顺序号）。
 */
export const useChatStore = defineStore('chat', () => {
  const sessions = ref<ChatSession[]>([])
  const currentConversationId = ref<string | null>(null)
  const messages = ref<ChatMessage[]>([])
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

  let abortController: AbortController | null = null

  async function loadSessions(): Promise<void> {
    loadingSessions.value = true
    try {
      sessions.value = await listSessions()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '加载会话列表失败'
    } finally {
      loadingSessions.value = false
    }
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
    try {
      messages.value = await listMessages(conversationId)
      await loadConfirmations()
    } catch (cause) {
      messages.value = []
      pendingConfirmations.value = []
      error.value = cause instanceof Error ? cause.message : '加载消息失败'
    } finally {
      loadingMessages.value = false
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
   * 处理一条待确认操作。
   *
   * @param approve true 确认执行，false 取消
   * @returns 执行结果描述，失败时返回 null
   */
  async function resolveConfirmation(
    confirmation: TaskConfirmation,
    approve: boolean,
  ): Promise<string | null> {
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
   * 发送一条消息并流式接收回复。
   *
   * 用户消息先乐观追加到本地列表（否则会出现「点发送后界面毫无反应」的观感），
   * 助手回复逐段追加到 `streamingContent`；结束后统一以服务端数据为准刷新。
   */
  async function send(content: string): Promise<void> {
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
    const optimisticUserMessage: ChatMessage = {
      id: -1,
      role: 'USER',
      content: trimmed,
      sequenceNo: messages.value.length + 1,
      createdAt: new Date().toISOString(),
    }
    messages.value = [...messages.value, optimisticUserMessage]
    streamingContent.value = ''
    streaming.value = true
    error.value = null
    abortController = new AbortController()

    try {
      await streamChat(
        conversationId,
        trimmed,
        {
          onDelta: (chunk) => {
            streamingContent.value += chunk
          },
          onDone: () => {
            // 清空临时内容，随后由服务端数据接管，避免出现重复渲染
            streamingContent.value = ''
          },
          onError: (message) => {
            error.value = message
            streamingContent.value = ''
          },
        },
        abortController.signal,
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
      if (conversationId) {
        try {
          messages.value = await listMessages(conversationId)
        } catch {
          // 刷新失败不影响本轮已展示的内容
        }
        await loadSessions()
        // 助手可能刚生成了待确认操作，本轮结束后立即拉一次
        await loadConfirmations()
      }
    }
  }

  /** 停止生成（客户端断开，服务端会取消上游订阅） */
  function stopStreaming(): void {
    abortController?.abort()
  }

  return {
    sessions,
    currentConversationId,
    messages,
    streamingContent,
    streaming,
    loadingSessions,
    loadingMessages,
    error,
    pendingConfirmations,
    loadSessions,
    createSession,
    selectSession,
    removeSession,
    send,
    stopStreaming,
    loadConfirmations,
    resolveConfirmation,
  }
})
