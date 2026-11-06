import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { isKnowledgeTab } from '@/lib/workspace-navigation'
import { useUiStore } from '@/stores/ui'
import router from '@/router'

vi.mock('@/api/auth', () => ({ fetchCurrentUser: vi.fn().mockResolvedValue({ authEnabled: false, username: null }) }))

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
})

describe('整合入口与旧链接兼容', () => {
  it.each([
    ['/memory?source=bookmark', '/knowledge', { tab: 'memory', source: 'bookmark' }],
    ['/usage?source=bookmark', '/activity', { view: 'usage', source: 'bookmark' }],
    ['/maintenance?source=bookmark', '/settings', { section: 'data', source: 'bookmark' }],
  ])('%s 保留上下文并定位新入口', async (url, path, query) => {
    await router.push(url)
    expect(router.currentRoute.value.path).toBe(path)
    expect(router.currentRoute.value.query).toEqual(query)
  })

  it('旧路径不会产生重定向循环，新入口可直接刷新访问', async () => {
    await router.push('/knowledge?tab=memory')
    expect(router.currentRoute.value.name).toBe('knowledge')
    await router.push('/activity?view=usage')
    expect(router.currentRoute.value.meta.title).toBe('运行中心')
    await router.push('/settings?section=data')
    expect(router.currentRoute.value.name).toBe('settings')
  })

  it('记忆视图可恢复，脏值回退到文档页', () => {
    localStorage.setItem('os.knowledge.tab', 'memory')
    expect(useUiStore().knowledgeTab).toBe('memory')
    setActivePinia(createPinia())
    localStorage.setItem('os.knowledge.tab', 'obsolete')
    expect(useUiStore().knowledgeTab).toBe('documents')
    expect(isKnowledgeTab(['memory'])).toBe(false)
  })
})
