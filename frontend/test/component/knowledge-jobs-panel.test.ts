import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { KnowledgeIndexJob } from '@/api/knowledge'
import KnowledgeDocumentsTab from '@/components/knowledge/KnowledgeDocumentsTab.vue'
import { useKnowledgeStore } from '@/stores/knowledge'

const wrappers: VueWrapper[] = []
const jobs: KnowledgeIndexJob[] = ['PENDING', 'RUNNING', 'FAILED', 'SUCCEEDED'].map((status, index) => ({
  id: `job-${index}`,
  documentId: `doc-${index}`,
  replacing: false,
  filename: `资料-${index}.md`,
  status: status as KnowledgeIndexJob['status'],
  attempts: status === 'FAILED' ? 2 : 1,
  progress: status === 'SUCCEEDED' ? 100 : 20,
  stage: status === 'FAILED' ? '索引失败' : '处理文档',
  error: status === 'FAILED' ? '请检查模型配置' : null,
}))

function render(items: KnowledgeIndexJob[] = jobs, error: string | null = null) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useKnowledgeStore()
  store.indexJobs = items.map((job) => ({ ...job }))
  store.jobsError = error
  vi.spyOn(store, 'loadDocuments').mockResolvedValue()
  const loadJobs = vi.spyOn(store, 'loadJobs').mockResolvedValue()
  const wrapper = mount(KnowledgeDocumentsTab, { attachTo: document.body, global: { plugins: [pinia] } })
  wrappers.push(wrapper)
  return { wrapper, store, loadJobs }
}

afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('后台索引任务折叠面板', () => {
  it('默认收起明细，保留完整状态摘要和刷新入口', () => {
    const { wrapper } = render()
    expect(wrapper.get('.job-toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('.job-toggle').attributes('aria-controls')).toBe(wrapper.get('.job-details').attributes('id'))
    expect(wrapper.get('[role="status"]').text()).toBe('共 4 项 · 2 项进行中 · 1 项失败 · 1 项已完成')
    expect(wrapper.get('.index-jobs').isVisible()).toBe(false)
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '刷新进度')
        ?.isVisible(),
    ).toBe(true)
  })

  it('展开保留进度、重试和错误详情，刷新与状态变化不重置用户选择', async () => {
    const { wrapper, store, loadJobs } = render()
    const toggle = wrapper.get('.job-toggle')
    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(toggle.text()).toContain('收起详情')
    expect(wrapper.get('.index-jobs').isVisible()).toBe(true)
    expect(wrapper.findAll('.index-jobs li')).toHaveLength(4)
    expect(wrapper.get('.job-error').text()).toBe('请检查模型配置')
    expect(wrapper.get('.index-jobs').text()).toContain('第 2 次尝试')
    const refresh = wrapper.findAll('button').find((button) => button.text() === '刷新进度')!
    await refresh.trigger('click')
    expect(loadJobs).toHaveBeenCalledTimes(2)
    expect(toggle.attributes('aria-expanded')).toBe('true')
    await toggle.trigger('click')
    store.indexJobs = store.indexJobs.map((job) => ({ ...job, status: 'SUCCEEDED', progress: 100 }))
    await flushPromises()
    await refresh.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('.index-jobs').isVisible()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toBe('共 4 项 · 4 项已完成')
  })

  it('收起时仍展示查询错误，没有已知任务时也能刷新重试', () => {
    const { wrapper } = render([], '进度查询失败，请刷新重试')
    expect(wrapper.get('.job-toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('.job-feedback').isVisible()).toBe(true)
    expect(wrapper.get('.job-feedback').text()).toContain('进度查询失败，请刷新重试')
    expect(wrapper.get('.job-summary').text()).toBe('暂未获取任务')
  })

  it('没有任务或查询错误时不占用页面空间', () => {
    const { wrapper } = render([])
    expect(wrapper.find('.job-card').exists()).toBe(false)
  })

  it('收起后继续轮询，任务结束或组件卸载后不再请求', async () => {
    vi.useFakeTimers()
    const { wrapper, store, loadJobs } = render()
    await vi.advanceTimersByTimeAsync(2000)
    expect(loadJobs).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.job-toggle').attributes('aria-expanded')).toBe('false')
    store.indexJobs = store.indexJobs.map((job) => ({ ...job, status: 'SUCCEEDED' }))
    await vi.advanceTimersByTimeAsync(2000)
    expect(loadJobs).toHaveBeenCalledTimes(2)
    store.indexJobs[0]!.status = 'RUNNING'
    wrapper.unmount()
    wrappers.splice(wrappers.indexOf(wrapper), 1)
    await vi.advanceTimersByTimeAsync(4000)
    expect(loadJobs).toHaveBeenCalledTimes(2)
  })
})
