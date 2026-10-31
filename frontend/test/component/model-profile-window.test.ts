import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ModelProfileDialog from '@/components/settings/ModelProfileDialog.vue'
import type { ModelProfile } from '@/types/api'

const { probeModelWindow } = vi.hoisted(() => ({ probeModelWindow: vi.fn() }))
vi.mock('@/api/model-profile', () => ({ probeModelWindow, probeModelList: vi.fn() }))
const wrappers: ReturnType<typeof mount>[] = []

function profile(overrides: Partial<ModelProfile> = {}): ModelProfile {
  return {
    id: 'profile-1',
    name: 'test',
    presetId: null,
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    protocol: 'OPENAI',
    apiKeyMasked: '***',
    hasApiKey: true,
    chatModel: 'glm-5.3',
    temperature: null,
    embeddingModel: '',
    audioModel: '',
    chatModels: ['glm-5.3', 'MiniMax-M2.5', 'deepseek-v4-pro'],
    embeddingModels: [],
    audioModels: [],
    contextWindows: { 'MiniMax-M2.5': 196601 },
    active: true,
    sortOrder: 0,
    revision: 1,
    updatedAt: '',
    sources: {
      baseUrl: 'DATABASE',
      apiKey: 'DATABASE',
      chatModel: 'DATABASE',
      embeddingModel: 'DEFAULT',
      audioModel: 'DEFAULT',
      temperature: 'DEFAULT',
    },
    ...overrides,
  }
}
async function open(p = profile()) {
  const wrapper = mount(ModelProfileDialog, {
    props: { modelValue: true, profile: p, preset: null },
    attachTo: document.body,
    global: {
      stubs: { teleport: true, transition: false, ElSelect: { template: '<div><slot /></div>' }, ElOption: true },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function reference(window: number) {
  return {
    ok: true,
    window,
    kind: 'REFERENCE',
    error: null,
    source: 'reference',
    sourceUrl: 'https://help.aliyun.com/zh/model-studio/glm-5-3',
    message: '端点只返回默认调用参数；未验证端点上限',
  }
}
function row(wrapper: ReturnType<typeof mount>, name: string) {
  return wrapper
    .findAll('.profile-form__model-row--chat')
    .find((r) => r.find('.profile-form__model-name').text() === name)!
}
beforeEach(() => {
  probeModelWindow.mockReset()
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  document.body.innerHTML = ''
})

describe('ModelProfileDialog window discovery', () => {
  it('补全空缺时顺序查询多个模型，并保留已填窗口', async () => {
    probeModelWindow.mockImplementation(({ model }: { model: string }) =>
      Promise.resolve(reference(model === 'glm-5.3' ? 1048576 : 1000000)),
    )
    const wrapper = await open()
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('补全空缺窗口'))!
      .trigger('click')
    await flushPromises()
    expect(probeModelWindow.mock.calls.map((args) => args[0].model)).toEqual(['glm-5.3', 'deepseek-v4-pro'])
    expect((row(wrapper, 'glm-5.3').find('input').element as HTMLInputElement).value).toBe('1048576')
    expect((row(wrapper, 'MiniMax-M2.5').find('input').element as HTMLInputElement).value).toBe('196601')
    expect(row(wrapper, 'glm-5.3').find('.profile-form__window-feedback').text()).toContain('官方参考')
    expect(row(wrapper, 'glm-5.3').find('a').attributes('href')).toContain('help.aliyun.com')
  })
  it('查询结果与现有值不同，不覆盖用户配置', async () => {
    probeModelWindow.mockResolvedValue(reference(204800))
    const wrapper = await open()
    await row(wrapper, 'MiniMax-M2.5').find('.profile-form__model-probe').trigger('click')
    await flushPromises()
    expect((row(wrapper, 'MiniMax-M2.5').find('input').element as HTMLInputElement).value).toBe('196601')
    expect(row(wrapper, 'MiniMax-M2.5').text()).toContain('已保留当前填写值')
  })
  it('认证失败保持原因可见，并停止后续批量查询', async () => {
    probeModelWindow.mockResolvedValue({
      ok: false,
      window: null,
      kind: 'AUTH_FAILED',
      error: '认证失败（HTTP 401），请检查 API Key',
    })
    const wrapper = await open()
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('补全空缺窗口'))!
      .trigger('click')
    await flushPromises()
    expect(probeModelWindow).toHaveBeenCalledTimes(1)
    expect(row(wrapper, 'glm-5.3').find('[role="status"]').text()).toContain('认证失败')
    expect((row(wrapper, 'glm-5.3').find('input').element as HTMLInputElement).value).toBe('')
  })
  it('修改端点后，旧查询结果不能写入新配置', async () => {
    let resolve!: (value: unknown) => void
    probeModelWindow.mockReturnValue(
      new Promise((r) => {
        resolve = r
      }),
    )
    const wrapper = await open()
    await row(wrapper, 'glm-5.3').find('.profile-form__model-probe').trigger('click')
    await wrapper
      .find('input[placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1"]')
      .setValue('https://new.example.com/v1')
    resolve(reference(1048576))
    await flushPromises()
    expect((row(wrapper, 'glm-5.3').find('input').element as HTMLInputElement).value).toBe('')
    expect(row(wrapper, 'glm-5.3').find('[role="status"]').exists()).toBe(false)
  })
  it('采用参考值只修改草稿，保存后才提交准确的 token 值', async () => {
    probeModelWindow.mockResolvedValue(reference(204800))
    const wrapper = await open()
    await row(wrapper, 'MiniMax-M2.5').find('.profile-form__model-probe').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(row(wrapper, 'MiniMax-M2.5').find('details').attributes('open')).toBeUndefined()
    await row(wrapper, 'MiniMax-M2.5').find('.profile-form__apply-window').trigger('click')
    expect((row(wrapper, 'MiniMax-M2.5').find('input').element as HTMLInputElement).value).toBe('204800')
    expect(row(wrapper, 'MiniMax-M2.5').find('.profile-form__apply-window').exists()).toBe(false)
    expect(row(wrapper, 'MiniMax-M2.5').find('[role="status"]').text()).toContain('已采用，保存后生效')
    expect(wrapper.emitted('submit')).toBeUndefined()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '保存')!
      .trigger('click')
    const payload = wrapper.emitted('submit')![0]![0] as { contextWindows: Record<string, number> }
    expect(payload.contextWindows['MiniMax-M2.5']).toBe(204800)
  })
  it('未采用查询值时，保存仍使用原有窗口', async () => {
    probeModelWindow.mockResolvedValue(reference(204800))
    const wrapper = await open()
    await row(wrapper, 'MiniMax-M2.5').find('.profile-form__model-probe').trigger('click')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '保存')!
      .trigger('click')
    const payload = wrapper.emitted('submit')![0]![0] as { contextWindows: Record<string, number> }
    expect(payload.contextWindows['MiniMax-M2.5']).toBe(196601)
  })
  it('默认温度明确显示服务端控制，支持自定义后恢复默认', async () => {
    const wrapper = await open()
    expect(wrapper.find('.profile-form__temperature-default').text()).toContain('使用服务端默认值')
    expect(wrapper.find('.profile-form__slider').exists()).toBe(false)
    await wrapper.find('.profile-form__temperature-default button').trigger('click')
    expect(wrapper.find('.profile-form__slider').exists()).toBe(true)
    await wrapper
      .find('.profile-form__temperature')
      .findAll('button')
      .find((b) => b.text() === '用默认')!
      .trigger('click')
    expect(wrapper.find('.profile-form__temperature-default').exists()).toBe(true)
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '保存')!
      .trigger('click')
    const payload = wrapper.emitted('submit')![0]![0] as { temperature: number | null }
    expect(payload.temperature).toBeNull()
  })
})
