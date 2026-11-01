import { toolInvocationsByMessage, confirmationFeedback } from '@/lib/chat-tool-history'
import { eq } from './assert.mjs'

const message = (id, role, time) => ({ id, role, createdAt: `2026-11-02T10:${time}:00Z` })
const tool = (id, time, turnId, extra = {}) => ({ id, createdAt: `2026-11-02T10:${time}:00Z`, turnId, ...extra })
const messages = [
  message(1, 'USER', '00'),
  message(2, 'ASSISTANT', '02'),
  message(3, 'USER', '03'),
  message(4, 'ASSISTANT', '05'),
  message(5, 'USER', '06'),
  message(6, 'ASSISTANT', '07'),
]
const records = [tool(3, '04', 'new'), tool(2, '01', 'old'), tool(1, '00', 'old')]
let grouped = toolInvocationsByMessage(messages, records)
eq(
  '保留早期回答的完整工具轨迹，按实际调用顺序显示',
  grouped
    .get(2)
    .map((item) => item.id)
    .join(','),
  '1,2',
)
eq(
  '新一轮的工具只挂到本轮回答',
  grouped
    .get(4)
    .map((item) => item.id)
    .join(','),
  '3',
)
eq('后续闲聊不继承上一轮工具', grouped.has(6), false)
eq('不会把工具放到用户消息上', grouped.has(3), false)
grouped = toolInvocationsByMessage(messages.slice(2, 4), [
  tool(8, '03', 'failed-attempt', { outcome: 'FAILED' }),
  tool(9, '04', 'retry', { outcome: 'SUCCESS' }),
])
eq(
  '同一消息时间段内仅展示最新尝试，不混入重试前失败',
  grouped
    .get(4)
    .map((item) => item.id)
    .join(','),
  '9',
)
grouped = toolInvocationsByMessage([messages[1], ...messages.slice(2)], records)
eq('分页首条缺少对应用户消息时，不猜测关联旧工具', grouped.has(2), false)
grouped = toolInvocationsByMessage(messages.slice(0, 2), [tool(1, '01', null), tool(2, '04', null)])
eq('没有轮次标识的旧记录按消息时间段关联', grouped.get(2).length, 1)
eq(
  '新消息正在生成时，不把新工具挂到旧回答',
  toolInvocationsByMessage([...messages.slice(0, 2), messages[2]], records).has(4),
  false,
)
eq('无工具记录时不显示空轨迹', toolInvocationsByMessage(messages, []).size, 0)

const approval = (status) => ({ outcome: 'REQUIRES_CONFIRMATION', confirmationStatus: status })
eq('全部已确认提供实时完成反馈', confirmationFeedback([approval('CONFIRMED')]), '人工确认已完成')
eq('仍有待确认不能显示全部完成', confirmationFeedback([approval('CONFIRMED'), approval('PENDING')]), null)
eq('旧接口状态未知不能假定已确认', confirmationFeedback([approval(null)]), null)
eq(
  '取消、过期和失败不算待确认也不误报成功',
  confirmationFeedback([approval('CANCELLED'), approval('EXPIRED'), approval('FAILED')]),
  '已取消 1 项，已过期 1 项，执行失败 1 项',
)
eq('普通调用不显示人工确认状态', confirmationFeedback([{ outcome: 'SUCCESS' }]), null)
