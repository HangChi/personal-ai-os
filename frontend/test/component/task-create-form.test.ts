import { flushPromises, mount } from '@vue/test-utils'
import { ElDatePicker, ElMessage, ElSelect } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

import TaskCreateForm from '@/components/task/TaskCreateForm.vue'
import TaskOrganizationFields from '@/components/task/TaskOrganizationFields.vue'
import type { TaskItem } from '@/types/api'
import { getTask, listTasks } from '@/api/tasks'

vi.mock('@/api/tasks', () => ({ getTask: vi.fn(), listTasks: vi.fn() }))

const parent: TaskItem = {
  id: 'parent-1',
  title: '准备季度复盘',
  projectId: 'project-1',
  description: null,
  status: 'TODO',
  priority: 'MEDIUM',
  tags: [],
  dueAt: null,
  completedAt: null,
  createdAt: '2026-11-06T00:00:00Z',
  updatedAt: '2026-11-06T00:00:00Z',
  overdue: false,
}

beforeEach(() => {
  vi.mocked(getTask).mockResolvedValue(parent)
  vi.mocked(listTasks).mockResolvedValue({ items: [parent], total: 1, page: 0, size: 100, truncated: false })
})

afterEach(() => {
  vi.clearAllMocks()
  ElMessage.closeAll()
})

async function mountForm(embedded = false) {
  const wrapper = mount(TaskCreateForm, {
    props: {
      tags: ['工作', '个人'],
      pendingCount: 7,
      overdueCount: 2,
      creating: false,
      embedded,
      projects: [
        {
          id: 'project-1',
          name: '工作计划',
          description: null,
          archived: false,
          createdAt: parent.createdAt,
          updatedAt: parent.updatedAt,
        },
      ],
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
    wrapper.get('.task-create__field--tags').getComponent(ElSelect).vm.$emit('update:modelValue', ['工作'])
    wrapper.get('.task-create__field--priority').getComponent(ElSelect).vm.$emit('update:modelValue', 'HIGH')
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
    const tags = wrapper.get('.task-create__field--tags').getComponent(ElSelect)
    const priority = wrapper.get('.task-create__field--priority').getComponent(ElSelect)
    tags.vm.$emit('update:modelValue', ['个人'])
    priority.vm.$emit('update:modelValue', 'LOW')
    wrapper.getComponent(ElDatePicker).vm.$emit('update:modelValue', dueDate)
    wrapper.vm.reset()
    await nextTick()

    expect(wrapper.get('input[id$="-title"]').element).toHaveProperty('value', '')
    expect(tags.props('modelValue')).toEqual([])
    expect(priority.props('modelValue')).toBe('MEDIUM')
    expect(wrapper.getComponent(ElDatePicker).props('modelValue')).toBeNull()
    wrapper.unmount()
  })

  it('默认收起更多设置，空标题不可创建，也不主动加载父任务', async () => {
    const wrapper = await mountForm(true)
    expect(wrapper.get('details').element).toHaveProperty('open', false)
    expect(wrapper.get('summary').text()).toContain('项目 · 父任务 · 重复')
    expect(listTasks).not.toHaveBeenCalled()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('input[id$="-title"]').setValue('   ')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')).toBeUndefined()
    expect(wrapper.text()).not.toContain('填写标题后即可创建任务')
    wrapper.unmount()
  })

  it('收起设置后仍展示所选摘要，并提交项目、重复规则及时区', async () => {
    const wrapper = await mountForm(true)
    const details = wrapper.get('details')
    ;(details.element as HTMLDetailsElement).open = true
    await details.trigger('toggle')
    const organization = wrapper.getComponent(TaskOrganizationFields)
    organization.vm.$emit('update:modelValue', {
      projectId: 'project-1',
      parentTaskId: '',
      recurrenceType: 'WEEKLY',
      recurrenceTimezone: 'Asia/Shanghai',
    })
    await wrapper.get('input[id$="-title"]').setValue('每周整理')
    wrapper.getComponent(ElDatePicker).vm.$emit('update:modelValue', new Date('2026-11-06T09:30:00Z'))
    ;(details.element as HTMLDetailsElement).open = false
    await details.trigger('toggle')
    expect(wrapper.get('summary').text()).toContain('工作计划 · 每周')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')?.[0]?.[0]).toMatchObject({
      projectId: 'project-1',
      recurrenceType: 'WEEKLY',
      recurrenceTimezone: 'Asia/Shanghai',
      title: '每周整理',
      dueAt: '2026-11-06T09:30:00.000Z',
    })
    wrapper.unmount()
  })

  it('添加子任务自动展开，保留父任务并禁用重复规则', async () => {
    const wrapper = await mountForm(true)
    wrapper.vm.startSubtask(parent)
    await flushPromises()
    expect(wrapper.get('details').element).toHaveProperty('open', true)
    expect(wrapper.get('summary').text()).toContain('工作计划 · 子任务')
    const selects = wrapper.getComponent(TaskOrganizationFields).findAllComponents(ElSelect)
    expect(selects[1]!.props('modelValue')).toBe(parent.id)
    expect(selects[2]!.props('disabled')).toBe(true)
    expect(wrapper.text()).toContain('子任务不支持重复规则')
    await wrapper.get('input[id$="-title"]').setValue('整理资料')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')?.[0]?.[0]).toMatchObject({ projectId: parent.projectId, parentTaskId: parent.id })
    wrapper.vm.reset()
    await nextTick()
    expect(wrapper.get('details').element).toHaveProperty('open', false)
    expect(wrapper.get('summary').text()).toContain('工作计划')
    expect(selects[1]!.props('modelValue')).toBe('')
    wrapper.unmount()
  })

  it('周期任务缺少截止时间时阻止提交，补全后可创建', async () => {
    const wrapper = await mountForm(true)
    await wrapper.get('input[id$="-title"]').setValue('每日回顾')
    const organization = wrapper.getComponent(TaskOrganizationFields)
    organization.vm.$emit('update:modelValue', {
      ...organization.props('modelValue'),
      recurrenceType: 'DAILY',
    })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')).toBeUndefined()
    wrapper.getComponent(ElDatePicker).vm.$emit('update:modelValue', new Date('2026-11-06T10:00:00Z'))
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')?.[0]?.[0]).toMatchObject({ recurrenceType: 'DAILY' })
    wrapper.unmount()
  })

  it('取消只通知父组件，保留草稿；创建中按钮和输入均禁用', async () => {
    const wrapper = await mountForm(true)
    await wrapper.get('input[id$="-title"]').setValue('保留的草稿')
    const cancel = wrapper.findAll('button').find((button) => button.text() === '取消')!
    await cancel.trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(wrapper.get('input[id$="-title"]').element).toHaveProperty('value', '保留的草稿')
    await wrapper.setProps({ creating: true })
    expect(cancel.attributes('disabled')).toBeDefined()
    expect(wrapper.get('input[id$="-title"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('button[type="submit"]').classes()).toContain('is-loading')
    wrapper.unmount()
  })
})
