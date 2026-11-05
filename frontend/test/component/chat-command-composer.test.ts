import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import ChatComposer from '@/components/chat/ChatComposer.vue'
const { uploadAttachment } = vi.hoisted(() => ({ uploadAttachment: vi.fn() }))
vi.mock('@/api/chat', () => ({ uploadAttachment, fetchTranscriptionStatus: vi.fn(), transcribeAudio: vi.fn() }))
const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})
function open(value: string, overrides: Record<string, unknown> = {}) {
  const wrapper = mount(ChatComposer, {
    props: {
      modelValue: value,
      streaming: false,
      knowledgeOn: false,
      topK: 4,
      thinkingLevel: 'off',
      enterSend: true,
      sessionModel: null,
      chatModels: [],
      defaultModel: '',
      contextUsage: { promptTokens: 0, budgetTokens: 0, contextWindow: 0, estimated: false },
      ...overrides,
    },
  })
  wrappers.push(wrapper)
  return wrapper
}
describe('输入区快捷指令', () => {
  it('键盘选择和 Tab 仅补全，不执行；输入法按键不触发', async () => {
    const wrapper = open('/ta')
    const input = wrapper.get('textarea')
    await input.trigger('focus')
    expect(wrapper.findAll('[role=option]')).toHaveLength(2)
    await input.trigger('keydown', { key: 'ArrowDown', isComposing: true })
    expect(wrapper.findAll('[role=option]')[0]?.attributes('aria-selected')).toBe('true')
    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(wrapper.findAll('[role=option]')[1]?.attributes('aria-selected')).toBe('true')
    await input.trigger('keydown', { key: 'Tab' })
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['/task '])
    expect(wrapper.emitted('command')).toBeUndefined()
    expect(wrapper.emitted('send')).toBeUndefined()
  })
  it('部分指令 Enter 补全，完整指令 Enter 直接调用', async () => {
    const wrapper = open('/ta')
    await wrapper.get('textarea').trigger('focus')
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('command')).toBeUndefined()
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['/tasks '])
    await wrapper.setProps({ modelValue: '/tasks' })
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('command')?.[0]).toEqual(['/tasks'])
    expect(wrapper.emitted('send')).toBeUndefined()
  })
  it('Esc 关闭候选，鼠标选择也只补全', async () => {
    const wrapper = open('/')
    await wrapper.get('textarea').trigger('focus')
    await wrapper.get('[role=option]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['/help '])
    expect(wrapper.emitted('command')).toBeUndefined()
    await wrapper.setProps({ modelValue: '/ta' })
    await wrapper.get('textarea').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role=listbox]').exists()).toBe(false)
  })
  it.each(['/task', '/tool'])('完整指令 %s 不会被相似指令替换', async (value) => {
    const wrapper = open(value)
    await wrapper.get('textarea').trigger('focus')
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('command')?.[0]).toEqual([value])
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
  it('执行中 /stop 路由为指令，绝不会排成跟进消息', async () => {
    const wrapper = open('/stop', { streaming: true })
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('command')?.[0]).toEqual(['/stop'])
    expect(wrapper.emitted('followup')).toBeUndefined()
    expect(wrapper.emitted('send')).toBeUndefined()
    expect(wrapper.findAll('button').some((button) => button.text() === '执行')).toBe(true)
  })
  it('保留 Ctrl+Enter 偏好，防止 IME 误执行，忙碌时不重复调用', async () => {
    const wrapper = open('/task 一', { enterSend: false })
    const input = wrapper.get('textarea')
    await input.trigger('keydown', { key: 'Enter' })
    await input.trigger('keydown', { key: 'Enter', ctrlKey: true, isComposing: true })
    expect(wrapper.emitted('command')).toBeUndefined()
    await input.trigger('keydown', { key: 'Enter', ctrlKey: true })
    expect(wrapper.emitted('command')).toHaveLength(1)
    await wrapper.setProps({ commandBusy: true })
    await input.trigger('keydown', { key: 'Enter', ctrlKey: true })
    expect(wrapper.emitted('command')).toHaveLength(1)
  })
  it('双斜线作为普通聊天，快捷入口不会覆盖已有草稿', async () => {
    const wrapper = open('//path')
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('send')).toHaveLength(1)
    expect(wrapper.emitted('command')).toBeUndefined()
    expect(wrapper.get('[aria-label=打开快捷指令]').attributes('disabled')).toBeDefined()
  })
  it('有附件的快捷指令不执行且保留草稿和附件', async () => {
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: vi.fn() }))
    uploadAttachment.mockResolvedValue({ id: 'attachment', filename: 'draft.txt', contentType: 'text/plain', size: 4 })
    const wrapper = open('/task 保留')
    await wrapper.get('textarea').trigger('paste', { clipboardData: { files: [new File(['keep'], 'draft.txt')] } })
    await flushPromises()
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    expect(warning).toHaveBeenCalledWith(expect.stringContaining('不支持附件'))
    expect(wrapper.emitted('command')).toBeUndefined()
    expect(wrapper.emitted('send')).toBeUndefined()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.text()).toContain('draft.txt')
  })
})
