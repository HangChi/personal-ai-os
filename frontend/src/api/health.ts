import { requestData } from '@/api/http'
import type { HealthStatus } from '@/types/api'

/**
 * 后端健康检查。
 *
 * 对应 `GET /api/v1/health`，契约见
 * specs/001-project-skeleton/contracts/http-api.md。
 */
export function fetchHealthStatus(): Promise<HealthStatus> {
  return requestData<HealthStatus>({ url: '/health', method: 'get' })
}
