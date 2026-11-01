import { requestData } from '@/api/http'
import type { McpStatus, PersonalMcpStatus } from '@/types/api'

export function fetchMcpStatus(): Promise<McpStatus> {
  return requestData<McpStatus>({ url: '/mcp/servers', method: 'get' })
}

export function fetchPersonalMcpStatus(): Promise<PersonalMcpStatus> {
  return requestData<PersonalMcpStatus>({ url: '/mcp/server', method: 'get' })
}

export function reconnectMcpServer(name: string): Promise<void> {
  return requestData<void>({ url: `/mcp/servers/${encodeURIComponent(name)}/reconnect`, method: 'post' })
}
