import { createPinia, setActivePinia } from 'pinia'
import { rejects } from 'node:assert/strict'
import { eq } from './assert.mjs'
import { handlers } from './stub-api.mjs'

function freshStore() {
  setActivePinia(createPinia())
}
const { useMcpStore } = await import('@/stores/mcp')

// ---- MCP 未启用：是正常状态，不是错误 ----

{
  handlers.set('/mcp/servers', () => Promise.resolve({ enabled: false, servers: [] }))
  freshStore()
  const store = useMcpStore()
  await store.loadMcp()

  eq('未启用标记正确', store.mcp.enabled, false)
  eq('未启用时 server 列表为空', store.mcp.servers.length, 0)
  eq('未启用不是错误', store.error, null)
}

// ---- 外部连接失败不影响本系统服务 ----

{
  handlers.set('/mcp/servers', () => Promise.reject(new Error('502 Bad Gateway')))
  freshStore()
  const store = useMcpStore()
  await store.loadMcp()

  eq('mcpError 有值', store.error, '502 Bad Gateway')
  eq('本系统服务 error 不受影响', store.personalError, null)
  eq('mcpLoading 复位', store.loading, false)
}

// HTTP 200 只表示重连请求处理完成；工具服务仍不可用时必须反馈失败。
{
  handlers.set('/mcp/servers/fetch/reconnect', () => Promise.resolve(null))
  handlers.set('/mcp/servers', () =>
    Promise.resolve({
      enabled: true,
      servers: [{ name: 'fetch', available: false, failureReason: '连接超时', toolCount: 0, autoTools: [] }],
    }),
  )
  freshStore()
  const store = useMcpStore()
  await rejects(() => store.reconnectServer('fetch'), /连接超时/)
  eq('重连失败保留真实状态', store.mcp.servers[0].available, false)
}

{
  handlers.set('/mcp/servers', () =>
    Promise.resolve({
      enabled: true,
      servers: [{ name: 'fetch', available: true, failureReason: null, toolCount: 1, autoTools: ['fetch'] }],
    }),
  )
  freshStore()
  const store = useMcpStore()
  await store.reconnectServer('fetch')
  eq('重连成功刷新工具状态', store.mcp.servers[0].toolCount, 1)
}

{
  handlers.set('/mcp/servers', () => Promise.reject(new Error('状态查询失败')))
  freshStore()
  const store = useMcpStore()
  await rejects(() => store.reconnectServer('fetch'), /状态查询失败/)
}
