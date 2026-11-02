import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ChatFollowupQueue from '@/components/chat/ChatFollowupQueue.vue'
import type { AgentFollowup } from '@/api/chat-runs'
const item: AgentFollowup = {
  id: '1',
  runId: 'r',
  conversationId: 'c',
  mode: 'QUEUE',
  request: { content: '完成后再生成摘要' },
  status: 'PENDING',
}
describe('排队消息操作', () => {
  it('取消和引导均为单次操作，传递原消息标识', async () => {
    const wrapper = mount(ChatFollowupQueue, { props: { items: [item] } })
    await wrapper.get('[aria-label^="用此消息引导"]').trigger('click')
    await wrapper.get('[aria-label^="取消跟进"]').trigger('click')
    expect(wrapper.emitted('steer')).toEqual([['1']])
    expect(wrapper.emitted('remove')).toEqual([['1']])
    wrapper.unmount()
  })
  it('编辑失败保留草稿，保存成功后退出；取消编辑不改变原消息', async () => {
    const wrapper = mount(ChatFollowupQueue, { props: { items: [item] } })
    await wrapper.get('[aria-label^="编辑排队"]').trigger('click')
    await wrapper.get('textarea').setValue('只生成三条摘要')
    await wrapper.get('form').trigger('submit')
    const result = wrapper.emitted('edit')![0]
    expect(result.slice(0, 2)).toEqual(['1', '只生成三条摘要'])
    ;(result[2] as (success: boolean) => void)(false)
    await wrapper.setProps({ error: '保存失败' })
    expect(wrapper.get('textarea').element.value).toBe('只生成三条摘要')
    expect(wrapper.text()).toContain('保存失败')
    ;(result[2] as (success: boolean) => void)(true)
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    await wrapper.get('[aria-label^="编辑排队"]').trigger('click')
    await wrapper.get('textarea').setValue('未保存的改动')
    await wrapper.get('textarea').trigger('keydown', { key: 'Escape' })
    expect(wrapper.text()).toContain(item.request.content)
    expect(wrapper.emitted('edit')).toHaveLength(1)
    await flushPromises()
    wrapper.unmount()
  })
  it('定时刷新不覆盖编辑草稿，处理期间禁用操作', async () => {
    const wrapper = mount(ChatFollowupQueue, { props: { items: [item] } })
    await wrapper.get('[aria-label^="编辑排队"]').trigger('click')
    await wrapper.get('textarea').setValue('草稿')
    await wrapper.setProps({ items: [{ ...item }], busy: true })
    expect(wrapper.get('textarea').element.value).toBe('草稿')
    for (const button of wrapper.findAll('button')) expect(button.attributes('disabled')).toBeDefined()
    await flushPromises()
    wrapper.unmount()
  })
})
