import type { Reminder, ReminderSourceType } from '@/types/api'
import type { NotificationPermissionState } from './permission'

/**
 * 提醒的送达层。
 *
 * <h2>职责边界</h2>
 * 这里只做三件事：决定走哪条通道、去重、组装通知内容。
 * **不碰 DOM、不引 Element Plus** —— 站内提示的渲染由调用方负责，
 * 这样核心逻辑可以在 Node 里直接单测，不需要浏览器环境。
 *
 * <h2>通道语义</h2>
 * - `browser`：页面在后台且已授权 → 发系统通知（用户能真正看到）
 * - `inline`：页面可见 → 发站内提示（用户就在屏幕前，系统通知是多余干扰）
 * - `none`：页面在后台但没有授权 → **什么都不发**。用站内提示冒充送达会制造
 *   「系统说提醒了、用户根本没感知」的假象，比不提醒更糟。
 */

export type DeliveryChannel = 'browser' | 'inline' | 'none'

/** 通知内容（系统通知与站内提示共用，保证两条通道看到的是同一件事） */
export interface NoticePayload {
  title: string
  body: string
  tag: string
}

export interface DispatchResult {
  /** 实际采用的通道；`browser` 构造失败时会降级为 `inline` */
  channel: DeliveryChannel
  /** 本次要展示的通知；`browser` 且全部发送成功时为空（已由 notifier 发出） */
  notices: NoticePayload[]
  /** 因已通知过而跳过的条数 */
  skipped: number
}

/** 超过这个条数就合并为一条汇总通知，避免通知风暴 */
export const SUMMARY_THRESHOLD = 3

/** 可注入的通知函数：默认发系统通知，测试里替换为记录器 */
export type NotifyFn = (payload: NoticePayload) => void

/**
 * 通道决策（纯函数，八种组合见 contracts 一节）。
 *
 * @param permission  当前通知权限
 * @param pageVisible 页面是否可见
 */
export function chooseChannel(
  permission: NotificationPermissionState,
  pageVisible: boolean,
): DeliveryChannel {
  if (pageVisible) {
    return 'inline'
  }
  return permission === 'granted' ? 'browser' : 'none'
}

/**
 * 默认实现：构造浏览器通知，点击时聚焦窗口并跳转到日程页。
 *
 * @param navigate 点击后的跳转动作（由调用方注入路由跳转，避免这里依赖 router）
 */
export function createBrowserNotify(navigate?: () => void): NotifyFn {
  return (payload) => {
    const notification = new Notification(payload.title, {
      body: payload.body,
      tag: payload.tag,
    })
    notification.onclick = () => {
      window.focus()
      navigate?.()
    }
  }
}

export class ReminderNotifier {
  /** 本页面会话内已通知过的提醒 id（刷新即重置，见 research R-03） */
  private readonly notified = new Set<string>()

  private readonly notify: NotifyFn

  constructor(notify: NotifyFn) {
    this.notify = notify
  }

  /**
   * 处理一轮轮询结果。
   *
   * @param reminders   服务端返回的到点未读提醒
   * @param permission  当前通知权限
   * @param pageVisible 页面是否可见
   */
  dispatch(
    reminders: Reminder[],
    permission: NotificationPermissionState,
    pageVisible: boolean,
  ): DispatchResult {
    const channel = chooseChannel(permission, pageVisible)
    const fresh = reminders.filter((reminder) => !this.notified.has(reminder.id))
    const skipped = reminders.length - fresh.length

    if (channel === 'none' || fresh.length === 0) {
      return { channel, notices: [], skipped }
    }

    const notices =
      fresh.length > SUMMARY_THRESHOLD
        ? [summaryNotice(fresh)]
        : fresh.map((reminder) => singleNotice(reminder))

    // 先记账再发送：即使发送抛异常，也不该在下一轮重复打扰同一条提醒
    for (const reminder of fresh) {
      this.notified.add(reminder.id)
    }

    if (channel === 'inline') {
      return { channel, notices, skipped }
    }

    let sent = 0
    for (const notice of notices) {
      try {
        this.notify(notice)
        sent += 1
      } catch (error) {
        // 构造通知可能因系统策略直接抛异常；一条送不出去不能拖垮整个轮询
        console.warn('浏览器通知发送失败，降级为站内提示', error)
        return { channel: 'inline', notices: notices.slice(sent), skipped }
      }
    }
    return { channel, notices: [], skipped }
  }

  /** 重置去重状态（测试用；生产上页面刷新即新实例） */
  reset(): void {
    this.notified.clear()
  }
}

function sourceLabel(sourceType: ReminderSourceType): string {
  return sourceType === 'TASK' ? '[任务]' : '[日程]'
}

function singleNotice(reminder: Reminder): NoticePayload {
  return {
    title: `${sourceLabel(reminder.sourceType)} ${reminder.title}`,
    body: `${formatTime(reminder.remindAt ?? reminder.occurrenceAt)} 到点`,
    // 用提醒 id 作 tag：浏览器会把同 tag 的通知合并，而不是堆成一列
    tag: reminder.id,
  }
}

function summaryNotice(reminders: Reminder[]): NoticePayload {
  const titles = reminders
    .slice(0, 3)
    .map((reminder) => reminder.title)
    .join('、')
  return {
    title: `有 ${reminders.length} 条提醒到点`,
    body: reminders.length > 3 ? `${titles} 等` : titles,
    tag: 'reminder-summary',
  }
}

function formatTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
}
