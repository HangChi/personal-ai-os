import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

import ChatComposer from '@/components/chat/ChatComposer.vue'
import type { ContextUsage } from '@/api/chat'

/**
 * 输入区的交互契约。
 *
 * <p>选它做组件测试，是因为它集中了几条**只在真实按键事件下才成立**的规则：
 * 输入法选词期间不能发送、Shift+Enter 换行、两种 Enter 偏好，
 * 以及「正在生成」时按钮必须变成停止。这些规则纯函数测试完全覆盖不到，
 * 而写错的表现是「打中文时消息被吞」或「想换行却发出去了」—— 用户会立刻感知。
 */

/**
 * 上传接口 mock（拖拽 / 粘贴上传用）。
 *
 * <p>用 `vi.hoisted` 而不是在 `vi.mock` 工厂里直接 `vi.fn()`：工厂是惰性求值的，
 * 用例里要能拿到**同一个** mock 实例来断言「传上去了吗」。整个 `@/api/chat`
 * 被替换掉，所以语音输入用到的两个导出也要给一份 —— 本用例里它们不会被调用
 * （happy-dom 没有 MediaRecorder），但少了会 import 失败。
 */
const { uploadAttachment } = vi.hoisted(() => ({ uploadAttachment: vi.fn() }))

vi.mock('@/api/chat', () => ({
  uploadAttachment,
  fetchTranscriptionStatus: vi.fn(),
  transcribeAudio: vi.fn(),
}))

/** budgetTokens 为 0 时不渲染仪表盘，多数用例不需要它 */
const contextUsage: ContextUsage = {
  promptTokens: 0,
  budgetTokens: 0,
  contextWindow: 0,
  estimated: false,
}

function baseProps(overrides: Record<string, unknown> = {}) {
  return {
    modelValue: '',
    streaming: false,
    knowledgeOn: false,
    topK: 4,
    thinkingLevel: 'off' as const,
    enterSend: true,
    // 模型选择器与读数组都在第一行；不传会触发三条 required prop 警告，
    // 让人误以为输入区坏了（实测过：警告满屏，真正的失败反而被淹没）
    sessionModel: null,
    chatModels: ['qwen3.8-flash'],
    defaultModel: 'qwen3.8-flash',
    contextUsage,
    ...overrides,
  }
}

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((button) => button.text().includes(text))
}

