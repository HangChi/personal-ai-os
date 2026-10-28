/**
 * 多套模型配置页的纯函数：卡片摘要、校验、排序、表单转换。
 *
 * <p>按项目约定，视图只做编排，判定逻辑全放这里，由 `.mjs` 单测覆盖。
 * 这些函数全部无副作用、不 import 组件库，因此测试里能直接调用。
 */

import type { ModelConfigSource, ModelListResult, ModelProfile, ModelProtocol } from '@/types/api'

/** 温度允许范围（与后端 ModelProfileController 常量一致） */
export const TEMPERATURE_MIN = 0
export const TEMPERATURE_MAX = 2

/** 字段长度上限（与 V21/V22 列宽一致） */
export const MODEL_NAME_MAX = 120
export const PROFILE_NAME_MAX = 60

/**
 * 协议的中文标签。
 *
 * <p>界面上必须把「协议」讲成「获取模型列表用的协议」，而不是
 * 「聊天用的协议」—— 后者会让用户以为选了 Anthropic 就用 Anthropic 原生
 * API 聊天。实际聊天链路始终走 OpenAI 兼容（Ollama 也用它的 /v1 兼容端点）。
 */
export const PROTOCOL_LABELS: Record<ModelProtocol, string> = {
  OPENAI: 'OpenAI 兼容',
  ANTHROPIC: 'Anthropic',
  OLLAMA: 'Ollama',
}

/**
 * 各协议的模型列表接口说明，作为下拉项的描述与悬浮提示。
 *
 * <p>把路径直接写出来是有意为之：用户遇到「获取列表失败」时，
 * 第一个要确认的就是「它到底请求了哪个地址」。这段文字是唯一的线索来源。
 */
export const PROTOCOL_HINTS: Record<ModelProtocol, string> = {
  OPENAI: 'GET {端点}/v1/models，用 Bearer 鉴权。绝大多数厂商与中转站都属这一类',
  ANTHROPIC: 'GET {端点}/v1/models，用 x-api-key 鉴权（不是 Bearer）。Anthropic 没有嵌入模型',
  OLLAMA: 'GET {主机}/api/tags，本地默认无需密钥。填了 /v1 会自动剥掉',
}

/** 协议下拉的可选项（顺序即推荐顺序） */
export const PROTOCOL_OPTIONS: Array<{ value: ModelProtocol; label: string; hint: string }> = (
  ['OPENAI', 'ANTHROPIC', 'OLLAMA'] as ModelProtocol[]
).map((value) => ({ value, label: PROTOCOL_LABELS[value], hint: PROTOCOL_HINTS[value] }))

/** 协议默认值：绝大多数端点都是 OpenAI 兼容 */
export const DEFAULT_PROTOCOL: ModelProtocol = 'OPENAI'

/**
 * 来源的中文标签。
 *
 * `DATABASE` 用「本配置」而不是「数据库」：多配置场景下「数据库」这个说法
 * 会让人误以为是「某处存着的某个值」，而实际含义是「这张卡片自己填了」。
 */
export const SOURCE_LABELS: Record<ModelConfigSource, string> = {
  DATABASE: '本配置',
  ENVIRONMENT: '环境变量',
  DEFAULT: '内置默认',
}

/** 来源对应的 Element Plus tag 类型 */
export const SOURCE_TAG_TYPES: Record<ModelConfigSource, 'primary' | 'info' | 'warning'> = {
  DATABASE: 'primary',
  ENVIRONMENT: 'info',
  DEFAULT: 'warning',
}

/** 来源说明，悬浮提示用 */
export function sourceHint(source: ModelConfigSource): string {
  switch (source) {
    case 'DATABASE':
      return '这张卡片自己填了该项，优先于环境变量'
    case 'ENVIRONMENT':
      return '这张卡片没填该项，用了 .env / application.yml 里的值'
    default:
      return '环境变量与本卡片都没设置，使用代码内置默认值'
  }
}

/**
 * 新建/编辑配置的表单模型。
 *
 * <p>密钥恒以空串呈现 —— 不是「密钥为空」，而是「没有回填」。
 * 这个区分由 {@link buildProfilePayload} 负责。把后端返回的掩码
 * `****8f3a` 回填进输入框再提交，会把真实密钥覆盖成这七个字符。
 */
export interface ModelProfileForm {
  name: string
  presetId: string | null
  baseUrl: string
  /** 协议：只影响「获取模型列表」打哪个接口，不影响聊天 */
  protocol: ModelProtocol
  apiKey: string
  chatModel: string
  temperature: number | null
  embeddingModel: string
}

