import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { listOccurrences } from '@/api/calendar'
import { monthRange } from '@/lib/calendar-dates'
import { useCalendarStore } from '@/stores/calendar'
import type { CalendarOccurrence } from '@/types/api'
import CalendarView from '@/views/CalendarView.vue'

vi.mock('@/api/calendar', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/calendar')>()),
  listOccurrences: vi.fn(),
}))

let wrapper: VueWrapper | undefined
let pinia: ReturnType<typeof createPinia>
let items: CalendarOccurrence[]

function occurrence(day: number, title: string): CalendarOccurrence {
  return {
    eventId: `event-${day}`,
    title,
    location: null,
    allDay: false,
    startsAt: new Date(2026, 10, day, 9).toISOString(),
    endsAt: new Date(2026, 10, day, 10).toISOString(),
    recurrenceType: 'NONE',
    recurring: false,
    reminderLeadMinutes: 15,
  }
}

async function openCalendar(): Promise<VueWrapper> {
  wrapper = mount(CalendarView, {
    global: {
      plugins: [pinia],
      stubs: {
        RouterLink: true,
        CalendarNotifyBanner: true,
        CalendarDueReminders: true,
        CalendarCreateForm: true,
        CalendarExchange: true,
        CalendarEditDialog: true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 10, 8, 12))
  pinia = createPinia()
  setActivePinia(pinia)
  items = [occurrence(2, '月初会议'), occurrence(26, '月底会议')]
  vi.mocked(listOccurrences)
    .mockReset()
    .mockImplementation(async (from, to, page = 0, size = 100) => {
      // 模拟后端默认只查今天起七天，两条夹具都在该默认窗口之外。
      const start = from ? new Date(from).getTime() : new Date(2026, 10, 8).getTime()
      const end = to ? new Date(to).getTime() : start + 7 * 24 * 60 * 60 * 1000
      const matches = items.filter((item) => {
        const at = new Date(item.startsAt).getTime()
        return at >= start && at < end
      })
      return { items: matches.slice(page * size, (page + 1) * size), total: matches.length, page, size }
    })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.useRealTimers()
})

describe('日程页查询窗口', () => {
  it('首次打开列表直接显示本月日程，不需要先切换月视图', async () => {
    const view = await openCalendar()
    expect(view.findAll('.cal-list__item-title').map((item) => item.text())).toEqual(['月初会议', '月底会议'])
    const { from, to } = monthRange(new Date(2026, 10, 1))
    expect(listOccurrences).toHaveBeenCalledExactlyOnceWith(from, to, 0, 100)
  })

  it('列表与月视图使用同一月份，切回列表恢复每页 100 条', async () => {
    const view = await openCalendar()
    const { from, to } = monthRange(new Date(2026, 10, 1))
    await view.get('input[value="month"]').setValue(true)
    await flushPromises()
    expect(listOccurrences).toHaveBeenLastCalledWith(from, to, 0, 500)
    expect(view.findAll('.cal-month__cell-title').map((item) => item.text())).toEqual(['月初会议', '月底会议'])
    await view.get('input[value="list"]').setValue(true)
    await flushPromises()
    expect(listOccurrences).toHaveBeenLastCalledWith(from, to, 0, 100)
    expect(view.findAll('.cal-list__item-title').map((item) => item.text())).toEqual(['月初会议', '月底会议'])
  })

  it('重新进入页面重置为本月第一页，不沿用 store 中的旧月份、页码或月视图条数', async () => {
    const store = useCalendarStore()
    const previous = monthRange(new Date(2026, 9, 1))
    await store.loadOccurrences(previous.from, previous.to, 3, 500)
    const view = await openCalendar()
    const { from, to } = monthRange(new Date(2026, 10, 1))
    expect(listOccurrences).toHaveBeenLastCalledWith(from, to, 0, 100)
    expect(view.findAll('.cal-list__item-title').map((item) => item.text())).toEqual(['月初会议', '月底会议'])
  })

  it.each([
    [2027, 0, 1],
    [2028, 1, 29],
    [2026, 11, 31],
  ])('首次列表按本地月份边界查询：%s/%s/%s', async (year, month, day) => {
    vi.setSystemTime(new Date(year, month, day, 0, 15))
    await openCalendar()
    const { from, to } = monthRange(new Date(year, month, 1))
    expect(listOccurrences).toHaveBeenCalledExactlyOnceWith(from, to, 0, 100)
  })
})
