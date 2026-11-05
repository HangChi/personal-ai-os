import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ChatComposer from '@/components/chat/ChatComposer.vue'
import ChatView from '@/views/ChatView.vue'
import { useChatStore } from '@/stores/chat'
import { useModelProfileStore } from '@/stores/model-profile'

const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})

async function open() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useChatStore()
  vi.spyOn(store, 'loadSessions').mockResolvedValue()
  vi.spyOn(store, 'refreshContextUsage').mockResolvedValue()
  vi.spyOn(useModelProfileStore(), 'load').mockResolvedValue()
  const create = vi.spyOn(store, 'createSession').mockImplementation(async () => {
    store.currentConversationId = 'new-session'
  })
  const change = vi.spyOn(store, 'setModel').mockResolvedValue(true)
  const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: vi.fn() }))
  const wrapper = mount(ChatView, {
    global: {
      plugins: [pinia],
      stubs: { ChatComposer: true, ChatSessionList: true, ChatWelcome: true },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  const pick = (model: string | null) => wrapper.getComponent(ChatComposer).vm.$emit('update:sessionModel', model)
  return { store, wrapper, create, change, error, pick }
}

describe('对话页切换模型', () => {
  it('首次选择模型创建会话时，文字和附件草稿身份保持不变', async () => {
    const { wrapper, pick } = await open()
    const composer = wrapper.getComponent(ChatComposer)
    const key = composer.props('draftKey')
    composer.vm.$emit('update:modelValue', '尚未发送的问题')
    await flushPromises()
    pick('alternate')
    await flushPromises()
    expect(composer.props('modelValue')).toBe('尚未发送的问题')
    expect(composer.props('draftKey')).toBe(key)
  })
  it('尚无会话时先创建，再把选择保存到新会话', async () => {
    const { create, change, pick } = await open()
    pick('alternate')
    await flushPromises()
    expect(create).toHaveBeenCalledOnce()
    expect(change).toHaveBeenCalledWith('new-session', 'alternate')
  })

  it('已有会话直接更新，并支持切回默认模型', async () => {
    const { store, create, change, pick } = await open()
    store.currentConversationId = 'existing'
    pick(null)
    await flushPromises()
    expect(create).not.toHaveBeenCalled()
    expect(change).toHaveBeenCalledWith('existing', null)
  })

  it('创建失败时显示错误，不继续保存模型', async () => {
    const { store, create, change, error, pick } = await open()
    create.mockImplementation(async () => {
      store.error = '服务暂不可用'
    })
    pick('alternate')
    await flushPromises()
    expect(change).not.toHaveBeenCalled()
    expect(error).toHaveBeenCalledWith('服务暂不可用')
  })

  it('保存期间忽略重复操作，结束后恢复选择器', async () => {
    const { store, wrapper, create, change, pick } = await open()
    let finish!: () => void
    create.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          finish = () => {
            store.currentConversationId = 'new-session'
            resolve()
          }
        }),
    )
    pick('alternate')
    pick('another')
    await flushPromises()
    expect(create).toHaveBeenCalledOnce()
    expect(wrapper.getComponent(ChatComposer).props('changingModel')).toBe(true)
    finish()
    await flushPromises()
    expect(change).toHaveBeenCalledOnce()
    expect(change).toHaveBeenCalledWith('new-session', 'alternate')
    expect(wrapper.getComponent(ChatComposer).props('changingModel')).toBe(false)
  })
})
