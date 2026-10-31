import { flushPromises, mount } from '@vue/test-utils'
import { ElDatePicker, ElSelect, ElSwitch } from 'element-plus'
import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'

import CalendarCreateForm from '@/components/calendar/CalendarCreateForm.vue'

const start = new Date('2026-11-02T01:00:00Z')
const end = new Date('2026-11-02T02:00:00Z')

async function mountForm() {
  const wrapper = mount(CalendarCreateForm, { props: { defaultLead: 15, creating: false } })
  await flushPromises()
  return wrapper
}

describe('新建日程表单', () => {
  it('月视图预填时间后，提交标题、地点、全天和重复设置', async () => {
    const wrapper = await mountForm()
    wrapper.vm.fillSlot(start, end)
    await wrapper.get('input[id$="-title"]').setValue('  每周例会  ')
    await wrapper.get('input[id$="-location"]').setValue('  会议室 A  ')
    wrapper.getComponent(ElSwitch).vm.$emit('update:modelValue', true)
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'WEEKLY')
    await nextTick()
    const until = new Date('2026-12-01T00:00:00Z')
    const pickers = wrapper.findAllComponents(ElDatePicker)
    expect(pickers).toHaveLength(2)
    pickers[1]!.vm.$emit('update:modelValue', until)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')?.[0]).toEqual([
      {
        title: '每周例会',
        location: '会议室 A',
        allDay: true,
        startsAt: start.toISOString(),
        endsAt: end.toISOString(),
        recurrenceType: 'WEEKLY',
        recurrenceUntil: until.toISOString(),
        reminderLeadMinutes: 15,
      },
    ])
    wrapper.unmount()
  })

  it('创建中不重复提交，也不清空已填内容', async () => {
    const wrapper = await mountForm()
    wrapper.vm.fillSlot(start, end)
    await wrapper.get('input[id$="-title"]').setValue('待提交日程')
    await wrapper.setProps({ creating: true })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')).toBeUndefined()
    expect(wrapper.get('input[id$="-title"]').element).toHaveProperty('value', '待提交日程')
    await wrapper.setProps({ creating: false })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')).toHaveLength(1)
    wrapper.unmount()
  })

  it('父组件确认成功后可重置字段，重复截止随重复设置收起', async () => {
    const wrapper = await mountForm()
    wrapper.vm.fillSlot(start, end)
    await wrapper.get('input[id$="-title"]').setValue('日程标题')
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'DAILY')
    await nextTick()
    expect(wrapper.find('.cal-create__repeat-until').exists()).toBe(true)
    wrapper.vm.reset()
    await nextTick()
    expect(wrapper.get('input[id$="-title"]').element).toHaveProperty('value', '')
    expect(wrapper.getComponent(ElDatePicker).props('modelValue')).toBeNull()
    expect(wrapper.getComponent(ElSwitch).props('modelValue')).toBe(false)
    expect(wrapper.find('.cal-create__repeat-until').exists()).toBe(false)
    wrapper.unmount()
  })
})
