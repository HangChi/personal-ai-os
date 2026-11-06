import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import SearchView from '@/views/SearchView.vue'
import { globalSearch } from '@/api/search'

vi.mock('@/api/search', () => ({ globalSearch: vi.fn() }))
let wrapper: VueWrapper
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(globalSearch).mockResolvedValue({ keyword: '项目', totalHits: 0, groups: [] })
})
afterEach(() => wrapper?.unmount())
async function open() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/search', component: SearchView }] })
  await router.push('/search?q=项目&source=header')
  wrapper = mount(SearchView, { global: { plugins: [createPinia(), router], stubs: { PageHeader: true } } })
  await flushPromises()
  return router
}
describe('搜索页表单与错误恢复', () => {
  it('失败时不显示无结果，保持同一关键词也能再次提交', async () => {
    vi.mocked(globalSearch).mockRejectedValueOnce(new Error('连接暂不可用'))
    await open()
    expect(wrapper.text()).toContain('连接暂不可用')
    expect(wrapper.text()).not.toContain('没有找到与')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(globalSearch).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('连接暂不可用')
    expect(wrapper.text()).toContain('没有找到与「项目」相关的内容')
  })
  it('修改关键词同步 URL 并保留上下文，路由切换可恢复查询', async () => {
    const router = await open()
    await wrapper.get('input[aria-label="搜索全部模块"]').setValue('会议')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ q: '会议', source: 'header' })
    expect(globalSearch).toHaveBeenLastCalledWith('会议')
    await router.push('/search?q=项目&source=header')
    await flushPromises()
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('项目')
    expect(globalSearch).toHaveBeenLastCalledWith('项目')
  })
})
