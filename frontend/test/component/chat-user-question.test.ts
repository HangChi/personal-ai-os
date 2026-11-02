import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ChatUserQuestion from '@/components/chat/ChatUserQuestion.vue'
import type { AgentQuestion } from '@/api/chat-runs'
const question: AgentQuestion = {
  id: 'q',
  runId: 'r',
  question: '报告按什么方式组织？',
  options: [
    { label: '按项目', description: '按项目聚合进展' },
    { label: '按日期', description: '按时间查看进度' },
  ],
  status: 'PENDING',
}
describe('结构化澄清问题', () => {
  it('选项直接提交一次，随后禁用以防重复回答', async () => {
    const wrapper = mount(ChatUserQuestion, { props: { question } })
    expect(wrapper.text()).toContain('按项目聚合进展')
    const button = wrapper.findAll('.user-question__options button')[0]
    await button.trigger('click')
    await button.trigger('click')
    expect(wrapper.emitted('answer')).toEqual([['按项目']])
    expect(button.attributes('disabled')).toBeDefined()
    await flushPromises()
    wrapper.unmount()
  })
  it('自由输入保留具体意图，空白不能提交，失败后允许重试', async () => {
    const wrapper = mount(ChatUserQuestion, { props: { question } })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('answer')).toBeUndefined()
    await wrapper.get('textarea').setValue('  按项目，但只显示本周风险  ')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('answer')).toEqual([['按项目，但只显示本周风险']])
    await wrapper.setProps({ busy: true })
    await wrapper.setProps({ busy: false, error: '网络异常' })
    expect(wrapper.text()).toContain('网络异常')
    expect(wrapper.get('textarea').element.value).toContain('只显示本周风险')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('answer')).toHaveLength(2)
    await flushPromises()
    wrapper.unmount()
  })
  it('新问题清空旧草稿，无选项时支持直接回答', async () => {
    const wrapper = mount(ChatUserQuestion, { props: { question } })
    await wrapper.get('textarea').setValue('旧草稿')
    await wrapper.setProps({ question: { ...question, id: 'new', question: '截止时间是什么？', options: [] } })
    expect(wrapper.get('textarea').element.value).toBe('')
    expect(wrapper.find('.user-question__options').exists()).toBe(false)
    await flushPromises()
    wrapper.unmount()
  })
})
