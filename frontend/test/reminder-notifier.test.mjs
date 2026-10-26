import { eq } from './assert.mjs'

/**
 * 提醒送达层（阶段 9）：通道决策、去重、汇总、异常降级。
 *
 * 这一层的分支是本阶段唯一容易改坏的地方，所以八种通道组合逐一枚举；
 * 通知调用通过构造函数注入，测试里换成一个记录器即可断言"发了几条、内容是什么"。
 */

const { chooseChannel, ReminderNotifier, SUMMARY_THRESHOLD, createBrowserNotify } =
  await import('@/notifications/reminder-notifier')

function reminder(id, title, options = {}) {
  return {
    id,
    sourceType: 'TASK',
    sourceId: 'source-' + id,
    title,
    occurrenceAt: '2026-10-25T06:00:00Z',
    remindAt: '2026-10-25T06:00:00Z',
    status: 'PENDING',
    readAt: null,
    ...options,
  }
}

function recorder() {
  const sent = []
  const notify = (payload) => {
    sent.push(payload)
  }
  return { sent, notify }
}

// ---- 通道决策：4 权限 × 2 可见性 ----

{
  eq('可见 + granted → 站内', chooseChannel('granted', true), 'inline')
  eq('可见 + default → 站内', chooseChannel('default', true), 'inline')
  eq('可见 + denied → 站内', chooseChannel('denied', true), 'inline')
  eq('可见 + unsupported → 站内', chooseChannel('unsupported', true), 'inline')

  eq('隐藏 + granted → 系统通知', chooseChannel('granted', false), 'browser')
  eq('隐藏 + default → 不发', chooseChannel('default', false), 'none')
  eq('隐藏 + denied → 不发', chooseChannel('denied', false), 'none')
  eq('隐藏 + unsupported → 不发', chooseChannel('unsupported', false), 'none')
}

// ---- 单条：后台 + 已授权 → 发一条，内容含来源与标题 ----

{
  const { sent, notify } = recorder()
  const notifier = new ReminderNotifier(notify)

  const result = notifier.dispatch([reminder('r-1', '交周报')], 'granted', false)

  eq('通道为系统通知', result.channel, 'browser')
  eq('发出 1 条', sent.length, 1)
  eq('标题含来源前缀', sent[0].title, '[任务] 交周报')
  eq('正文含到点时间', sent[0].body.includes('到点'), true)
  eq('tag 用提醒 id', sent[0].tag, 'r-1')
  eq('系统通知已发，不再重复交给调用方', result.notices.length, 0)
}

// ---- 单条：页面可见 → 不发系统通知，交给站内提示 ----

{
  const { sent, notify } = recorder()
  const notifier = new ReminderNotifier(notify)

  const result = notifier.dispatch([reminder('r-1', '交周报')], 'granted', true)

  eq('通道为站内', result.channel, 'inline')
  eq('没有系统通知', sent.length, 0)
  eq('站内提示内容有 1 条', result.notices.length, 1)
  eq('站内标题同源', result.notices[0].title, '[任务] 交周报')
}

// ---- 无权限 + 后台：什么都不发（不冒充送达）----

{
  const { sent, notify } = recorder()
  const notifier = new ReminderNotifier(notify)

  const result = notifier.dispatch([reminder('r-1', '交周报')], 'default', false)

  eq('通道为 none', result.channel, 'none')
  eq('没有系统通知', sent.length, 0)
  eq('也没有站内提示', result.notices.length, 0)
}

// ---- 去重：同一条连续三次只通知一次 ----

{
  const { sent, notify } = recorder()
  const notifier = new ReminderNotifier(notify)

  notifier.dispatch([reminder('r-1', '交周报')], 'granted', false)
  const second = notifier.dispatch([reminder('r-1', '交周报')], 'granted', false)
  const third = notifier.dispatch([reminder('r-1', '交周报')], 'granted', false)

  eq('总共只通知一次', sent.length, 1)
  eq('第二轮被跳过 1 条', second.skipped, 1)
  eq('第三轮被跳过 1 条', third.skipped, 1)
  eq('跳过的轮次不发通知', third.notices.length, 0)
}

// ---- 汇总：超过阈值合并为一条 ----

{
  eq('阈值为 3', SUMMARY_THRESHOLD, 3)

  const { sent, notify } = recorder()
  const notifier = new ReminderNotifier(notify)
  const many = [
    reminder('r-1', '交周报'),
    reminder('r-2', '买菜'),
    reminder('r-3', '开会', { sourceType: 'CALENDAR_EVENT' }),
    reminder('r-4', '吃药'),
  ]

  notifier.dispatch(many, 'granted', false)

  eq('4 条合并为 1 条', sent.length, 1)
  eq('标题含数量', sent[0].title, '有 4 条提醒到点')
  eq('正文含前三条标题', sent[0].body.includes('交周报') && sent[0].body.includes('买菜'), true)
  eq('正文有「等」', sent[0].body.includes('等'), true)

  // 汇总之后，同样的 4 条不该再发
  const again = notifier.dispatch(many, 'granted', false)
  eq('汇总后不再重发', sent.length, 1)
  eq('全部被去重跳过', again.skipped, 4)
}

// ---- 恰好等于阈值：逐条发 ----

{
  const { sent, notify } = recorder()
  const notifier = new ReminderNotifier(notify)
  const three = [reminder('r-1', '一'), reminder('r-2', '二'), reminder('r-3', '三')]

  notifier.dispatch(three, 'granted', false)

  eq('3 条时逐条发', sent.length, 3)
}

// ---- 通知构造抛异常：降级为站内，且不冒泡 ----

{
  let calls = 0
  const notifier = new ReminderNotifier(() => {
    calls += 1
    throw new Error('通知不可用')
  })

  const result = notifier.dispatch([reminder('r-1', '交周报')], 'granted', false)

  eq('notify 被调用过', calls, 1)
  eq('降级为站内通道', result.channel, 'inline')
  eq('把未送出的内容交给调用方', result.notices.length, 1)
}

// ---- 降级后下一轮仍能继续工作（不被异常打断）----

{
  let shouldThrow = true
  const sent = []
  const notifier = new ReminderNotifier((payload) => {
    if (shouldThrow) {
      throw new Error('第一次失败')
    }
    sent.push(payload)
  })

  notifier.dispatch([reminder('r-1', '第一条')], 'granted', false)
  shouldThrow = false
  const second = notifier.dispatch([reminder('r-2', '第二条')], 'granted', false)

  eq('第二轮恢复发送', sent.length, 1)
  eq('第二轮通道恢复为系统通知', second.channel, 'browser')
}

// ---- createBrowserNotify：点击时聚焦并跳转 ----

{
  const calls = { focus: 0, navigate: 0 }
  const originalNotification = globalThis.Notification
  const originalWindow = globalThis.window

  const instances = []
  class FakeNotification {
    constructor(title, options) {
      this.title = title
      this.options = options
      this.onclick = null
      instances.push(this)
    }
  }
  globalThis.Notification = FakeNotification
  globalThis.window = {
    focus: () => {
      calls.focus += 1
    },
  }

  const notify = createBrowserNotify(() => {
    calls.navigate += 1
  })
  notify({ title: '标题', body: '正文', tag: 'tag-1' })

  eq('构造了一个通知', instances.length, 1)
  eq('标题透传', instances[0].title, '标题')
  eq('tag 透传', instances[0].options.tag, 'tag-1')

  instances[0].onclick()
  eq('点击时聚焦窗口', calls.focus, 1)
  eq('点击时跳转日程页', calls.navigate, 1)

  globalThis.Notification = originalNotification
  globalThis.window = originalWindow
}
