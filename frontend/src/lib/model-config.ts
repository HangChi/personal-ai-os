/**
 * 模型配置页的纯函数：来源标注、校验、表单与生效值的合并。
 *
 * 按项目约定，视图只做编排，判定逻辑全放这里，可被 `.mjs` 单测覆盖。
 */

import type { ModelConfig, ModelConfigSource, SaveModelConfigPayload } from '@/types/api'

/** 温度允许范围（与后端 ModelConfigController 的常量一致） */
export const TEMPERATURE_MIN = 0
export const TEMPERATURE_MAX = 2

/** 字段长度上限（与 V20 列宽一致） */
export const MODEL_NAME_MAX = 120

/**
 * 来源的中文标签。
 *
 * `DATABASE` 用「设置页」而不是「数据库」：用户不知道背后是数据库，
 * 但一定知道自己在设置页填过值——这个标签的作用就是让用户认出
 * 「是我改的」还是「环境变量带过来的」。
 */
export const SOURCE_LABELS: Record<ModelConfigSource, string> = {
  DATABASE: '设置页',
  ENVIRONMENT: '环境变量',
  DEFAULT: '内置默认',
}

/** 来源对应的 Element Plus tag 类型 */
export const SOURCE_TAG_TYPES: Record<ModelConfigSource, 'primary' | 'info' | 'warning'> = {
  DATABASE: 'primary',
  ENVIRONMENT: 'info',
  DEFAULT: 'warning',
}

/**
 * 来源的悬浮说明。
 *
 * 环境变量那一项要说清「改哪里」：这是最常见的一类困惑
 * （用户在 .env 里改了却不生效，因为设置页覆盖了）。
 */
export function sourceHint(source: ModelConfigSource): string {
  switch (source) {
    case 'DATABASE':
      return '该值在设置页保存过，优先于环境变量'
    case 'ENVIRONMENT':
      return '来自 .env / application.yml；在设置页保存后会改为使用设置页的值'
    default:
      return '环境变量与设置页都没有设置，使用代码内置默认值'
  }
}

/** 表单模型：与生效配置的区别是密钥永远为空（后端不回明文） */
export interface ModelConfigForm {
  baseUrl: string
  /**
   * 密钥输入框。
   *
   * 恒以空串呈现——不是「密钥为空」，而是「没有回填」。用户不填就表示不改动。
   * 这个区分由 {@link buildSavePayload} 负责，不要试图在这里塞掩码值：
   * 把 `****8f3a` 提交回去会把真实密钥覆盖成这七个字符。
   */
  apiKey: string
  chatModel: string
  temperature: number | null
  embeddingModel: string
}

/** 把生效配置转成表单初值 */
export function toForm(config: ModelConfig): ModelConfigForm {
  return {
    baseUrl: config.baseUrl ?? '',
    apiKey: '',
    chatModel: config.chatModel ?? '',
    temperature: config.temperature,
    embeddingModel: config.embeddingModel ?? '',
  }
}

/**
 * 表单 → 请求体。
 *
 * 关键是密钥的三态：
 * - 输入框仍为空（用户没碰）→ **不传**该字段（`undefined`），后端保持原值；
 * - 用户填了非空 → 传明文，覆盖；
 * - 用户想清空 → 需要一个显式动作，由 {@link buildSavePayload} 的
 *   `clearApiKey` 参数表达。仅凭「空输入框」无法区分「没碰」与「想清空」，
 *   这正是后端把 `null` 与 `""` 分开的原因。
 *
 * @param form       表单值
 * @param clearApiKey 用户是否显式点了「清除密钥」
 */
