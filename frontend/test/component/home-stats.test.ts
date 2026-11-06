import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import { useKnowledgeStore } from '@/stores/knowledge'
import { listDocuments } from '@/api/knowledge'
import { listMemories } from '@/api/memory'
import { fetchTaskStats } from '@/api/tasks'
import { listOccurrences } from '@/api/calendar'

vi.mock('@/api/health', () => ({ fetchHealthStatus: vi.fn().mockResolvedValue({ status: 'UP' }) }))
vi.mock('@/api/knowledge', async (original) => ({ ...(await original<object>()), listDocuments: vi.fn() }))
vi.mock('@/api/memory', () => ({ listMemories: vi.fn() }))
vi.mock('@/api/tasks', () => ({ fetchTaskStats: vi.fn() }))
vi.mock('@/api/calendar', () => ({ listOccurrences: vi.fn() }))

const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(fetchTaskStats).mockResolvedValue({ pending: 0, overdue: 0 })
  vi.mocked(listMemories).mockResolvedValue([])
  vi.mocked(listDocuments).mockResolvedValue({ items: [], total: 100, page: 0, size: 1 })
  vi.mocked(listOccurrences).mockResolvedValue({ items: [], total: 3, page: 0, size: 1 })
})
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))

async function open() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const knowledge = useKnowledgeStore()
  knowledge.keyword = 'weekly-report'
  knowledge.page = 2
  knowledge.total = 2
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: HomeView }] })
  await router.push('/')
  const wrapper = mount(HomeView, {
    global: {
      plugins: [pinia, router],
      stubs: { PageHeader: true, TodayWorkspace: true },
      directives: { loading: () => {} },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  const card = (label: string) => wrapper.findAll('.home__stat').find((item) => item.text().includes(label))!
  return { wrapper, knowledge, card }
}

describe('首页独立统计', () => {
  it('从知识库筛选页返回首页仍显示全量总数，且保留原筛选和缓存', async () => {
    const { knowledge, card } = await open()
    expect(listDocuments).toHaveBeenCalledExactlyOnceWith({ page: 0, size: 1 })
    expect(card('知识文档').get('.home__stat-value').text()).toBe('100')
    expect(knowledge.keyword).toBe('weekly-report')
    expect(knowledge.page).toBe(2)
    expect(knowledge.total).toBe(2)
    expect(card('待办任务').get('.home__stat-value').text()).toBe('0')
    expect(card('今日日程').get('.home__stat-value').text()).toBe('3')
  })

  it('各来源失败显示 — 和失败提示，成功的零值保持 0，重试后能恢复', async () => {
    vi.mocked(listDocuments).mockRejectedValueOnce(new Error('offline'))
    vi.mocked(listMemories).mockRejectedValueOnce(new Error('offline'))
    vi.mocked(fetchTaskStats).mockRejectedValueOnce(new Error('offline'))
    const { card } = await open()
    for (const label of ['知识文档', '长期记忆', '待办任务', '已逾期']) {
      expect(card(label).get('.home__stat-value').text()).toBe('—')
      expect(card(label).text()).toContain('加载失败，点击重试')
    }
    expect(card('今日日程').get('.home__stat-value').text()).toBe('3')
    await card('知识文档').trigger('click')
    await flushPromises()
    expect(card('知识文档').get('.home__stat-value').text()).toBe('100')
    expect(listOccurrences).toHaveBeenCalledOnce()
    await card('待办任务').trigger('click')
    await flushPromises()
    expect(card('待办任务').get('.home__stat-value').text()).toBe('0')
    expect(card('已逾期').get('.home__stat-value').text()).toBe('0')
  })

  it('日程加载失败不影响其他卡，等待中的请求有独立加载态', async () => {
    vi.mocked(listOccurrences).mockRejectedValueOnce(new Error('offline'))
    let resolve!: (value: Awaited<ReturnType<typeof listDocuments>>) => void
    vi.mocked(listDocuments).mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    const { card } = await open()
    expect(card('今日日程').text()).toContain('加载失败')
    expect(card('知识文档').attributes('aria-busy')).toBe('true')
    expect(card('知识文档').text()).toContain('加载中')
    expect(card('长期记忆').get('.home__stat-value').text()).toBe('0')
    resolve({ items: [], total: 0, page: 0, size: 1 })
    await flushPromises()
    expect(card('知识文档').attributes('aria-busy')).toBe('false')
    expect(card('知识文档').get('.home__stat-value').text()).toBe('0')
  })
})
