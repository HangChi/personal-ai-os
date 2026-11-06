import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, expect, it, vi } from 'vitest'
import { useChatStore } from '@/stores/chat'
import { followRun, latestRun, startRun, type ChatRun } from '@/api/chat-runs'
import { listMessages } from '@/api/chat'
import type { ChatMessage, RagRetrieval } from '@/types/api'

vi.mock('@/api/chat', async (original) => ({
  ...(await original<typeof import('@/api/chat')>()),
  listMessages: vi.fn(),
  listSessions: vi.fn(async () => ({ items: [], page: 0, size: 20, total: 0 })),
  fetchContextUsage: vi.fn(async () => ({
    promptTokens: 0,
    budgetTokens: 32000,
    contextWindow: 128000,
    estimated: true,
  })),
}))
vi.mock('@/api/chat-runs', async (original) => ({
  ...(await original<typeof import('@/api/chat-runs')>()),
  startRun: vi.fn(),
  followRun: vi.fn(),
  latestRun: vi.fn(),
}))
vi.mock('@/api/activity', () => ({ listToolInvocations: vi.fn(async () => ({ items: [], total: 0 })) }))
vi.mock('@/api/confirmations', () => ({
  listConfirmations: vi.fn(async () => []),
  cancelOperation: vi.fn(),
  confirmOperation: vi.fn(),
}))

const retrieval: RagRetrieval = {
  hits: [],
  queries: ['制度'],
  stages: [],
  warnings: [],
  status: 'READY',
  disagreements: [],
  clarification: '',
}
const message = (id: number, role: 'USER' | 'ASSISTANT', content: string): ChatMessage => ({
  id,
  role,
  content,
  sequenceNo: id,
  createdAt: '2026-11-06T00:00:00Z',
  citations: [],
  attachments: [],
})
beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

it('完成后绑定具体回复，下一轮刷新保留旧过程，切换会话清空实时来源', async () => {
  const store = useChatStore()
  store.currentConversationId = 'c'
  let persisted: ChatMessage[] = []
  let current: ChatRun
  let round = 0
  vi.mocked(listMessages).mockImplementation(async (id) =>
    structuredClone(id === 'c' ? persisted : [message(9, 'ASSISTANT', '另一个会话')]),
  )
  vi.mocked(latestRun).mockImplementation(async (id) => (id === 'c' ? current : null))
  vi.mocked(startRun).mockImplementation(async () => {
    current = { id: `r-${++round}`, conversationId: 'c', status: 'RUNNING', events: [] }
    return current
  })
  vi.mocked(followRun).mockImplementation(async (_run, handlers) => {
    expect(store.liveRag).toBeNull()
    const rag = { ...retrieval, queries: [`问题${round}`] }
    handlers.onRag?.(rag)
    handlers.onDelta(`回答${round}`)
    handlers.onDone(`回答${round}`)
    persisted = [
      ...persisted,
      message(round * 2 - 1, 'USER', `问题${round}`),
      message(round * 2, 'ASSISTANT', `回答${round}`),
    ]
    current = {
      ...current,
      status: 'COMPLETED',
      events: [
        { id: 1, name: 'rag', payload: JSON.stringify(rag) },
        { id: 2, name: 'done', payload: JSON.stringify({ content: `回答${round}` }) },
      ],
    }
  })
  await store.send('问题1', { useKnowledge: true })
  expect(store.messages[1]?.retrieval?.queries).toEqual(['问题1'])
  expect(store.liveRag).toBeNull()
  await store.send('问题2', { useKnowledge: true })
  expect(store.messages[1]?.retrieval?.queries).toEqual(['问题1'])
  expect(store.messages[3]?.retrieval?.queries).toEqual(['问题2'])
  await store.selectSession('other')
  expect(store.messages.map((item) => item.content)).toEqual(['另一个会话'])
  expect(store.messages[0]?.retrieval).toBeUndefined()
  expect(store.liveRag).toBeNull()
  await store.selectSession('c')
  expect(store.messages[3]?.retrieval?.queries).toEqual(['问题2'])
  expect(store.liveRag).toBeNull()
})

it('reset 后不把废弃检索过程绑定到恢复后的回答', async () => {
  const store = useChatStore()
  store.currentConversationId = 'c'
  vi.mocked(startRun).mockResolvedValue({ id: 'reset-run', conversationId: 'c', status: 'RUNNING', events: [] })
  vi.mocked(followRun).mockImplementation(async (_run, handlers) => {
    handlers.onRag?.(retrieval)
    handlers.onReset()
    handlers.onDelta('已恢复')
    handlers.onDone('已恢复')
  })
  vi.mocked(listMessages).mockResolvedValue([message(1, 'USER', '问题'), message(2, 'ASSISTANT', '已恢复')])
  vi.mocked(latestRun).mockResolvedValue(null)
  await store.send('问题', { useKnowledge: true })
  expect(store.messages[1]?.retrieval).toBeUndefined()
  expect(store.liveCitations).toEqual([])
})