describe('ChatComposer', () => {
  it('正在生成时按钮变成「停止」，点击只发 stop', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps({ streaming: true, modelValue: '内容' }) })

    expect(buttonByText(wrapper, '停止')).toBeTruthy()
    expect(buttonByText(wrapper, '发送')).toBeFalsy()

    await buttonByText(wrapper, '停止')!.trigger('click')

    expect(wrapper.emitted('stop')).toHaveLength(1)
    expect(wrapper.emitted('send')).toBeUndefined()
  })

  it('输入为空时发送按钮不可点（避免发出空消息）', () => {
    const wrapper = mount(ChatComposer, { props: baseProps({ modelValue: '   ' }) })

    expect(buttonByText(wrapper, '发送')!.attributes('disabled')).toBeDefined()
  })

  it('点击发送会带上当前附件列表（无附件时为空数组）', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps({ modelValue: '你好' }) })

    await buttonByText(wrapper, '发送')!.trigger('click')

    expect(wrapper.emitted('send')?.[0]).toEqual([[]])
  })

  it('enterSend=true：Enter 发送，Shift+Enter 换行不发送', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps({ modelValue: '你好' }) })
    const textarea = wrapper.find('textarea')

    await textarea.trigger('keydown', { key: 'Enter', shiftKey: true })
    expect(wrapper.emitted('send')).toBeUndefined()

    await textarea.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('send')).toHaveLength(1)
  })

  it('enterSend=false：Enter 不发送，Ctrl/Cmd+Enter 才发送', async () => {
    const wrapper = mount(ChatComposer, {
      props: baseProps({ modelValue: '你好', enterSend: false }),
    })
    const textarea = wrapper.find('textarea')

    await textarea.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('send')).toBeUndefined()

    await textarea.trigger('keydown', { key: 'Enter', ctrlKey: true })
    expect(wrapper.emitted('send')).toHaveLength(1)
  })

  it('输入法选词期间的 Enter 被忽略（否则打中文时按回车会把消息发出去）', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps({ modelValue: '你好' }) })

    // isComposing 由浏览器在 composition 期间置位，模拟时必须显式带上
    await wrapper.find('textarea').trigger('keydown', { key: 'Enter', isComposing: true })

    expect(wrapper.emitted('send')).toBeUndefined()
  })

  it('其他按键不触发发送', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps({ modelValue: '你好' }) })

    await wrapper.find('textarea').trigger('keydown', { key: 'a' })

    expect(wrapper.emitted('send')).toBeUndefined()
  })

  /*
   * 分行契约（2026-10-29 最终摆法，用户 feedback「上下文长度和 0/4000 的位置太丑了」后定）：
   *
   *   输入区本体 = textarea + 右下角字数（字数数的是草稿 → 属于输入框，有内容才显示）
   *   读数行   = 只剩上下文用量（会话级状态，靠左，不再与字数分居两端）
   *   工具行   = 控件（附件/知识库/模型/思考/语音/发送）
   *
   * <p>这几条断言守的是**布局契约**而不是视觉效果。判据因此是
   * 「字数在不在输入区里、读数行还剩谁、模型在不在动作组」，
   * 而不是任何主观描述。
   */
  it('读数行只剩上下文用量：字数在输入区右下角，都不含模型选择器', () => {
    const wrapper = mount(ChatComposer, {
      // budgetTokens 必须一起给：ContextMeter 在 budgetTokens=0 时整块不渲染，
      // 只覆盖 contextWindow 会得到「读数组不存在」的假红
      props: baseProps({
        modelValue: '12345',
        contextUsage: { ...contextUsage, promptTokens: 1024, budgetTokens: 32768, contextWindow: 32768 },
      }),
    })

    const metaRow = wrapper.find('.chat-composer__meta-row')
    expect(metaRow.exists()).toBe(true)
    // 上下文读数在读数行
    expect(metaRow.find('.context-meter').exists()).toBe(true)
    // 字数**不在**读数行 —— 它已移入输入区（两个孤儿分居两端就是被这次反馈掉的摆法）
    expect(metaRow.find('.chat-composer__count').exists()).toBe(false)
    // 模型选择器也不在读数行 —— 它已移入动作组
    expect(metaRow.find('.model-picker').exists()).toBe(false)

    // 字数住在输入区本体里，分母必须与 textarea 的 maxlength 一致，
    // 否则「还能打多少」是错的
    const maxlength = wrapper.find('textarea').attributes('maxlength')
    const count = wrapper.find('.chat-composer__input .chat-composer__count')
    expect(count.exists()).toBe(true)
    expect(count.text()).toBe(`5 / ${maxlength}`)
  })

  it('空草稿不显示字数：没有可数的东西，不占位也不显示 0 / 4000', () => {
    const wrapper = mount(ChatComposer, { props: baseProps({ modelValue: '' }) })

    expect(wrapper.find('.chat-composer__count').exists()).toBe(false)
  })

  it('模型选择器在第二行的动作组内（与思考 / 语音 / 发送同排）', () => {
    const wrapper = mount(ChatComposer, { props: baseProps() })

    const toolRow = wrapper.find('.chat-composer__tool-row')
    const actionGroup = toolRow.find('.chat-composer__action-group')
    expect(actionGroup.exists()).toBe(true)
    expect(actionGroup.find('.model-picker').exists()).toBe(true)
  })

  it('budgetTokens=0 时不渲染上下文读数（未产生用量时不留空壳）', () => {
    const wrapper = mount(ChatComposer, { props: baseProps() })

    expect(wrapper.find('.context-meter').exists()).toBe(false)
  })

  it('有预算时上下文读数渲染在读数行（该行现在只有它一个元素）', async () => {
    const usage: ContextUsage = { promptTokens: 4096, budgetTokens: 32768, contextWindow: 32768, estimated: false }
    const wrapper = mount(ChatComposer, { props: baseProps({ contextUsage: usage }) })

    const metaRow = wrapper.find('.chat-composer__meta-row')
    expect(metaRow.find('.context-meter').exists()).toBe(true)
    // 字数已移入输入区，空草稿（baseProps 默认）下不渲染
    expect(metaRow.find('.chat-composer__count').exists()).toBe(false)
  })
})

/*
 * ------------------------------------------------------------------ 拖拽 / 粘贴上传
 *
 * <p>守三条规则，全部是「错了用户立刻能感知、但纯函数测不到」的那类：
 * <ol>
 *   <li>拖进来要有蒙层（否则用户不知道松手会发生什么）；</li>
 *   <li>松手要真的把文件传给上传接口（蒙层只是皮，传文件才是里）；</li>
 *   <li>拖到页面别处松手不能把当前文档替换掉（浏览器默认行为，会丢整个会话）。</li>
 * </ol>
 *
 * <p>事件用 `new Event + dispatchEvent` 而不是 `wrapper.trigger`：VTU 对
 * drag/drop 这类事件的 options 支持不完整，`dataTransfer` 塞不进事件对象，
 * 而这里断言的恰恰是 `event.dataTransfer.files` 传对了没有。
 */
