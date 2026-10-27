import { check, eq } from './assert.mjs'
import { matchMessageIds, nextMatchCursor } from '@/lib/chat-search'

/**
 * 会话内搜索的纯逻辑。
 *
 * <p>这两条规则写错都不会报错，只会让用户觉得「搜索坏了」：
 * 关键词为空却显示「1/50」、或者连点下一处到末尾后不再回到起点。
 */

const messages = [
  { id: 1, content: '下周三有空吗' },
  { id: 2, content: '周三下午有课。' },
  { id: 3, content: null },
  { id: 4, content: '排课表已更新' },
]

// ---------- 匹配 ----------
eq('命中两条含「周三」的消息', matchMessageIds(messages, '周三').join(','), '1,2')
eq('大小写不敏感', matchMessageIds([{ id: 9, content: 'Docker 部署' }], 'docker').join(','), '9')
eq('首尾空白被忽略', matchMessageIds(messages, '  排课  ').join(','), '4')
eq('关键词为空时无匹配（不返回全部）', matchMessageIds(messages, '   ').length, 0)
eq('内容为 null 的消息不报错也不命中', matchMessageIds(messages, '随便').length, 0)

// ---------- 游标循环 ----------
eq('正常前进', nextMatchCursor(0, 3, 1), 1)
eq('末尾回到开头', nextMatchCursor(2, 3, 1), 0)
eq('开头往前回到末尾', nextMatchCursor(0, 3, -1), 2)
eq('没有命中时游标归零', nextMatchCursor(5, 0, 1), 0)
// 命中数变小后旧游标可能越界：取模要能兜住
check('越界游标不会算出负数或越界值', nextMatchCursor(9, 3, 1) === 1)
