import type { ToolInvocation } from '@/types/api'

export const TOOL_STATUS_META: Record<string, { label: string; type: 'success' | 'danger' | 'warning' | 'info' }> = {
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  REJECTED: { label: '被拒绝', type: 'warning' },
  REQUIRES_CONFIRMATION: { label: '待确认', type: 'warning' },
  DUPLICATE: { label: '已去重', type: 'info' },
  IN_PROGRESS: { label: '执行中', type: 'info' },
  CONFIRMED: { label: '已确认', type: 'success' },
  CANCELLED: { label: '已取消', type: 'info' },
  EXPIRED: { label: '已过期', type: 'info' },
}

export function toolInvocationStatus(item: ToolInvocation): string {
  if (item.outcome !== 'REQUIRES_CONFIRMATION' || !item.confirmationStatus || item.confirmationStatus === 'PENDING') {
    return item.outcome
  }
  return item.confirmationStatus
}

export function toolInvocationMeta(item: ToolInvocation) {
  const status = toolInvocationStatus(item)
  return TOOL_STATUS_META[status] ?? { label: status, type: 'info' as const }
}