export function buildSavePayload(form: ModelConfigForm, clearApiKey = false): SaveModelConfigPayload {
  const trimmedKey = form.apiKey.trim()
  let apiKey: string | null | undefined
  if (clearApiKey) {
    apiKey = ''
  } else if (trimmedKey.length > 0) {
    apiKey = trimmedKey
  } else {
    // 不传：后端 SaveModelConfigRequest.apiKey 为 null 时保持原值
    apiKey = undefined
  }

  return {
    baseUrl: blankToNull(form.baseUrl),
    apiKey,
    chatModel: blankToNull(form.chatModel),
    temperature: form.temperature,
    embeddingModel: blankToNull(form.embeddingModel),
  }
}

/**
 * 表单校验。
 *
 * 与后端 `ModelConfigController.validate` 保持同一套规则——前端拦一遍是为了
 * 快速反馈，后端那一遍才是权威。两边规则不一致时以能报出更清楚信息的一侧为准，
 * 但绝不能让前端放过后端会拒的值（那样用户会看到「保存后无变化」）。
 *
 * @returns 错误提示；通过校验时返回 null
 */
export function validateForm(form: ModelConfigForm): string | null {
  const baseUrl = form.baseUrl.trim()
  if (baseUrl.length > 0) {
    if (!/^https?:\/\//.test(baseUrl)) {
      return '端点地址必须以 http:// 或 https:// 开头'
    }
    if (baseUrl.length > 500) {
      return '端点地址长度不能超过 500 个字符'
    }
  }
  if (form.apiKey.trim().length > 500) {
    return 'API Key 长度不能超过 500 个字符'
  }
  if (form.chatModel.trim().length > MODEL_NAME_MAX) {
    return `对话模型名长度不能超过 ${MODEL_NAME_MAX} 个字符`
  }
  if (form.embeddingModel.trim().length > MODEL_NAME_MAX) {
    return `嵌入模型名长度不能超过 ${MODEL_NAME_MAX} 个字符`
  }
  if (form.temperature != null) {
    if (!Number.isFinite(form.temperature)) {
      return '温度必须是一个数字'
    }
    if (form.temperature < TEMPERATURE_MIN || form.temperature > TEMPERATURE_MAX) {
      return `温度必须在 ${TEMPERATURE_MIN} 到 ${TEMPERATURE_MAX} 之间`
    }
  }
  return null
}

/**
 * 表单是否有未保存的改动。
 *
 * <p>用于「保存」按钮的可用态与离开确认。密钥框非空也算改动（用户填了就是想换）。
 *
 * <p>温度比较要容忍 `null` 与 `0` 的区别：`null` 是「沿用服务端默认」，
 * `0` 是「完全确定性输出」，两者语义不同，不能当成相等。
 */
export function isDirty(form: ModelConfigForm, config: ModelConfig | null): boolean {
  if (!config) {
    return false
  }
  return (
    form.baseUrl.trim() !== (config.baseUrl ?? '') ||
    form.apiKey.trim().length > 0 ||
    form.chatModel.trim() !== (config.chatModel ?? '') ||
    form.embeddingModel.trim() !== (config.embeddingModel ?? '') ||
    form.temperature !== config.temperature
  )
}

/**
 * 掩码密钥的展示文本。
 *
 * 后端已经返回 `****8f3a`，这里只负责把「没有密钥」的情况说清楚，
 * 而不是显示一个空占位让人以为加载失败。
 */
export function apiKeyDisplay(config: ModelConfig | null): string {
  if (!config) {
    return '—'
  }
  return config.hasApiKey ? config.apiKeyMasked : '未设置'
}

/** 连通性测试结果的一句话摘要 */
export function testResultSummary(result: {
  ok: boolean
  latencyMs: number
  replyPreview: string | null
  error: string | null
}): string {
  if (!result.ok) {
    return result.error ?? '测试失败'
  }
  const latency = result.latencyMs >= 1000 ? `${(result.latencyMs / 1000).toFixed(1)} 秒` : `${result.latencyMs} 毫秒`
  const reply = result.replyPreview?.trim()
  return reply ? `连通正常（${latency}），模型回复：${reply}` : `连通正常（${latency}）`
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed.length === 0 ? null : trimmed
}
