import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ChatComposer from '@/components/chat/ChatComposer.vue'
import ChatView from '@/views/ChatView.vue'
import { useChatStore } from '@/stores/chat'
import { useChatCommandsStore } from '@/stores/chat-commands'
import { useModelProfileStore } from '@/stores/model-profile'
const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})
async function open() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const chat = useChatStore(),
    commands = useChatCommandsStore()
  vi.spyOn(chat, 'loadSessions').mockResolvedValue()
  vi.spyOn(chat, 'refreshContextUsage').mockResolvedValue()
  vi.spyOn(useModelProfileStore(), 'load').mockResolvedValue()
  vi.spyOn(commands, 'loadCatalog').mockResolvedValue()
  vi.spyOn(chat, 'loadConfirmations').mockResolvedValue()
  vi.spyOn(chat, 'loadToolTrail').mockResolvedValue()
  const send = vi.spyOn(chat, 'send').mockResolvedValue()
  const execute = vi.spyOn(commands, 'execute').mockResolvedValue({
    requestId: 'id',
    tool: 'createTask',
    replayed: false,
    result: { status: 'REQUIRES_CONFIRMATION', message: '请确认', data: null, confirmationId: 'confirmation' },
  })
  const stop = vi.spyOn(chat, 'stopStreaming').mockResolvedValue()
  vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: vi.fn() }))
  const wrapper = mount(ChatView, {
    global: { plugins: [pinia], stubs: { ChatComposer: true, ChatSessionList: true, ChatWelcome: true } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  chat.currentConversationId = 'c'
  await flushPromises()
  const composer = wrapper.getComponent(ChatComposer)
  const command = async (input: string) => {
    composer.vm.$emit('update:modelValue', input)
    await flushPromises()
    composer.vm.$emit('command', input)
    await flushPromises()
  }
  return { wrapper, composer, chat, commands, send, execute, stop, command }
}
describe('对话页快捷操作编排', () => {
  it('直接调用工具并刷新待确认列表，不发送模型消息', async () => {
    const { chat, execute, send, command, composer } = await open()
    await command('/task 买牛奶')
    expect(execute).toHaveBeenCalledWith('c', '/task 买牛奶', 'createTask', { title: '买牛奶' })
    expect(send).not.toHaveBeenCalled()
    expect(chat.loadConfirmations).toHaveBeenCalled()
    expect(chat.loadToolTrail).toHaveBeenCalled()
    expect(composer.props('modelValue')).toBe('')
  })
  it('未知指令保留草稿，/help 显示帮助，/stop 调用现有停止动作', async () => {
    const { wrapper, execute, send, stop, command, composer } = await open()
    await command('/typo')
    expect(composer.props('modelValue')).toBe('/typo')
    expect(execute).not.toHaveBeenCalled()
    expect(send).not.toHaveBeenCalled()
    await command('/help')
    expect(wrapper.text()).toContain('/delete-task')
    await command('/stop')
    expect(stop).toHaveBeenCalledOnce()
  })
  it('请求丢失保留草稿，晚到结果不清除新会话草稿', async () => {
    const { execute, command, composer, chat } = await open()
    execute.mockResolvedValueOnce(null)
    await command('/task 保留')
    expect(composer.props('modelValue')).toBe('/task 保留')
    let finish!: (value: null) => void
    execute.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    composer.vm.$emit('command', '/task 保留')
    await flushPromises()
    chat.currentConversationId = 'other'
    composer.vm.$emit('update:modelValue', '另一份草稿')
    await flushPromises()
    finish(null)
    await flushPromises()
    expect(composer.props('modelValue')).toBe('另一份草稿')
  })
  it('本地动作失败保留指令草稿', async () => {
    const { chat, stop, command, composer } = await open()
    stop.mockImplementation(async () => {
      chat.error = '停止失败'
    })
    await command('/stop')
    expect(composer.props('modelValue')).toBe('/stop')
    vi.spyOn(chat, 'createSession').mockImplementation(async () => {
      chat.error = '新建失败'
    })
    await command('/new')
    expect(composer.props('modelValue')).toBe('/new')
  })
})
