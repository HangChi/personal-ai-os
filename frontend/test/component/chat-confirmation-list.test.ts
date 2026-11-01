import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ChatConfirmationList from '@/components/chat/ChatConfirmationList.vue'
import type { TaskConfirmation } from '@/types/api'

const item: TaskConfirmation = {
  id: 'confirmation-1',
  token: 'test-token',
  action: 'DELETE_CALENDAR_EVENT',
  summary: '删除面试日程及关联提醒，确认后生效。',
  affectedCount: 2,
  status: 'PENDING',
  expiresAt: '2026-11-02T00:00:00Z',
  createdAt: '2026-11-01T00:00:00Z',
}

describe('ChatConfirmationList', () => {
  it('保留可读操作信息，取消与确认传递准确的人工决定', async () => {
    const wrapper = mount(ChatConfirmationList, { props: { items: [item], resolvingId: null } })
    expect(wrapper.get('section').attributes('aria-label')).toBe('待确认：删除日程')
    expect(wrapper.text()).toContain(item.summary)
    expect(wrapper.text()).toContain('涉及 2 项')
    const buttons = wrapper.findAll('button')
    expect(buttons[1].text()).toBe('确认删除')
    await buttons[0].trigger('click')
    await buttons[1].trigger('click')
    expect(wrapper.emitted('resolve')).toEqual([
      [item, false],
      [item, true],
    ])
    wrapper.unmount()
  })

  it('处理期间禁用全部确认操作，避免并发重复执行', async () => {
    const wrapper = mount(ChatConfirmationList, {
      props: { items: [item, { ...item, id: 'confirmation-2', action: 'MCP_TOOL_CALL' }], resolvingId: item.id },
    })
    for (const button of wrapper.findAll('button')) {
      expect(button.attributes('disabled')).toBeDefined()
      await button.trigger('click')
    }
    expect(wrapper.emitted('resolve')).toBeUndefined()
    expect(wrapper.findAll('button')[1].text()).toBe('正在处理')
    expect(wrapper.findAll('button')[1].attributes('aria-busy')).toBe('true')
    expect(wrapper.findAll('button')[3].text()).toBe('确认执行')
    wrapper.unmount()
  })
})
