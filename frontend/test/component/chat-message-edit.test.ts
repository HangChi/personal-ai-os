import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ChatView from '@/views/ChatView.vue'
import { useChatStore } from '@/stores/chat'
import { useModelProfileStore } from '@/stores/model-profile'
import type { ChatMessage } from '@/types/api'

const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})

async function open() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useChatStore()
  store.currentConversationId = 'session'
  const message = {
    id: 1,
    role: 'USER',
    sequenceNo: 1,
    content: '原始消息\n第二行',
    createdAt: new Date().toISOString(),
    attachments: [],
    citations: [],
  } as ChatMessage
  store.messages = [message]
  vi.spyOn(store, 'loadSessions').mockResolvedValue()
  vi.spyOn(store, 'refreshContextUsage').mockResolvedValue()
  vi.spyOn(useModelProfileStore(), 'load').mockResolvedValue()
  const resend = vi.spyOn(store, 'editAndResend').mockResolvedValue(true)
  const prompt = vi.spyOn(ElMessageBox, 'prompt')
  const wrapper = mount(ChatView, {
    global: {
      plugins: [pinia],
      stubs: {
        ChatComposer: true,
        ChatSessionList: true,
        ChatWelcome: true,
        ChatPlanPanel: true,
        AppLogo: true,
      },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  await wrapper.get('[aria-label="编辑并重发这条消息"]').trigger('click')
  await flushPromises()
  return { store, message, wrapper, resend, prompt }
}

describe('消息行内编辑', () => {
  it('直接在消息处编辑，取消保留原消息且不弹框、不发请求', async () => {
    const { store, wrapper, resend, prompt } = await open()
    expect(wrapper.get('textarea').element.value).toBe('原始消息\n第二行')
    await wrapper.get('textarea').setValue('修改草稿')
    await wrapper.get('textarea').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(store.messages[0].content).toBe('原始消息\n第二行')
    expect(resend).not.toHaveBeenCalled()
    expect(prompt).not.toHaveBeenCalled()
  })

  it('保留多行输入，保存只重发一次', async () => {
    const { message, wrapper, resend, prompt } = await open()
    await wrapper.get('textarea').setValue('新内容\n新第二行')
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    expect(resend).not.toHaveBeenCalled()
    await wrapper.get('form[aria-label="编辑消息"]').trigger('submit')
    await flushPromises()
    expect(resend).toHaveBeenCalledExactlyOnceWith(message, '新内容\n新第二行')
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(prompt).not.toHaveBeenCalled()
  })

  it('回退失败保留编辑内容，空白消息不能提交', async () => {
    const { wrapper, resend } = await open()
    resend.mockResolvedValue(false)
    await wrapper.get('textarea').setValue('尚未保存的草稿')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('尚未保存的草稿')
    await wrapper.get('textarea').setValue('   ')
    await wrapper.get('form').trigger('submit')
    expect(resend).toHaveBeenCalledOnce()
  })

  it('提交期间防止重复重发，切换会话退出编辑', async () => {
    const { store, wrapper, resend } = await open()
    let finish!: (value: boolean) => void
    resend.mockImplementation(
      () =>
        new Promise<boolean>((resolve) => {
          finish = resolve
        }),
    )
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(resend).toHaveBeenCalledOnce()
    expect(wrapper.get('textarea').attributes('disabled')).toBeDefined()
    finish(false)
    await flushPromises()
    store.currentConversationId = 'other-session'
    await flushPromises()
    expect(wrapper.find('textarea').exists()).toBe(false)
  })
})
