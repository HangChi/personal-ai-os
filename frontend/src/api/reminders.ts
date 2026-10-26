import { requestData } from '@/api/http'
import type { Reminder, ReminderScanResult, ReminderStatus } from '@/types/api'

/**
 * 提醒接口客户端。
 */

/**
 * 查询提醒。
 *
 * @param dueOnly true（默认）只返回已到点未读的；false 可看到「接下来会提醒什么」，
 *                也便于验证扫描是否已经把下一条物化出来 —— 否则只能干等到点
 */
export function listReminders(status?: ReminderStatus, dueOnly = true, limit?: number): Promise<Reminder[]> {
  return requestData<Reminder[]>({
    url: '/reminders',
    method: 'get',
    params: { status, dueOnly, limit },
  })
}

/** 标记已读（幂等：重复调用不报错） */
export function markReminderRead(id: string): Promise<Reminder> {
  return requestData<Reminder>({ url: `/reminders/${id}/read`, method: 'post' })
}

/** 手动触发一次扫描（排错用，不必等下一个调度周期） */
export function scanReminders(): Promise<ReminderScanResult> {
  return requestData<ReminderScanResult>({ url: '/reminders/scan', method: 'post' })
}
