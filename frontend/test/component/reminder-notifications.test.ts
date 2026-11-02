import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElNotification } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import ReminderNotifications from '@/components/calendar/ReminderNotifications.vue'
import { useCalendarStore } from '@/stores/calendar'
import type { Reminder } from '@/types/api'

const { markReminderRead } = vi.hoisted(() => ({ markReminderRead: vi.fn() }))
vi.mock('@/api/reminders', () => ({
  markReminderRead,
  listReminders: vi.fn().mockResolvedValue([]),
  scanReminders: vi.fn(),
}))

let wrapper: VueWrapper | undefined

function reminder(id: string): Reminder {
  return {
    id,
    title: `提醒 ${id}`,
    sourceId: id,
    sourceType: 'CALENDAR_EVENT',
    occurrenceAt: '2026-11-02T08:00:00Z',
    remindAt: '2026-11-02T08:00:00Z',
    status: 'PENDING',
    readAt: null,
  }
}

async function setup(ids = ['r1']) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useCalendarStore()
  store.dueReminders = ids.map(reminder)
  store.inlineNotices = ids.map((id) => ({ title: `提醒 ${id}`, body: '测试正文', tag: id, reminderIds: [id] }))
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/calendar', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  // 队列推进依赖通知的 before-leave；默认 Transition stub 不触发该钩子。
  wrapper = mount(ReminderNotifications, { global: { plugins: [pinia, router], stubs: { transition: false } } })
  await flushPromises()
  return { store, router }
}

function button(text: string): HTMLButtonElement {
  const result = visibleNotices()
    .flatMap((notice) => [...notice.querySelectorAll<HTMLButtonElement>('button')])
    .find((element) => element.textContent?.trim() === text)
  if (!result) throw new Error(`没有找到按钮：${text}`)
  return result
}

function visibleNotices() {
  return [...document.querySelectorAll<HTMLElement>('.el-notification')].filter(
    (element) => element.style.display !== 'none',
  )
}

async function expectNotice(id?: string) {
  // 使用真实退场动画钩子，等待旧弹框卸载后检查队列的下一条。
  await vi.waitFor(() => {
    expect(visibleNotices()).toHaveLength(id ? 1 : 0)
    if (id) expect(visibleNotices()[0]!.textContent).toContain(`提醒 ${id}`)
  })
}

beforeEach(() => {
  markReminderRead.mockReset().mockResolvedValue({ status: 'READ' })
})

afterEach(async () => {
  wrapper?.unmount()
  wrapper = undefined
  ElNotification.closeAll()
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 50))
  document.body.innerHTML = ''
  vi.useRealTimers()
})

