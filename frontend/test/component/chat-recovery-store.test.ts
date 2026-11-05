import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useChatStore } from '@/stores/chat'
import { answerQuestion, followRun, resumeRun, submitFollowup, summarizeRun, type ChatRun } from '@/api/chat-runs'
vi.mock('@/api/chat-runs', async (original) => ({
  ...(await original<typeof import('@/api/chat-runs')>()),
  resumeRun: vi.fn(),
  summarizeRun: vi.fn(),
  answerQuestion: vi.fn(),
  submitFollowup: vi.fn(),
  followRun: vi.fn(),
}))
const run: ChatRun = { id: 'r', conversationId: 'c', status: 'INTERRUPTED', events: [], recoveryMode: 'SUMMARY_ONLY' }
beforeEach(() => {
  setActivePinia(createPinia())
  vi.resetAllMocks()
})
describe('安全恢复请求', () => {
  it.each(['answer', 'followup'] as const)('晚到的 %s 不会覆盖新会话的运行状态', async (kind) => {
    const store = useChatStore()
    store.currentConversationId = 'c'
    store.activeRun = {
      ...run,
      status: 'WAITING_USER',
      question: { id: 'q', runId: 'r', question: '选择哪项？', options: [], status: 'PENDING' },
    }
    let finish!: (value: ChatRun) => void
    const response = new Promise<ChatRun>((resolve) => {
      finish = resolve
    })
    vi.mocked(answerQuestion).mockReturnValue(response)
    vi.mocked(submitFollowup).mockReturnValue(response)
    const pending = kind === 'answer' ? store.respondToQuestion('回答') : store.sendFollowup('补充', 'STEER')
    store.currentConversationId = 'other'
    const other = { ...run, id: 'other-run', conversationId: 'other' }
    store.activeRun = other
    finish({ ...run, status: 'RUNNING' })
    expect(await pending).toBe(true)
    expect(store.activeRun).toEqual(other)
    expect(store.streaming).toBe(false)
    expect(followRun).not.toHaveBeenCalled()
  })
  it('普通继续不会绕过工具结果核对', async () => {
    const store = useChatStore()
    store.currentConversationId = 'c'
    store.activeRun = run
    await store.continueRun()
    expect(resumeRun).not.toHaveBeenCalled()
    expect(summarizeRun).not.toHaveBeenCalled()
    expect(store.error).toContain('工具结果待核实')
  })
  it('摘要使用独立接口，连续点击只提交一次，晚返回不覆盖其他会话', async () => {
    const store = useChatStore()
    store.currentConversationId = 'c'
    store.activeRun = run
    let finish!: (value: ChatRun) => void
    vi.mocked(summarizeRun).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const pending = store.continueRun(true)
    await store.continueRun(true)
    expect(summarizeRun).toHaveBeenCalledExactlyOnceWith('c', 'r')
    expect(resumeRun).not.toHaveBeenCalled()
    expect(store.recoveringRun).toBe(true)
    store.currentConversationId = 'other'
    const other = { ...run, id: 'other-run', conversationId: 'other' }
    store.activeRun = other
    finish({ ...run, status: 'RUNNING' })
    await pending
    expect(store.activeRun).toEqual(other)
    expect(store.streaming).toBe(false)
    expect(store.recoveringRun).toBe(false)
  })
  it('晚到的失败不会显示在新会话', async () => {
    const store = useChatStore()
    store.currentConversationId = 'c'
    store.activeRun = run
    let fail!: (reason: Error) => void
    vi.mocked(summarizeRun).mockImplementation(
      () =>
        new Promise((_resolve, reject) => {
          fail = reject
        }),
    )
    const pending = store.continueRun(true)
    store.currentConversationId = 'other'
    fail(new Error('原会话请求失败'))
    await pending
    expect(store.error).toBeNull()
    expect(store.recoveringRun).toBe(false)
  })
})
