import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ChatView from '@/views/ChatView.vue'
import ChatComposer from '@/components/chat/ChatComposer.vue'
import { useChatStore } from '@/stores/chat'
import { useSettingsStore } from '@/stores/settings'
import { useModelProfileStore } from '@/stores/model-profile'
afterEach(() => vi.restoreAllMocks())
describe('跟进处理读取设置', () => {
  it.each(['QUEUE', 'STEER'] as const)('设置为 %s 时直接按该方式发送，无需额外选择', async (mode) => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const chat = useChatStore()
    const settings = useSettingsStore()
    settings.settings.followupMode = mode
    chat.currentConversationId = 'c'
    chat.streaming = true
    chat.activeRun = { id: 'r', conversationId: 'c', status: 'RUNNING', events: [] }
    vi.spyOn(chat, 'loadSessions').mockResolvedValue()
    vi.spyOn(useModelProfileStore(), 'load').mockResolvedValue()
    vi.spyOn(chat, 'refreshContextUsage').mockResolvedValue()
    vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: vi.fn() }))
    const followup = vi.spyOn(chat, 'sendFollowup').mockResolvedValue(true)
    const wrapper = mount(ChatView, {
      global: {
        plugins: [pinia],
        stubs: { ChatComposer: true, ChatPlanPanel: true, ChatSessionList: true, ChatWelcome: true, AppLogo: true },
      },
    })
    await flushPromises()
    const composer = wrapper.getComponent(ChatComposer)
    composer.vm.$emit('update:modelValue', '请只保留三条结论')
    await flushPromises()
    const done = vi.fn()
    composer.vm.$emit('followup', [], done)
    await flushPromises()
    expect(followup).toHaveBeenCalledWith('请只保留三条结论', mode, expect.objectContaining({ attachments: [] }))
    expect(done).toHaveBeenCalledWith(true)
    wrapper.unmount()
  })
})
