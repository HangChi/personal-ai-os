import assert from 'node:assert/strict'
import * as chat from '../../../frontend/src/api/chat.ts'
import * as runs from '../../../frontend/src/api/chat-runs.ts'
import { getPlan } from '../../../frontend/src/api/plan.ts'
import { fetchUsageStats } from '../../../frontend/src/api/usage.ts'
import { http } from '../../../frontend/src/api/http.ts'

const base = new URL(process.env.GO_P4_BASE_URL)
assert.equal(base.hostname, '127.0.0.1', '只允许 Go 测试创建的回环服务')
const ids = []
const controller = new AbortController()
const timeout = setTimeout(() => controller.abort(), 30_000)
let checks = 0
const check = (value, message) => { assert.ok(value, message); checks++ }
const wait = async (id, expected) => {
  for (let i = 0; i < 200; i++) {
    const run = await runs.latestRun(id)
    if (run?.status === expected) return run
    await new Promise((resolve) => setTimeout(resolve, 20))
  }
  throw new Error(`等待 ${expected} 超时`)
}
const handlers = (state) => ({
  onRun: (run) => { state.run = run },
  onDelta: (text) => { state.delta = (state.delta ?? '') + text },
  onDone: (text) => { state.done = text },
  onError: (error) => { throw new Error(error) },
  onActivity: () => {}, onReset: () => {}, onConnection: () => {},
})
try {
  const session = await chat.createSession('frontend P4')
  const id = session.conversationId
  ids.push(id)
  check((await chat.renameSession(id, 'renamed frontend')).title === 'renamed frontend', '改名')
  check((await chat.setSessionModel(id, 'fixture-p4')).chatModel === 'fixture-p4', '切换模型')
  check((await chat.listSessions({ keyword: 'renamed frontend', size: 1 })).items.some((item) => item.conversationId === id), '分页搜索')
  const attachment = await chat.uploadAttachment(new File(['frontend attachment'], 'p4.txt', { type: 'text/plain' }))
  check(attachment.size === 19 && !!attachment.id, '实际 multipart 附件上传')
  const download = await http.get(`/chat/uploads/${attachment.id}/content`, { responseType: 'text' })
  check(download.data === 'frontend attachment', '附件读取')
  let state = {}
  await chat.streamChat(id, 'legacy frontend', handlers(state), controller.signal, { attachments: [attachment], useKnowledge: true })
  check(state.done === 'frontend Go reply' && state.delta === state.done, '旧 POST SSE')
  const messages = await chat.listMessages(id)
  check(messages.length === 2 && messages[0].attachments[0].id === attachment.id, '刷新消息与附件')
  check((await chat.listMessages(id, { limit: 1 })).length === 1, '历史分页')
  check((await chat.fetchContextUsage(id)).budgetTokens > 0, '上下文')
  check((await chat.setContextBudget(8000, id)).budgetTokens > 0, '持久预算')
  let run = await runs.startRun(id, 'run frontend', {}, 'frontend-run')
  state = {}
  await runs.followRun(run, handlers(state), controller.signal)
  check(state.done === 'frontend Go reply', 'runs GET SSE 与终态')
  check((await runs.startRun(id, 'run frontend', {}, 'frontend-run')).id === run.id, '原请求幂等回放')
  await assert.rejects(() => runs.startRun(id, 'other', {}, 'frontend-run'))
  check((await getPlan(id)) === null, '无计划时返回 null')
  let usage
  for (let i = 0; i < 100; i++) {
    usage = await fetchUsageStats(7)
    if (usage.turns >= 2) break
    await new Promise((resolve) => setTimeout(resolve, 20))
  }
  check(usage.turns >= 2, '完成后的异步用量')
  const audit = await http.get('/agent/tool-invocations', { params: { conversationId: id } })
  check(Array.isArray(audit.data.data.items), '执行审计分页')
  const exported = await http.get(`/chat/sessions/${id}/export`, { responseType: 'text', headers: { Accept: 'text/markdown' } })
  check(exported.data.includes('legacy frontend') && exported.data.includes('frontend Go reply'), 'Markdown 导出')
  check((await chat.rewindMessages(id, 3)).deleted >= 2, '编辑回退')

  const asking = await chat.createSession('ask frontend'); ids.push(asking.conversationId)
  run = await runs.startRun(asking.conversationId, 'ask frontend', {}, 'frontend-ask')
  run = await wait(asking.conversationId, 'WAITING_USER')
  check(run.question?.question === '选择下一步？', 'askUser WAITING_USER')
  await runs.submitFollowup(asking.conversationId, run.id, 'QUEUE', 'queued frontend', {}, 'frontend-queue')
  const edited = await runs.editFollowup(asking.conversationId, run.id, 'frontend-queue', 'edited queue')
  check(edited.followups[0].request.content === 'edited queue', '编辑排队输入')
  await runs.cancelFollowup(asking.conversationId, run.id, 'frontend-queue')
  await runs.submitFollowup(asking.conversationId, run.id, 'QUEUE', 'queued frontend 2', {}, 'frontend-queue2')
  await runs.answerQuestion(asking.conversationId, run.id, run.question.id, '继续')
  await wait(asking.conversationId, 'COMPLETED')
  for (let i = 0; i < 150 && (await runs.latestRun(asking.conversationId))?.id !== 'frontend-queue2'; i++) await new Promise((r) => setTimeout(r, 20))
  check((await wait(asking.conversationId, 'COMPLETED')).id === 'frontend-queue2', '完成后自动消费队列')

  const stopping = await chat.createSession('stop frontend'); ids.push(stopping.conversationId)
  run = await runs.startRun(stopping.conversationId, 'stop frontend', {}, 'frontend-stop')
  await runs.submitFollowup(stopping.conversationId, run.id, 'QUEUE', 'retain after stop', {}, 'retained-queue')
  await runs.stopRun(stopping.conversationId, run.id)
  run = await wait(stopping.conversationId, 'STOPPED')
  check(run.followups.some((f) => f.id === 'retained-queue' && f.status === 'PENDING'), '停止后保留队列')
  await runs.cancelFollowup(stopping.conversationId, run.id, 'retained-queue')
  check((await chat.fetchTranscriptionStatus()).available === false, '未配置语音时显式回退')
  console.log(`原前端 API → Go Foundation：${checks} 组端到端断言通过（JSON/文件/两种 SSE/队列/问答/停止）`)
} finally {
  clearTimeout(timeout)
  controller.abort()
  // stop 已被确认后等待执行器释放会话租约，避免掩盖清理错误。
  for (const id of ids.reverse()) {
    let last
    for (let i = 0; i < 50; i++) {
      try { await chat.deleteSession(id); last = null; break } catch (e) { last = e; await new Promise((r) => setTimeout(r, 20)) }
    }
    if (last) throw last
  }
}
