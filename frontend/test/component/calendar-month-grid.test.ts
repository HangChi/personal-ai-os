import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import CalendarMonthGrid from '@/components/calendar/CalendarMonthGrid.vue'
import { buildMonthCells } from '@/lib/calendar-dates'
import type { CalendarOccurrence } from '@/types/api'

describe('CalendarMonthGrid', () => {
  it('长标题保留完整提示和键盘编辑入口，多余日程可从折叠列表打开', async () => {
    const cells = buildMonthCells(new Date(2026, 10, 1))
    const items: CalendarOccurrence[] = Array.from({ length: 4 }, (_, i) => ({
      eventId: `event-${i}`,
      title: `${i} 面试 - 虹科电子公司 - 工业数字化解决方案工程师`,
      location: null,
      allDay: false,
      startsAt: '2026-11-02T06:00:00Z',
      endsAt: '2026-11-02T06:30:00Z',
      recurrenceType: 'NONE',
      recurring: false,
      reminderLeadMinutes: 15,
    }))
    const wrapper = mount(CalendarMonthGrid, {
      props: {
        cells,
        itemsByDay: new Map([['2026-11-02', items]]),
        title: '2026 年 11 月',
        overflow: 0,
        loading: false,
      },
      global: { stubs: { ElPopover: { template: '<div><slot name="reference" /><slot /></div>' } } },
    })
    expect(wrapper.findAll('.cal-month__cell')).toHaveLength(42)
    expect(wrapper.findAll('.cal-month__cell-item')).toHaveLength(3)
    const first = wrapper.get('.cal-month__cell-item')
    expect(first.attributes('title')).toBe(items[0].title)
    await first.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('open-edit')?.[0]).toEqual([items[0]])
    expect(wrapper.get('.cal-month__cell-more').text()).toBe('还有 1 条')
    await wrapper.get('button.cal-month__cell-more-item').trigger('click')
    expect(wrapper.emitted('open-edit')?.[1]).toEqual([items[3]])
    wrapper.unmount()
  })
})
