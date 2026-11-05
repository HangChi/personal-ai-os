import { webcrypto } from 'node:crypto'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { commandSignature, commandSuggestions, parseChatCommand } from '@/lib/chat-commands'
import { useChatCommandsStore } from '@/stores/chat-commands'
import type { DirectToolExecution } from '@/api/chat-tools'

const { executeDirectTool, listDirectTools } = vi.hoisted(() => ({
  executeDirectTool: vi.fn(),
  listDirectTools: vi.fn(),
}))
vi.mock('@/api/chat-tools', () => ({ executeDirectTool, listDirectTools }))
const success = (): DirectToolExecution => ({
  requestId: 'receipt',
  tool: 'createTask',
  result: { status: 'OK', message: '已创建', data: { id: 'one' }, confirmationId: null },
  replayed: false,
})
beforeEach(() => {
  setActivePinia(createPinia())
  sessionStorage.clear()
  vi.stubGlobal('crypto', webcrypto)
  vi.resetAllMocks()
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('快捷指令解析', () => {
  it('普通消息与双斜线转义保持为聊天，不调用模型解释指令', () => {
    expect(parseChatCommand('hello')).toEqual({ kind: 'chat', content: 'hello' })
    expect(parseChatCommand('//task ordinary')).toEqual({ kind: 'chat', content: '/task ordinary' })
  })
  it.each([
    ['/tasks', 'findTasks', { limit: 20 }],
    ['/tasks 报销', 'findTasks', { keyword: '报销', limit: 20 }],
    ['/task 买牛奶', 'createTask', { title: '买牛奶' }],
    ['/search 资料', 'searchKnowledge', { query: '资料', topK: 4 }],
    ['/calendar', 'findCalendarEvents', {}],
    ['/reminders', 'findDueReminders', {}],
  ])('解析 %s', (input, tool, args) => {
    expect(parseChatCommand(input as string)).toEqual({ kind: 'tool', tool, arguments: args })
  })
  it('按真实 ID 完成和申请删除，去除重复 ID', () => {
    const id = '10000000-0000-4000-8000-000000000001'
    expect(parseChatCommand('/done ' + id)).toEqual({
      kind: 'tool',
      tool: 'setTaskCompleted',
      arguments: { taskId: id, completed: true },
    })
    expect(parseChatCommand('/delete-task ' + id + '，' + id)).toEqual({
      kind: 'tool',
      tool: 'deleteTask',
      arguments: { taskIds: [id] },
    })
  })
  it('日期按本地日历日计算，含完整日期验证', () => {
    const result = parseChatCommand('/calendar 2026-11-05')
    const from = new Date(2026, 10, 5),
      to = new Date(2026, 10, 6)
    expect(result).toEqual({
      kind: 'tool',
      tool: 'findCalendarEvents',
      arguments: { from: from.toISOString(), to: to.toISOString() },
    })
  })
  it.each([
    '/unknown',
    '/task',
    '/done 1',
    '/delete-task latest',
    '/calendar 2026-02-30',
    '/help extra',
    '/tool',
    '/tool createTask null',
    '/tool createTask []',
    '/tool createTask {bad}',
  ])('非法指令不会降级成聊天 %s', (input) => {
    expect(() => parseChatCommand(input)).toThrow()
  })
  it('支持 JSON 工具参数和按名称补全', () => {
    expect(parseChatCommand('/tool createTask {"title":"hello"}')).toEqual({
      kind: 'tool',
      tool: 'createTask',
      arguments: { title: 'hello' },
    })
    const tools = [{ name: 'mcp_local_read', description: '读取', inputSchema: '{}' }]
    expect(commandSuggestions('/ta').map((item) => item.label)).toEqual(['/tasks', '/task'])
    expect(commandSuggestions('/tool mcp', tools)[0]?.completion).toBe('/tool mcp_local_read ')
    expect(commandSuggestions('/tool mcp_local_read {}', tools)).toEqual([])
    expect(commandSuggestions('//task')).toEqual([])
  })
})
describe('快捷操作请求身份', () => {
  it('键顺序无关，数组顺序保留', () => {
    expect(commandSignature('x', { z: 2, a: { z: 3, a: 1 } })).toBe(commandSignature('x', { a: { a: 1, z: 3 }, z: 2 }))
    expect(commandSignature('x', { a: [1, 2] })).not.toBe(commandSignature('x', { a: [2, 1] }))
  })
  it('响应丢失后刷新仍沿用相同 UUID，成功后的新操作使用新 UUID', async () => {
    executeDirectTool.mockRejectedValueOnce(new Error('connection lost')).mockResolvedValue(success())
    const first = useChatCommandsStore()
    await first.execute('c', '/task 私密', 'createTask', { title: '私密' })
    expect(executeDirectTool).toHaveBeenCalledTimes(1)
    const key = executeDirectTool.mock.calls[0]![3]
    expect(sessionStorage.getItem('os:chat-command-attempt:c')).not.toContain('私密')
    expect(first.entries.c![0]?.error).toContain('结果未确认')
    setActivePinia(createPinia())
    const refreshed = useChatCommandsStore()
    await refreshed.execute('c', '/task 私密', 'createTask', { title: '私密' })
    expect(executeDirectTool.mock.calls[1]![3]).toBe(key)
    await refreshed.execute('c', '/task 私密', 'createTask', { title: '私密' })
    expect(executeDirectTool.mock.calls[2]![3]).not.toBe(key)
  })
  it('多个未确认操作分别保留身份，不被后续命令覆盖', async () => {
    executeDirectTool.mockRejectedValue(new Error('lost'))
    const store = useChatCommandsStore()
    await store.execute('c', 'one', 'createTask', { title: 'one' })
    await store.execute('c', 'two', 'createTask', { title: 'two' })
    await store.execute('c', 'one', 'createTask', { title: 'one' })
    await store.execute('other', 'one', 'createTask', { title: 'one' })
    expect(executeDirectTool.mock.calls[2]![3]).toBe(executeDirectTool.mock.calls[0]![3])
    expect(executeDirectTool.mock.calls[1]![3]).not.toBe(executeDirectTool.mock.calls[0]![3])
    expect(executeDirectTool.mock.calls[3]![3]).not.toBe(executeDirectTool.mock.calls[0]![3])
  })
  it('错误结果保留身份，待确认回放保留确认编号', async () => {
    executeDirectTool
      .mockResolvedValueOnce({ ...success(), result: { ...success().result, status: 'ERROR' } })
      .mockResolvedValueOnce({
        ...success(),
        result: { ...success().result, status: 'REQUIRES_CONFIRMATION', confirmationId: 'original-confirmation' },
        replayed: true,
      })
    const store = useChatCommandsStore()
    await store.execute('c', 'delete', 'deleteTask', { taskIds: ['one'] })
    const result = await store.execute('c', 'delete', 'deleteTask', { taskIds: ['one'] })
    expect(executeDirectTool.mock.calls[1]![3]).toBe(executeDirectTool.mock.calls[0]![3])
    expect(result?.result.confirmationId).toBe('original-confirmation')
  })
  it('阻止重复点击，异步结果归属原会话', async () => {
    let finish!: (value: DirectToolExecution) => void
    executeDirectTool.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const store = useChatCommandsStore()
    const pending = store.execute('c', 'one', 'createTask', { title: 'one' })
    expect(await store.execute('other', 'two', 'createTask', { title: 'two' })).toBeNull()
    await vi.waitFor(() => expect(executeDirectTool).toHaveBeenCalledOnce())
    finish(success())
    await pending
    expect(store.entries.c![0]?.busy).toBe(false)
    expect(store.entries.other).toBeUndefined()
  })
  it('无法持久化操作标识时不发送请求', async () => {
    vi.stubGlobal('sessionStorage', {
      getItem: () => null,
      setItem: () => {
        throw new Error('storage denied')
      },
    })
    const store = useChatCommandsStore()
    await store.execute('c', 'one', 'createTask', { title: 'one' })
    expect(executeDirectTool).not.toHaveBeenCalled()
    expect(store.entries.c![0]?.error).toContain('本次未发送')
  })
  it('收到成功回执后清理存储失败仍保留已知结果与原身份', async () => {
    const storage = new Map<string, string>()
    let reads = 0
    vi.stubGlobal('sessionStorage', {
      getItem: (key: string) => {
        if (++reads === 2) throw new Error('storage denied after reply')
        return storage.get(key) ?? null
      },
      setItem: (key: string, value: string) => storage.set(key, value),
    })
    executeDirectTool.mockResolvedValue(success())
    const store = useChatCommandsStore()
    expect(await store.execute('c', 'one', 'createTask', { title: 'one' })).toEqual(success())
    expect(store.entries.c![0]?.error).toBeUndefined()
    await store.execute('c', 'one', 'createTask', { title: 'one' })
    expect(executeDirectTool.mock.calls[1]![3]).toBe(executeDirectTool.mock.calls[0]![3])
  })
})