describe('全局提醒弹框直接确认', () => {
  it('任意页面可直接知道了，成功后收起且不跳转', async () => {
    const { store, router } = await setup()
    expect(document.body.textContent).toContain('测试正文')
    button('知道了').click()
    await flushPromises()
    expect(markReminderRead).toHaveBeenCalledExactlyOnceWith('r1')
    expect(store.dueReminders).toHaveLength(0)
    await expectNotice()
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('提交时显示忙碌并阻止重复点击', async () => {
    let finish!: () => void
    markReminderRead.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    const { store } = await setup()
    const action = button('知道了')
    action.click()
    action.click()
    await flushPromises()
    expect(markReminderRead).toHaveBeenCalledTimes(1)
    expect(action.classList.contains('is-loading')).toBe(true)
    expect(document.querySelector('.reminder-notice')?.getAttribute('aria-busy')).toBe('true')
    expect(store.dueReminders).toHaveLength(1)
    finish()
    await flushPromises()
    expect(store.dueReminders).toHaveLength(0)
  })

  it('失败就地提示并保留弹框，重试成功才收起', async () => {
    markReminderRead.mockRejectedValueOnce(new Error('网络断开'))
    const { store } = await setup()
    button('知道了').click()
    await flushPromises()
    expect(document.querySelector('.reminder-notice [role="alert"]')?.textContent).toContain('确认提醒失败')
    expect(visibleNotices()).toHaveLength(1)
    expect(store.dueReminders).toHaveLength(1)
    button('知道了').click()
    await flushPromises()
    expect(markReminderRead).toHaveBeenCalledTimes(2)
    expect(store.dueReminders).toHaveLength(0)
    await expectNotice()
  })

  it('多条提醒排队，每次知道了仅确认当前一条', async () => {
    const { store } = await setup(['r1', 'r2', 'r3', 'r4'])
    store.dueReminders.push(reminder('new'))
    for (const [index, id] of ['r1', 'r2', 'r3', 'r4'].entries()) {
      await expectNotice(id)
      expect(visibleNotices()[0]!.textContent).not.toContain('全部知道了')
      button('知道了').click()
      await flushPromises()
      expect(markReminderRead).toHaveBeenCalledTimes(index + 1)
      expect(markReminderRead).toHaveBeenLastCalledWith(id)
    }
    expect(store.dueReminders.map(({ id }) => id)).toEqual(['new'])
    await expectNotice()
  })

  it('当前条失败时不展示下一条，重试成功后继续', async () => {
    markReminderRead.mockRejectedValueOnce(new Error('失败'))
    await setup(['r1', 'r2'])
    button('知道了').click()
    await flushPromises()
    expect(visibleNotices()).toHaveLength(1)
    expect(visibleNotices()[0]!.textContent).toContain('提醒 r1')
    expect(visibleNotices()[0]!.textContent).toContain('确认提醒失败')
    button('知道了').click()
    await flushPromises()
    expect(markReminderRead.mock.calls.map(([id]) => id)).toEqual(['r1', 'r1'])
    await expectNotice('r2')
  })

  it('排队期间被日程页确认的提醒不再弹出', async () => {
    const { store } = await setup(['r1', 'r2', 'r3'])
    await store.acknowledge({ id: 'r2' })
    button('知道了').click()
    await flushPromises()
    await expectNotice('r3')
  })

  it('关闭当前条只收起该条，再展示下一条', async () => {
    const { store } = await setup(['r1', 'r2'])
    visibleNotices()[0]!.querySelector<HTMLElement>('.el-notification__closeBtn')!.click()
    await flushPromises()
    await expectNotice('r2')
    expect(markReminderRead).not.toHaveBeenCalled()
    expect(store.dueReminders.map(({ id }) => id)).toEqual(['r1', 'r2'])
  })

  it('查看日程只跳转，不代替确认', async () => {
    const { router, store } = await setup()
    button('查看日程').click()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/calendar')
    expect(markReminderRead).not.toHaveBeenCalled()
    expect(store.dueReminders).toHaveLength(1)
  })

  it('日程页确认后同步收起弹框', async () => {
    const { store } = await setup()
    await store.acknowledge({ id: 'r1' })
    await flushPromises()
    await expectNotice()
  })

  it('关闭按钮仅收起，不把未确认提醒标记已读', async () => {
    const { store } = await setup()
    document.querySelector<HTMLElement>('.el-notification__closeBtn')!.click()
    await flushPromises()
    expect(markReminderRead).not.toHaveBeenCalled()
    expect(store.dueReminders).toHaveLength(1)
  })

  it('等待超过原来的 6 秒仍保留操作按钮', async () => {
    vi.useFakeTimers()
    await setup(['r1', 'r2'])
    await vi.advanceTimersByTimeAsync(6500)
    expect(visibleNotices()).toHaveLength(1)
    expect(button('知道了')).toBeTruthy()
    vi.useRealTimers()
  })

  it('卸载后收起全部弹框，不残留操作入口', async () => {
    await setup(['r1', 'r2'])
    wrapper!.unmount()
    wrapper = undefined
    await flushPromises()
    await expectNotice()
    expect(markReminderRead).not.toHaveBeenCalled()
  })
})