/** 空表单（新建时用） */
export function emptyForm(presetId: string | null = null): ModelProfileForm {
  return {
    name: '',
    presetId,
    baseUrl: '',
    protocol: DEFAULT_PROTOCOL,
    apiKey: '',
    chatModel: '',
    temperature: null,
    embeddingModel: '',
  }
}

/**
 * 配置 → 编辑表单。
 *
 * <p>注意 {@code baseUrl} 等取的是**生效值**（可能是环境变量兜底来的）。
 * 这是有意的：用户点「编辑」看到的是「现在实际在用什么」，改哪项就存哪项。
 * 代价是保存后该字段的来源会从「环境变量」变成「本配置」—— 因为用户确实
 * 显式确认了这个值。要恢复回落，清空该输入框再保存。
 */
export function toForm(profile: ModelProfile): ModelProfileForm {
  return {
    name: profile.name,
    presetId: profile.presetId,
    baseUrl: profile.baseUrl ?? '',
    protocol: profile.protocol ?? DEFAULT_PROTOCOL,
    apiKey: '',
    chatModel: profile.chatModel ?? '',
    temperature: profile.temperature,
    embeddingModel: profile.embeddingModel ?? '',
  }
}

/**
 * 表单 → 请求体。
 *
 * <p>密钥三态：
 * <ul>
 *   <li>输入框仍为空且没点「清除」→ **不传**该字段，后端保持原值；</li>
 *   <li>填了非空 → 传明文覆盖；</li>
 *   <li>点了「清除」→ 传空串，后端清空并回落到环境变量。</li>
 * </ul>
 */
export function buildProfilePayload(
  form: ModelProfileForm,
  clearApiKey = false,
): {
  name: string
  presetId: string | null
  baseUrl: string | null
  protocol: ModelProtocol
  apiKey?: string
  chatModel: string | null
  temperature: number | null
  embeddingModel: string | null
} {
  const trimmedKey = form.apiKey.trim()
  let apiKey: string | undefined
  if (clearApiKey) {
    apiKey = ''
  } else if (trimmedKey.length > 0) {
    apiKey = trimmedKey
  } else {
    apiKey = undefined
  }

  return {
    name: form.name.trim(),
    presetId: form.presetId,
    baseUrl: blankToNull(form.baseUrl),
    protocol: form.protocol,
    apiKey,
    chatModel: blankToNull(form.chatModel),
    temperature: form.temperature,
    embeddingModel: blankToNull(form.embeddingModel),
  }
}

/**
 * 表单校验。
 *
 * <p>与后端 `ModelProfileController.validate` 同一套规则 —— 前端拦一遍是为了
 * 快速反馈，后端那一遍才是权威。绝不能让前端放过后端会拒的值
 * （那样用户会看到「点了保存什么也没发生」）。
 *
 * @returns 错误提示；通过时返回 null
 */
