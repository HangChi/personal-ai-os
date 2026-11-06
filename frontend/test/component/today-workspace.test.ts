import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import TodayWorkspace from '@/components/home/TodayWorkspace.vue'
import { listTasks, completeTask } from '@/api/tasks'
import { listOccurrences } from '@/api/calendar'
import { listConfirmations } from '@/api/confirmations'
import { unfinishedRuns } from '@/api/workspace'
import type { TaskItem } from '@/types/api'
vi.mock('@/api/tasks', () => ({ listTasks: vi.fn(), completeTask: vi.fn() }))
vi.mock('@/api/calendar', () => ({ listOccurrences: vi.fn() }))
vi.mock('@/api/confirmations', () => ({ listConfirmations: vi.fn() }))
vi.mock('@/api/workspace', () => ({ unfinishedRuns: vi.fn() }))
const task: TaskItem = {
  id: 'task-1',
  title: '准备今日周报',
  description: null,
  status: 'TODO',
  priority: 'HIGH',
  tags: [],
  dueAt: new Date().toISOString(),
  completedAt: null,
  createdAt: '',
  updatedAt: '',
  overdue: false,
}
const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listTasks).mockResolvedValue({ items: [task], total: 1, page: 0, size: 20 })
  vi.mocked(listOccurrences).mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
  vi.mocked(listConfirmations).mockResolvedValue([])
  vi.mocked(unfinishedRuns).mockResolvedValue({
    items: [{ id: 'r1', conversationId: 'conversation-1', title: '暂停的工作', status: 'STOPPED', updatedAt: '' }],
    total: 1,
  })
  vi.mocked(completeTask).mockResolvedValue({ ...task, status: 'DONE' })
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
})
async function open() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ['/', '/tasks', '/chat', '/calendar', '/activity'].map((path) => ({
      path,
      component: { template: '<div />' },
    })),
  })
  await router.push('/')
  const wrapper = mount(TodayWorkspace, { global: { plugins: [router] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}
describe('今日工作台', () => {
  it('独立读取有界时间筛选，完成任务后刷新两个任务分组', async () => {
    const { wrapper } = await open()
    expect(listTasks).toHaveBeenCalledWith(
      expect.objectContaining({
        incomplete: true,
        size: 20,
        page: 0,
        dueFrom: expect.any(String),
        dueBefore: expect.any(String),
      }),
    )
    expect(listTasks).toHaveBeenCalledWith(
      expect.objectContaining({ incomplete: true, dueFrom: undefined, dueBefore: expect.any(String) }),
    )
    const button = wrapper.findAll('button').find((b) => b.text() === '完成')!
    await button.trigger('click')
    await flushPromises()
    expect(completeTask).toHaveBeenCalledExactlyOnceWith('task-1')
    expect(listTasks).toHaveBeenCalledTimes(4)
  })
  it('来源失败独立显示，可重试，不覆盖其他分组', async () => {
    vi.mocked(listTasks).mockImplementation(async (filter) => {
      if (filter?.dueFrom) throw new Error('今日任务离线')
      return { items: [], total: 0, page: 0, size: 20 }
    })
    const { wrapper } = await open()
    expect(wrapper.text()).toContain('今日任务离线')
    expect(wrapper.text()).toContain('暂停的工作')
    expect(wrapper.text()).toContain('暂无今日日程')
    vi.mocked(listTasks).mockResolvedValue({ items: [task], total: 1, page: 0, size: 20 })
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '重试今日任务')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('今日任务离线')
    expect(wrapper.text()).toContain(task.title)
  })
  it('未结束执行点击定位对应会话，不自动恢复', async () => {
    const { wrapper, router } = await open()
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('暂停的工作'))!
      .trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/chat')
    expect(router.currentRoute.value.query.conversationId).toBe('conversation-1')
  })
  it('显示总数和截断说明，并可定位任务编辑', async () => {
    vi.mocked(listTasks).mockResolvedValue({ items: [task], total: 42, page: 0, size: 20 })
    const { wrapper, router } = await open()
    expect(wrapper.text()).toContain('显示前 20 项，共 42 项')
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes(task.title))!
      .trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.task).toBe(task.id)
  })
  it('已过期待确认不进入工作台，点击有效确认定位会话', async () => {
    vi.mocked(listConfirmations).mockResolvedValue([
      {
        id: 'expired',
        token: 'x',
        summary: '过期操作',
        action: 'DELETE',
        affectedCount: 1,
        status: 'PENDING',
        expiresAt: '2000-01-01T00:00:00Z',
        createdAt: '',
      },
      {
        id: 'pending',
        token: 'x',
        summary: '确认删除旧任务',
        action: 'DELETE',
        affectedCount: 2,
        status: 'PENDING',
        expiresAt: '2099-01-01T00:00:00Z',
        createdAt: '',
        conversationId: 'confirm-session',
      },
    ])
    const { wrapper, router } = await open()
    expect(wrapper.text()).not.toContain('过期操作')
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('确认删除旧任务'))!
      .trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.conversationId).toBe('confirm-session')
  })
})
