import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { defineComponent, onMounted, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { useSettingsStore } from '@/stores/settings'
import SettingsView from '@/views/SettingsView.vue'

vi.mock('@/api/health', () => ({ fetchHealthStatus: vi.fn().mockResolvedValue({ status: 'UP' }) }))
const mounts = { model: 0, mcp: 0 }
const wrappers: VueWrapper[] = []

function cachedPanel(kind: 'model' | 'mcp') {
  return defineComponent({
    props: { active: Boolean },
    setup() {
      const expanded = ref(false)
      onMounted(() => mounts[kind]++)
      return { expanded }
    },
    template: '<div><button @click="expanded = !expanded">展开工具</button><p v-if="expanded">工具详情</p></div>',
  })
}

async function open(url = '/settings?section=mcp&source=test') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/settings', component: SettingsView }],
  })
  await router.push(url)
  await router.isReady()
  const modelPanel = cachedPanel('model')
  const wrapper = mount(SettingsView, {
    attachTo: document.body,
    global: {
      plugins: [createPinia(), router],
      stubs: { ModelProfilesPanel: modelPanel, McpSettingsPanel: cachedPanel('mcp'), PageHeader: true },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

async function select(wrapper: VueWrapper, label: string) {
  await wrapper
    .findAll('.settings__nav-item')
    .find((button) => button.find('.settings__nav-label').text() === label)!
    .trigger('click')
  await flushPromises()
}

beforeEach(() => {
  mounts.model = 0
  mounts.mcp = 0
  localStorage.clear()
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

describe('设置分组切换', () => {
  it('按需初始化分组，切回时保留详情与挂载状态', async () => {
    const { wrapper, router } = await open()
    expect(mounts).toEqual({ model: 0, mcp: 1 })
    const mcp = wrapper.find('.settings__panel[aria-label="MCP"]')
    await mcp.find('button').trigger('click')
    await select(wrapper, '模型配置')
    expect(mounts).toEqual({ model: 1, mcp: 1 })
    expect(mcp.isVisible()).toBe(false)
    expect(router.currentRoute.value.query).toEqual({ source: 'test' })
    await select(wrapper, 'MCP')
    expect(mcp.isVisible()).toBe(true)
    expect(mcp.text()).toContain('工具详情')
    expect(mounts).toEqual({ model: 1, mcp: 1 })
    expect(wrapper.findAll('.settings__panel').filter((panel) => panel.isVisible())).toHaveLength(1)
    expect(router.currentRoute.value.query).toEqual({ section: 'mcp', source: 'test' })
  })
  it('URL 切换同步导航和可见内容，未知分组回到模型配置', async () => {
    const { wrapper, router } = await open('/settings?section=unknown')
    expect(wrapper.find('.settings__nav-item[aria-current="page"] .settings__nav-label').text()).toBe('模型配置')
    expect(mounts).toEqual({ model: 1, mcp: 0 })
    await router.replace('/settings?section=calendar')
    await flushPromises()
    expect(wrapper.find('.settings__nav-item[aria-current="page"] .settings__nav-label').text()).toBe('日程')
    expect(wrapper.find('.settings__panel[aria-label="日程"]').isVisible()).toBe(true)
    expect(wrapper.find('.settings__panel[aria-label="模型配置"]').isVisible()).toBe(false)
  })
  it('对话设置保存执行中的消息处理方式，重新打开后保留', async () => {
    const { wrapper } = await open('/settings?section=chat')
    const group = wrapper.get('[aria-label="执行中发送消息的处理方式"]')
    expect(group.text()).toContain('排队处理')
    expect(group.text()).toContain('引导当前对话')
    await group.get('input[value="STEER"]').setValue(true)
    await flushPromises()
    expect(useSettingsStore().settings.followupMode).toBe('STEER')
    expect(localStorage.getItem('os.settings.followupMode')).toBe('STEER')
    const reopened = await open('/settings?section=chat')
    expect((reopened.wrapper.get('input[value="STEER"]').element as HTMLInputElement).checked).toBe(true)
  })
  it('工作区偏好立即生效并持久化，默认知识页支持长期记忆', async () => {
    const { wrapper } = await open('/settings?section=workspace')
    const panel = wrapper.get('.settings__panel[aria-label="工作区"]')
    await panel.get('input[value="board"]').setValue(true)
    const ui = useUiStore()
    expect(ui.tasksView).toBe('board')
    expect(localStorage.getItem('os.tasks.view')).toBe('board')
    const select = panel.findComponent({ name: 'ElSelect' })
    select.vm.$emit('update:modelValue', 'memory')
    await flushPromises()
    expect(ui.knowledgeTab).toBe('memory')
    expect(localStorage.getItem('os.knowledge.tab')).toBe('memory')
    const width = panel.findComponent({ name: 'ElInputNumber' })
    width.vm.$emit('update:modelValue', 330)
    await flushPromises()
    expect(ui.sessionPanelWidth).toBe(330)
    expect(localStorage.getItem('os.chat.sessionPanel.width')).toBe('330')
  })
  it('提醒快捷选项保存实际默认值，不修改已有日程', async () => {
    const { wrapper } = await open('/settings?section=calendar')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '30 分钟')!
      .trigger('click')
    await flushPromises()
    expect(useSettingsStore().settings.reminderLead).toBe(30)
    expect(localStorage.getItem('os.settings.reminderLead')).toBe('30')
  })
})