export function validateProfileForm(form: ModelProfileForm): string | null {
  const name = form.name.trim()
  if (name.length === 0) {
    return '请填写配置名称'
  }
  if (name.length > PROFILE_NAME_MAX) {
    return `配置名称长度不能超过 ${PROFILE_NAME_MAX} 个字符`
  }

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
 * 卡片上的模型摘要。
 *
 * <p>只显示模型名而不显示端点：端点通常很长，塞进卡片会把布局撑乱；
 * 而「哪个模型」是用户区分几张卡片最常用的依据。端点放在卡片的悬浮提示里。
 */
export function modelSummary(profile: ModelProfile): string {
  const chat = (profile.chatModel ?? '').trim()
  return chat.length > 0 ? chat : '未指定模型'
}

/**
 * 卡片上的密钥摘要。
 *
 * <p>说「未设置」而不是显示空串：空串让人以为加载失败，
 * 而真实含义是「这张卡片没填密钥，用的是环境变量」。
 */
export function keySummary(profile: ModelProfile): string {
  return profile.hasApiKey ? profile.apiKeyMasked : '未设置密钥'
}

/**
 * 判断某张卡片是否可删除，返回不可删的原因。
 *
 * <p>与后端删除保护一一对应，但**前端提前拦**：让用户在点删除之前就知道
 * 为什么不能删，比点了之后弹一个错误框好得多。
 *
 * @param profile     目标卡片
 * @param totalCount  配置总数
 * @returns 不可删除的原因；可删除时返回 null
 */
export function deleteBlockedReason(profile: ModelProfile, totalCount: number): string | null {
  if (profile.active) {
    return '当前生效的配置不能删除，请先切换到其他配置'
  }
  if (totalCount <= 1) {
    return '至少需要保留一套配置'
  }
  return null
}

/**
 * 计算拖拽后的新顺序。
 *
 * <p>纯函数，返回新的 id 数组。视图层拿到后直接提交，不做乐观更新 ——
 * 顺序是服务端状态，本地先改再回滚会让列表跳一下。
 *
 * @param ids       当前顺序
 * @param fromIndex 被拖动的项
 * @param toIndex   目标位置
 * @returns 新顺序；索引非法时返回原数组的副本
 */
export function moveItem(ids: string[], fromIndex: number, toIndex: number): string[] {
  const next = [...ids]
  if (fromIndex < 0 || fromIndex >= next.length || toIndex < 0 || toIndex >= next.length) {
    return next
  }
  const [moved] = next.splice(fromIndex, 1)
  next.splice(toIndex, 0, moved)
  return next
}

/**
 * 移动一项到另一项之前（拖拽落点时更常用）。
 *
 * @param ids       当前顺序
 * @param dragId    被拖动的 id
 * @param targetId  目标 id（拖动项会插到它前面）
 */
export function reorderBefore(ids: string[], dragId: string, targetId: string): string[] {
  const from = ids.indexOf(dragId)
  const to = ids.indexOf(targetId)
  if (from < 0 || to < 0 || from === to) {
    return [...ids]
  }
  return moveItem(ids, from, to)
}

/**
 * 「获取模型列表」结果的一句话摘要。
 *
 * <p>成功时**必须报出数量**：只显示「获取成功」会让用户以为拿到了完整列表，
 * 而某些中转站只返回一小部分模型。
 */
export function probeResultSummary(result: ModelListResult): string {
  if (!result.ok) {
    return result.error ?? '获取模型列表失败'
  }
  const count = result.models.length
  const latency = result.latencyMs >= 1000 ? `${(result.latencyMs / 1000).toFixed(1)} 秒` : `${result.latencyMs} 毫秒`
  return `获取到 ${count} 个模型（${latency}）`
}

/**
 * 探测结果该用哪种提示语气。
 *
 * <p>同样是「没拿到列表」，「这端点压根没这个接口」与「你密钥错了」
 * 对用户的意义相反：前者什么都不用做，后者必须改。都用中性灰会让真正的
 * 错误被当成普通提示划过；都用红色又会把正常情况报成故障。
 *
 * `kind` 缺失时（旧后端）按 `ERROR` 处理：宁可多提示一句，也别漏掉真错误。
 */
export function probeAlertType(result: ModelListResult): 'success' | 'info' | 'warning' {
  if (result.ok) {
    return 'success'
  }
  return result.kind === 'UNSUPPORTED' ? 'info' : 'warning'
}

/**
 * 失败提示条下方的补充说明。
 *
 * 只有「端点不支持」这一种才该说「不影响使用」—— 密钥错了说这句是误导。
 */
export function probeResultHint(result: ModelListResult): string | null {
  if (result.ok) {
    return '下方的「对话模型 / 嵌入模型」已可从这些模型里挑选，也可以直接手打。'
  }
  if (result.kind === 'UNSUPPORTED') {
    return '该端点可能不提供模型列表接口，这不影响使用 —— 直接手动填写模型名即可。'
  }
  return '修正上面的问题后可以再试一次，也可以直接手动填写模型名。'
}

/**
 * 判断「获取模型列表」按钮此刻能不能点，返回不能点的原因。
 *
 * <p>与后端 `ModelListProbeService.blockedReason` 同一套判断，
 * 前端提前拦是为了让用户不必点一次才发现「忘了填密钥」。
 *
 * <h3>为什么 Ollama 不要求密钥</h3>
 * 本地 Ollama 默认无鉴权。要求填密钥会让这条最常见的本地用法卡住，
 * 而填错密钥的后果只是探测失败 —— 一个可恢复的错误。
 *
 * @param protocol 协议
 * @param baseUrl  表单里的端点（**不是**生效值：用户点了编辑但没保存时，
 *                 生效值仍是旧的，探测应当针对他眼前的这个端点）
 * @param apiKey   表单里的密钥；编辑态可能为空
 * @param hasStoredKey 这套配置是否已保存过密钥（编辑态可回落到它）
 * @returns 不能探测的原因；可以探测时返回 null
 */
export function probeBlockedReason(
  protocol: ModelProtocol,
  baseUrl: string,
  apiKey: string,
  hasStoredKey: boolean,
): string | null {
  if (baseUrl.trim().length === 0) {
    return '请先填写端点地址'
  }
  if (protocol !== 'OLLAMA' && apiKey.trim().length === 0 && !hasStoredKey) {
    return '请先填写 API Key（或先保存一次配置）'
  }
  return null
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
