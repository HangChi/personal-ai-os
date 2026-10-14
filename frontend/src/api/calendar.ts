import { requestData } from '@/api/http'
import type { CalendarEvent, CalendarOccurrence, RecurrenceType } from '@/types/api'

/**
 * 日程接口客户端。
 *
 * 查询返回的是**发生实例**（重复规则已由服务端展开），前端按时间顺序渲染即可，
 * 不需要理解重复规则 —— 重复语义只有服务端一处实现。
 */

export interface CreateEventPayload {
  title: string
  startsAt: string
  endsAt: string
  description?: string
  location?: string
  allDay?: boolean
  recurrenceType?: RecurrenceType
  recurrenceUntil?: string
  reminderLeadMinutes?: number
}

export interface UpdateEventPayload {
  title?: string
  description?: string
  location?: string
  allDay?: boolean
  startsAt?: string
  endsAt?: string
  recurrenceType?: RecurrenceType
  recurrenceUntil?: string
  clearRecurrenceUntil?: boolean
  reminderLeadMinutes?: number
}

export function listOccurrences(from?: string, to?: string, limit?: number): Promise<CalendarOccurrence[]> {
  return requestData<CalendarOccurrence[]>({
    url: '/calendar/events',
    method: 'get',
    params: { from, to, limit },
  })
}

export function createEvent(payload: CreateEventPayload): Promise<CalendarEvent> {
  return requestData<CalendarEvent>({ url: '/calendar/events', method: 'post', data: payload })
}

export function getEvent(id: string): Promise<CalendarEvent> {
  return requestData<CalendarEvent>({ url: `/calendar/events/${id}`, method: 'get' })
}

export function updateEvent(id: string, payload: UpdateEventPayload): Promise<CalendarEvent> {
  return requestData<CalendarEvent>({ url: `/calendar/events/${id}`, method: 'patch', data: payload })
}

export interface DeleteEventResult {
  deleted: boolean
  /** 随之删除的提醒条数 */
  deletedReminders: number
}

export function deleteEvent(id: string): Promise<DeleteEventResult> {
  return requestData<DeleteEventResult>({ url: `/calendar/events/${id}`, method: 'delete' })
}
