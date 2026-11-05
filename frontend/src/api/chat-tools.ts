import { requestData } from '@/api/http'

export interface DirectTool {
  name: string
  description: string
  inputSchema: string
}
export interface DirectToolResult {
  status: 'OK' | 'DUPLICATE' | 'REQUIRES_CONFIRMATION' | 'REJECTED' | 'ERROR'
  message: string
  data: unknown
  confirmationId: string | null
}
export interface DirectToolExecution {
  requestId: string
  tool: string
  result: DirectToolResult
  replayed: boolean
}
export const listDirectTools = (): Promise<DirectTool[]> => requestData({ url: '/chat/tools', method: 'get' })
export const executeDirectTool = (
  conversationId: string,
  tool: string,
  args: Record<string, unknown>,
  key: string,
): Promise<DirectToolExecution> =>
  requestData({
    url: '/chat/sessions/' + conversationId + '/tools',
    method: 'post',
    headers: { 'Idempotency-Key': key },
    data: { tool, arguments: args },
    timeout: 120_000,
  })
