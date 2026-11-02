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
  store.inlineNotices = [{ title: '到点提醒', body: '测试正文', tag: 'notice', reminderIds: ids }]
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/calendar', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  wrapper = mount(ReminderNotifications, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { store, router }
}

function button(text: string): HTMLButtonElement {
  const result = [...document.querySelectorAll<HTMLButtonElement>('.el-notification button')].find(
    (element) => element.textContent?.trim() === text,
  )
  if (!result) throw new Error(`没有找到按钮：${text}`)
  return result
}

function visibleNotices() {
  return [...document.querySelectorAll<HTMLElement>('.el-notification')].filter(
    (element) => element.style.display !== 'none',
  )
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
    expect(visibleNotices()).toHaveLength(0)
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
    expect(document.querySelector('.reminder-notice [role="alert"]')?.textContent).toContain('确认失败')
    expect(visibleNotices()).toHaveLength(1)
    expect(store.dueReminders).toHaveLength(1)
    button('知道了').click()
    await flushPromises()
    expect(markReminderRead).toHaveBeenCalledTimes(2)
    expect(store.dueReminders).toHaveLength(0)
    expect(visibleNotices()).toHaveLength(0)
  })

  it('汇总只确认弹框中的提醒；部分失败时只重试失败项', async () => {
    markReminderRead.mockImplementation(async (id: string) => {
      if (id === 'r2') throw new Error('失败')
    })
    const { store } = await setup(['r1', 'r2', 'r3', 'r4'])
    store.dueReminders.push(reminder('new'))
    button('全部知道了').click()
    await flushPromises()
    expect(markReminderRead.mock.calls.map(([id]) => id)).toEqual(['r1', 'r2', 'r3', 'r4'])
    expect(store.dueReminders.map(({ id }) => id)).toEqual(['r2', 'new'])
    expect(document.querySelector('.reminder-notice [role="alert"]')?.textContent).toContain('1 条')
    markReminderRead.mockReset().mockResolvedValue({ status: 'READ' })
    button('全部知道了').click()
    await flushPromises()
    expect(markReminderRead).toHaveBeenCalledExactlyOnceWith('r2')
    expect(store.dueReminders.map(({ id }) => id)).toEqual(['new'])
    expect(visibleNotices()).toHaveLength(0)
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
    expect(visibleNotices()).toHaveLength(0)
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
    await setup()
    await vi.advanceTimersByTimeAsync(6500)
    expect(visibleNotices()).toHaveLength(1)
    expect(button('知道了')).toBeTruthy()
    vi.useRealTimers()
  })

  it('卸载后收起全部弹框，不残留操作入口', async () => {
    await setup()
    wrapper!.unmount()
    wrapper = undefined
    await flushPromises()
    expect(visibleNotices()).toHaveLength(0)
    expect(markReminderRead).not.toHaveBeenCalled()
  })
})
