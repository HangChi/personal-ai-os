import { requestData } from '@/api/http'
import type { ModelConfig, ModelConnectionTestResult, SaveModelConfigPayload } from '@/types/api'

/**
 * 大模型 API 配置接口客户端（设置页）。
 *
 * 后端契约见 `ModelConfigController`（`/api/v1/settings/model`）。
 *
 * <h2>安全边界</h2>
 * 后端**任何响应都不含明文密钥**，只回 `****8f3a` 形式的掩码。因此本模块
 * 没有任何「读回密钥」的函数——读不到，也不需要读：表单里密钥框留空就是
 * 「不改动」，用户想换才填。
 */

/** 当前生效的模型配置（密钥已掩码） */
export function getModelConfig(): Promise<ModelConfig> {
  return requestData<ModelConfig>({ url: '/settings/model', method: 'get' })
}

/**
 * 保存配置并立即热切换聊天模型。
 *
 * <p>超时放宽到 30 秒：这个接口内部会重建模型实例，跨境端点偶发慢；
 * 而全局 `http` 的 10 秒会让「明明保存成功」被前端判成超时。
 *
 * <p>返回的 `ModelConfig` 是保存后的生效值。注意：保存与切换是两步
 * （见后端 `ModelConfigController.save`），切换失败时后端返回业务错误，
 * 但配置**已经落库**——此时刷新页面能看到新的值。
 */
export function saveModelConfig(payload: SaveModelConfigPayload): Promise<ModelConfig> {
  return requestData<ModelConfig>({
    url: '/settings/model',
    method: 'put',
    data: payload,
    timeout: 30_000,
  })
}

/**
 * 用当前生效的模型真发一次最小请求。
 *
 * <p>用 POST：它会产生一次真实调用（可能计费），不该被缓存或预取。
 * 失败也返回 200，由 `ok` 字段表达——这是查询语义，不是接口错误。
 */
export function testModelConnection(): Promise<ModelConnectionTestResult> {
  return requestData<ModelConnectionTestResult>({
    url: '/settings/model/test',
    method: 'post',
    timeout: 30_000,
  })
}
