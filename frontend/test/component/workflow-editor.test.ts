import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import WorkflowsView from '@/views/WorkflowsView.vue'
import { saveWorkflow, workflows } from '@/api/workflows'
import type { Workflow } from '@/api/workflows'

vi.mock('@/api/workflows', () => ({
  workflows: vi.fn(),
  saveWorkflow: vi.fn(),
  runWorkflow: vi.fn(),
  workflowHistory: vi.fn().mockResolvedValue({ items: [], total: 0 }),
  retryWorkflow: vi.fn(),
}))
const row: Workflow = {
  id: 'w-1',
  name: '每日晨报',
  kind: 'MORNING_BRIEF',
  timezone: 'Asia/Shanghai',
  localTime: '09:00',
  weekday: 1,
  enabled: false,
  maxRunsPerDay: 3,
  maxItems: 30,
  maxOutputChars: 10000,
  nextRunAt: '2026-11-07T01:00:00Z',
}
let wrapper: VueWrapper
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(workflows).mockResolvedValue({ items: [row], total: 1, truncated: false })
  vi.mocked(saveWorkflow).mockResolvedValue(row)
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
// Happy DOM 下此表单的真实 ElSelect 会触发递归更新；父层编排用原生选择器，
// 真正的 Element Plus 报告类型/星期交互由隔离 Chromium 检查覆盖。
const SelectStub = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  setup(props, { emit, slots, attrs }) {
    return () =>
      h(
        'select',
        {
          ...attrs,
          value: props.modelValue,
          onChange: (event: Event) => {
            const value = (event.target as HTMLSelectElement).value
            emit('update:modelValue', typeof props.modelValue === 'number' ? Number(value) : value)
          },
        },
        slots.default?.(),
      )
  },
})
const OptionStub = defineComponent({
  props: ['value', 'label'],
  setup: (props) => () => h('option', { value: props.value }, props.label),
})
async function open() {
  wrapper = mount(WorkflowsView, {
    attachTo: document.body,
    global: {
      stubs: { teleport: true, transition: true, RouterLink: true, ElSelect: SelectStub, ElOption: OptionStub },
    },
  })
  await flushPromises()
}
async function click(label: string) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === label)!
    .trigger('click')
  await flushPromises()
}

describe('列表优先的计划编辑', () => {
  it('初始展示列表，点击后才展示表单；新计划保持禁用默认值', async () => {
    await open()
    expect(wrapper.text()).toContain('每日晨报')
    expect(wrapper.find('form').exists()).toBe(false)
    await click('新建计划')
    await wrapper.get('input[aria-label="计划名称"]').setValue('工作晨报')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(saveWorkflow).toHaveBeenCalledWith(
      null,
      expect.objectContaining({ enabled: false, name: '工作晨报', maxRunsPerDay: 3 }),
    )
  })
  it('保存失败保留草稿和错误，可继续修改并保存', async () => {
    vi.mocked(saveWorkflow).mockRejectedValueOnce(new Error('连接暂不可用'))
    await open()
    await click('编辑')
    await wrapper.get('input[aria-label="计划名称"]').setValue('保留这份草稿')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('input[aria-label="计划名称"]').element).toHaveProperty('value', '保留这份草稿')
    expect(wrapper.text()).toContain('连接暂不可用')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(saveWorkflow).toHaveBeenLastCalledWith('w-1', expect.objectContaining({ name: '保留这份草稿' }))
  })
  it('执行历史使用独立抽屉，空记录给出下一步', async () => {
    await open()
    await click('执行历史')
    expect(document.body.textContent).toContain('尚无执行记录')
    expect(document.body.querySelector('[role="dialog"]')).not.toBeNull()
  })
})
