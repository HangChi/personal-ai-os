import type { DirectTool } from '@/api/chat-tools'

export interface ChatCommand {
  name: string
  description: string
  usage: string
}
export const CHAT_COMMANDS: ChatCommand[] = [
  { name: 'help', description: '查看快捷指令用法', usage: '/help' },
  { name: 'tasks', description: '直接查询任务，可加关键词', usage: '/tasks [关键词]' },
  { name: 'task', description: '直接创建一个任务', usage: '/task <任务标题>' },
  { name: 'done', description: '按 ID 完成任务', usage: '/done <任务 ID>' },
  { name: 'delete-task', description: '申请删除任务，仍需确认', usage: '/delete-task <任务 ID> [更多 ID]' },
  { name: 'calendar', description: '查询未来七天或指定日期的日程', usage: '/calendar [YYYY-MM-DD]' },
  { name: 'reminders', description: '查看当前到点的提醒', usage: '/reminders' },
  { name: 'search', description: '直接检索知识库', usage: '/search <关键词或问题>' },
  { name: 'tools', description: '查看可用工具及 JSON 参数', usage: '/tools' },
  { name: 'tool', description: '按名称直接调用工具', usage: '/tool <工具名> [JSON 对象]' },
  { name: 'new', description: '新建对话', usage: '/new' },
  { name: 'stop', description: '停止当前任务', usage: '/stop' },
  { name: 'resume', description: '继续中断的任务，保留结果核查限制', usage: '/resume' },
]
export type ParsedCommand =
  | { kind: 'chat'; content: string }
  | { kind: 'local'; action: 'help' | 'tools' | 'new' | 'stop' | 'resume' }
  | { kind: 'tool'; tool: string; arguments: Record<string, unknown> }

export function isCommandInput(value: string): boolean {
  const text = value.trimStart()
  return text.startsWith('/') && !text.startsWith('//')
}
function space(value: string): boolean {
  return value.trim() === ''
}
function splitHead(value: string): [string, string] {
  let index = 0
  while (index < value.length && !space(value[index]!)) index++
  return [value.slice(0, index), value.slice(index).trim()]
}
export function parseChatCommand(input: string): ParsedCommand {
  const text = input.trim()
  if (text.startsWith('//')) return { kind: 'chat', content: text.slice(1) }
  if (!text.startsWith('/')) return { kind: 'chat', content: input }
  const [token, args] = splitHead(text.slice(1))
  const name = token.toLowerCase()
  const command = CHAT_COMMANDS.find((item) => item.name === name)
  if (!command) throw new Error('未知快捷指令，输入 /help 查看用法；发送以 / 开头的普通文本请使用 //')
  const usage = (): never => {
    throw new Error('用法：' + command.usage)
  }
  const invoke = (tool: string, values: Record<string, unknown> = {}): ParsedCommand => ({
    kind: 'tool',
    tool,
    arguments: values,
  })
  switch (name) {
    case 'help':
    case 'tools':
    case 'new':
    case 'stop':
    case 'resume':
      if (args) usage()
      return { kind: 'local', action: name }
    case 'tasks':
      return invoke('findTasks', args ? { keyword: args, limit: 20 } : { limit: 20 })
    case 'task':
      if (!args || args.length > 200) usage()
      return invoke('createTask', { title: args })
    case 'done':
      if (!uuid(args)) usage()
      return invoke('setTaskCompleted', { taskId: args, completed: true })
    case 'delete-task': {
      const ids = Array.from(args, (character) =>
        space(character) || character === ',' || character === '，' ? ' ' : character,
      )
        .join('')
        .split(' ')
        .filter(Boolean)
      if (!ids.length || ids.length > 50 || !ids.every(uuid)) usage()
      return invoke('deleteTask', { taskIds: [...new Set(ids)] })
    }
    case 'calendar': {
      if (!args) return invoke('findCalendarEvents')
      if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(args)) usage()
      const from = new Date(args + 'T00:00:00')
      if (
        Number.isNaN(from.getTime()) ||
        from.getFullYear() !== Number(args.slice(0, 4)) ||
        from.getMonth() + 1 !== Number(args.slice(5, 7)) ||
        from.getDate() !== Number(args.slice(8))
      )
        usage()
      const to = new Date(from)
      to.setDate(to.getDate() + 1)
      return invoke('findCalendarEvents', { from: from.toISOString(), to: to.toISOString() })
    }
    case 'reminders':
      if (args) usage()
      return invoke('findDueReminders')
    case 'search':
      if (!args || args.length > 500) usage()
      return invoke('searchKnowledge', { query: args, topK: 4 })
    case 'tool': {
      const [tool, payload] = splitHead(args)
      if (!tool) return usage()
      let values: unknown = {}
      try {
        values = payload ? JSON.parse(payload) : {}
      } catch {
        throw new Error('工具参数必须是有效 JSON 对象，请输入 /tools 查看参数定义')
      }
      if (values === null || Array.isArray(values) || typeof values !== 'object')
        throw new Error('工具参数必须是 JSON 对象，不能是数组、null 或普通文本')
      return invoke(tool, values as Record<string, unknown>)
    }
    default:
      return usage()
  }
}
function uuid(value: string): boolean {
  return /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value)
}

export interface CommandSuggestion {
  key: string
  label: string
  description: string
  completion: string
}
export function commandSuggestions(input: string, tools: DirectTool[] = []): CommandSuggestion[] {
  if (!isCommandInput(input) || input.includes(String.fromCharCode(10))) return []
  const text = input.trimStart()
  const [head] = splitHead(text)
  if (head.toLowerCase() === '/tool' && text.length > head.length) {
    const rawQuery = text.slice(head.length).trimStart()
    if (Array.from(rawQuery).some(space)) return []
    return tools
      .filter((tool) => tool.name.toLowerCase().includes(rawQuery.toLowerCase()))
      .map((tool) => ({
        key: tool.name,
        label: tool.name,
        description: tool.description,
        completion: '/tool ' + tool.name + ' ',
      }))
  }
  if (Array.from(text).some(space)) return []
  const query = text.slice(1).toLowerCase()
  return CHAT_COMMANDS.filter((command) => command.name.startsWith(query) || command.description.includes(query))
    .sort((a, b) => Number(b.name === query) - Number(a.name === query))
    .map((command) => ({
      key: command.name,
      label: '/' + command.name,
      description: command.description,
      completion: '/' + command.name + ' ',
    }))
}

/** Object order and whitespace must not mint a new transport identity. */
export function commandSignature(tool: string, args: Record<string, unknown>): string {
  const canonical = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map(canonical)
    if (value !== null && typeof value === 'object')
      return Object.fromEntries(
        Object.entries(value)
          .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
          .map(([key, item]) => [key, canonical(item)]),
      )
    return value
  }
  return JSON.stringify([tool, canonical(args)])
}
