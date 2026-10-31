import { flushPromises, mount } from '@vue/test-utils'
import { ElDatePicker, ElSelect } from 'element-plus'
import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'

import TaskCreateForm from '@/components/task/TaskCreateForm.vue'

async function mountForm() {
  const wrapper = mount(TaskCreateForm, {
    props: {
      tags: ['工作', '个人'],
      pendingCount: 7,
      overdueCount: 2,
      creating: false,
    },
  })
  await flushPromises()
  return wrapper
}

describe('新建任务表单', () => {
  it('展示字段标签，并提交标题、标签、优先级和截止时间', async () => {
    const wrapper = await mountForm()
    const dueDate = new Date('2026-11-06T09:30:00Z')

    expect(wrapper.get('label[for$="-title"]').text()).toContain('任务标题')
    expect(wrapper.get('label[for$="-tags"]').text()).toContain('标签')
    expect(wrapper.get('label[for$="-priority"]').text()).toContain('优先级')
    expect(wrapper.get('label[for$="-due"]').text()).toContain('截止时间')

    await wrapper.get('input[id$="-title"]').setValue('  准备周报  ')
    const selects = wrapper.findAllComponents(ElSelect)
    selects[0]!.vm.$emit('update:modelValue', ['工作'])
    selects[1]!.vm.$emit('update:modelValue', 'HIGH')
    wrapper.getComponent(ElDatePicker).vm.$emit('update:modelValue', dueDate)
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('create')?.[0]).toEqual([
      {
        title: '准备周报',
        priority: 'HIGH',
        dueAt: dueDate.toISOString(),
        tags: ['工作'],
      },
    ])
    wrapper.unmount()
  })

  it('创建中不重复提交，且保留已填写内容', async () => {
    const wrapper = await mountForm()
    await wrapper.get('input[id$="-title"]').setValue('待提交任务')
    await wrapper.setProps({ creating: true })
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('create')).toBeUndefined()
    expect(wrapper.get('input[id$="-title"]').element).toHaveProperty('value', '待提交任务')
    wrapper.unmount()
  })

  it('父组件确认成功后可重置所有字段', async () => {
    const wrapper = await mountForm()
    const dueDate = new Date('2026-11-06T09:30:00Z')

    await wrapper.get('input[id$="-title"]').setValue('任务标题')
    const selects = wrapper.findAllComponents(ElSelect)
    selects[0]!.vm.$emit('update:modelValue', ['个人'])
    selects[1]!.vm.$emit('update:modelValue', 'LOW')
    wrapper.getComponent(ElDatePicker).vm.$emit('update:modelValue', dueDate)
    wrapper.vm.reset()
    await nextTick()

    expect(wrapper.get('input[id$="-title"]').element).toHaveProperty('value', '')
    expect(selects[0]!.props('modelValue')).toEqual([])
    expect(selects[1]!.props('modelValue')).toBe('MEDIUM')
    expect(wrapper.getComponent(ElDatePicker).props('modelValue')).toBeNull()
    wrapper.unmount()
  })
})
