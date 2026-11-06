import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CalendarExchange from '@/components/calendar/CalendarExchange.vue'
import { calendarConflicts } from '@/api/calendar'

vi.mock('@/api/calendar', () => ({
  calendarConflicts: vi.fn(),
  previewIcs: vi.fn(),
  importIcs: vi.fn(),
}))

afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('日历互通冲突日期', () => {
  it.each([
    [2026, 10, 6, 0, 15, '2026-11-06'],
    [2027, 0, 1, 0, 0, '2027-01-01'],
    [2026, 11, 31, 23, 59, '2026-12-31'],
  ] as const)('按浏览器本地日期初始化并查询七天窗口：%s/%s/%s %s:%s', async (year, month, day, hour, minute, key) => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(year, month, day, hour, minute))
    vi.mocked(calendarConflicts).mockResolvedValue({ items: [], total: 0, truncated: false })
    const wrapper = mount(CalendarExchange)
    try {
      expect(wrapper.get<HTMLInputElement>('input[type="date"]').element.value).toBe(key)
      const button = wrapper.findAll('button').find((item) => item.text().includes('检查未来 7 天冲突'))!
      await button.trigger('click')
      await flushPromises()
      const start = new Date(year, month, day)
      const end = new Date(year, month, day + 7)
      expect(calendarConflicts).toHaveBeenCalledWith(start.toISOString(), end.toISOString())
    } finally {
      wrapper.unmount()
    }
  })
})
