import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'

import ChatSessionList from '@/components/chat/ChatSessionList.vue'
import {
  CHAT_SESSION_PANEL_DEFAULT_WIDTH,
  CHAT_SESSION_PANEL_MAX_WIDTH,
  CHAT_SESSION_PANEL_MIN_WIDTH,
  useUiStore,
} from '@/stores/ui'
import type { ChatSession } from '@/types/api'

const VIEW_SRC = readFileSync(resolve(process.cwd(), 'src/views/ChatView.vue'), 'utf8')

function makeSession(id: string, title: string): ChatSession {
  return {
    conversationId: id,
    title,
    messageCount: 2,
    updatedAt: new Date().toISOString(),
  } as ChatSession
}

const SESSIONS = [makeSession('c-1', '期中复习提纲'), makeSession('c-2', 'Excel 算平均分')]

function mountList(options: { currentId?: string | null; sessions?: ChatSession[] } = {}) {
  const { currentId = 'c-1', sessions = SESSIONS } = options
  return mount(ChatSessionList, {
    props: {
      sessions,
      currentId,
      keyword: '',
      total: sessions.length,
      page: 1,
      size: 20,
      loading: false,
    },
    global: {
      stubs: {
        AppPagination: true,
        ElCard: { template: '<div><slot name="header" /><slot /></div>' },
        ElInput: true,
        ElEmpty: true,
        ElButton: {
          props: ['icon', 'link', 'type', 'size'],
          template: '<button v-bind="$attrs"><slot /></button>',
        },
      },
    },
  })
}

describe('会话栏始终可见', () => {
  it('列表、搜索和分页不因宽度调整而从 DOM 消失', () => {
    const wrapper = mountList()

    expect(wrapper.find('.chat-sessions').exists()).toBe(true)
    expect(wrapper.find('.chat-sessions__filter').exists()).toBe(true)
    expect(wrapper.find('.chat-sessions__scroll').exists()).toBe(true)
    expect(wrapper.findAll('.chat-sessions__item')).toHaveLength(SESSIONS.length)
    expect(wrapper.find('.chat-sessions__rail').exists()).toBe(false)
  })

  it('头部不再提供收起按钮', () => {
    const wrapper = mountList()

    expect(wrapper.find('.chat-sessions__collapse').exists()).toBe(false)
    expect(wrapper.find('[aria-label="收起会话列表"]').exists()).toBe(false)
  })
})

describe('会话栏头部交互', () => {
  it('刷新和新建图标可操作，加载时禁止重复刷新', async () => {
    const wrapper = mountList()
    const refresh = wrapper.get('button[aria-label="刷新会话"]')
    const create = wrapper.get('button[aria-label="新建会话"]')

    expect(refresh.text()).toBe('')
    expect(create.text()).toBe('')
    await refresh.trigger('click')
    await create.trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(wrapper.emitted('create')).toHaveLength(1)
    await wrapper.setProps({ loading: true })
    await refresh.trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })
})

describe('ChatView 的会话栏宽度契约', () => {
  it('用 CSS 变量驱动会话栏列宽，并提供可访问的拖拽分隔线', () => {
    expect(VIEW_SRC).toMatch(/grid-template-columns: var\(--os-session-panel\) 1fr/)
    expect(VIEW_SRC).toMatch(/class="chat__session-resizer"/)
    expect(VIEW_SRC).toMatch(/role="separator"/)
    expect(VIEW_SRC).toMatch(/aria-label="调整会话栏宽度"/)
    expect(VIEW_SRC).toMatch(/@dblclick="resetSessionPanelWidth"/)
    expect(VIEW_SRC).not.toMatch(/chat--session-collapsed/)
    expect(VIEW_SRC).not.toMatch(/sessionPanelCollapsed/)
  })

  it('拖拽时关闭 grid 过渡，窄屏隐藏分隔线', () => {
    expect(VIEW_SRC).toMatch(/html\.os-resizing \.chat\s*\{\s*transition: none;/)
    expect(VIEW_SRC).toMatch(/\.chat__session-resizer\s*\{[\s\S]*?cursor: col-resize;/)
    expect(VIEW_SRC).toMatch(/\.chat__session-resizer\s*\{\s*display: none;/)
  })
})

describe('会话栏宽度偏好', () => {
  it('使用 220-420px 范围、默认 260px，并在刷新后恢复', async () => {
    setActivePinia(createPinia())
    localStorage.clear()

    const store = useUiStore()
    expect(store.sessionPanelWidth).toBe(CHAT_SESSION_PANEL_DEFAULT_WIDTH)

    store.sessionPanelWidth = CHAT_SESSION_PANEL_MIN_WIDTH
    await nextTick()
    expect(store.sessionPanelWidth).toBe(CHAT_SESSION_PANEL_MIN_WIDTH)
    expect(localStorage.getItem('os.chat.sessionPanel.width')).toBe(String(CHAT_SESSION_PANEL_MIN_WIDTH))

    store.sessionPanelWidth = CHAT_SESSION_PANEL_MAX_WIDTH
    await nextTick()
    expect(localStorage.getItem('os.chat.sessionPanel.width')).toBe(String(CHAT_SESSION_PANEL_MAX_WIDTH))

    setActivePinia(createPinia())
    expect(useUiStore().sessionPanelWidth).toBe(CHAT_SESSION_PANEL_MAX_WIDTH)
  })

  it('坏的旧值回落到默认宽度', () => {
    localStorage.setItem('os.chat.sessionPanel.width', 'not-a-width')
    setActivePinia(createPinia())

    expect(useUiStore().sessionPanelWidth).toBe(CHAT_SESSION_PANEL_DEFAULT_WIDTH)
  })
})