describe('ChatComposer 拖拽上传', () => {
  beforeEach(() => {
    uploadAttachment.mockReset()
    uploadAttachment.mockResolvedValue({ id: 'att-1', filename: 'a.txt', size: 12, contentType: 'text/plain' })
  })

  /** 在根元素上派发一个 drag* 事件（bubbles/cancelable 与真实事件一致） */
  async function fireDrag(wrapper: ReturnType<typeof mount>, type: string): Promise<void> {
    wrapper.find('.chat-composer').element.dispatchEvent(new Event(type, { bubbles: true, cancelable: true }))
    // 蒙层是 v-if 渲染的：dragging 置位后要等一个 tick 才进 DOM，
    // 不同步等会得到「状态对了但断言时还没渲染」的假红
    await nextTick()
  }

  it('拖入后出现「松开上传」蒙层，离开后消失', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps() })

    await fireDrag(wrapper, 'dragenter')
    expect(wrapper.find('.chat-composer__drop-mask').exists()).toBe(true)

    await fireDrag(wrapper, 'dragleave')
    expect(wrapper.find('.chat-composer__drop-mask').exists()).toBe(false)
  })

  it('在子元素之间移动不会误消蒙层（enter/leave 成对出现）', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps() })

    await fireDrag(wrapper, 'dragenter') // 进入根元素
    await fireDrag(wrapper, 'dragenter') // 继续进入子元素
    await fireDrag(wrapper, 'dragleave') // 离开子元素（仍在根元素内）
    // 用布尔值直接置 false 的实现会在这里闪没了 —— 这条守的是计数器
    expect(wrapper.find('.chat-composer__drop-mask').exists()).toBe(true)

    await fireDrag(wrapper, 'dragleave') // 真正离开根元素
    expect(wrapper.find('.chat-composer__drop-mask').exists()).toBe(false)
  })

  it('松手把文件逐个交给上传接口，蒙层随之消失', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps() })
    const file = new File(['内容'], '周报.txt', { type: 'text/plain' })

    fireDrag(wrapper, 'dragenter')
    const drop = new Event('drop', { bubbles: true, cancelable: true })
    Object.assign(drop, { dataTransfer: { files: [file] } })
    wrapper.find('.chat-composer').element.dispatchEvent(drop)
    await flushPromises()

    expect(uploadAttachment).toHaveBeenCalledWith(file)
    expect(wrapper.find('.chat-composer__drop-mask').exists()).toBe(false)
  })

  it('粘贴剪贴板里的文件同样走上传（截图后 Ctrl+V 的路径）', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps() })
    const file = new File(['图'], 'shot.png', { type: 'image/png' })

    const paste = new Event('paste', { bubbles: true, cancelable: true })
    Object.assign(paste, { clipboardData: { files: [file] } })
    wrapper.find('textarea').element.dispatchEvent(paste)
    await flushPromises()

    expect(uploadAttachment).toHaveBeenCalledWith(file)
  })

  it('纯文字粘贴不拦：输入框的本职行为不能被吞掉', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps() })

    const paste = new Event('paste', { bubbles: true, cancelable: true })
    Object.assign(paste, { clipboardData: { files: [] } })
    wrapper.find('textarea').element.dispatchEvent(paste)
    await flushPromises()

    expect(uploadAttachment).not.toHaveBeenCalled()
  })

  it('文件拖到页面别处松手：默认的「替换文档」被拦住', () => {
    mount(ChatComposer, { props: baseProps() })

    const drop = new Event('drop', { bubbles: true, cancelable: true })
    document.body.dispatchEvent(drop)

    // 浏览器默认会把文件 URL 替换当前文档 —— 整个会话状态清零
    expect(drop.defaultPrevented).toBe(true)
  })

  it('卸载时摘掉页面级监听：别在别的页面留副作用', () => {
    const wrapper = mount(ChatComposer, { props: baseProps() })

    /*
     * 用 spy 而不是「再派发一次 drop 看 defaultPrevented」：
     * 同文件前面的用例 mount 后没有卸载，它们的 document 监听还挂着，
     * 行为断言会被这些泄漏的监听判成 true —— 假红，且红的原因跟本用例无关。
     * spy 断言「注销调用发生过」，不受全局监听状态影响。
     */
    const removeSpy = vi.spyOn(document, 'removeEventListener')
    wrapper.unmount()

    expect(removeSpy).toHaveBeenCalledWith('drop', expect.any(Function))
    expect(removeSpy).toHaveBeenCalledWith('dragover', expect.any(Function))
    removeSpy.mockRestore()
  })
})

/*
 * ------------------------------------------------------------------ 视觉规格
 *
 * <p>只守**结构与类名契约**，不断言颜色/像素值 —— 后者会随主题令牌调整而假红，
 * 而这两条规则（按钮必须带图标、逼近上限必须挂预警类）是行为语义。
 */
describe('ChatComposer 视觉规格', () => {
  it('发送与停止按钮都带图标，不只靠文字表意', () => {
    const sending = mount(ChatComposer, { props: baseProps({ modelValue: '内容' }) })
    expect(buttonByText(sending, '发送')!.find('.el-icon').exists()).toBe(true)

    const stopping = mount(ChatComposer, { props: baseProps({ streaming: true, modelValue: '内容' }) })
    expect(buttonByText(stopping, '停止')!.find('.el-icon').exists()).toBe(true)
  })

  it('字数 ≥90% 时挂预警类（maxlength 是硬截断，超出的字直接消失）', () => {
    const wrapper = mount(ChatComposer, { props: baseProps({ modelValue: 'x'.repeat(3600) }) })

    expect(wrapper.find('.chat-composer__count--warn').exists()).toBe(true)
  })
})
