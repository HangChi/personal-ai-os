/**
 * 设置偏好（阶段 19，contracts C-03）。
 *
 * 纯函数部分：默认值、脏值收敛、键名构建。store（stores/settings.ts）只负责
 * 读写 localStorage 与响应式，判定逻辑全在这里——可被 .mjs 单测覆盖。
 *
 * 命名空间约定：`os.settings.*` 是设置页可管项；`os.theme` / `os.nav.collapsed` /
 * `os.tasks.view` / `os.knowledge.tab` / `os.chat.knowledge.<会话>` 是各页面
 * 自己的偏好（设置页可读写前两者，但键不搬家——搬家意味着两套键迁移）。
 */

export interface SettingsState {
  /** 新会话的知识库增强默认开关 */
  knowledgeDefaultOn: boolean
  /** 默认检索条数（1~20） */
  knowledgeTopK: number
  /** Enter 直接发送（false 时 Ctrl/Cmd+Enter 发送） */
  enterSend: boolean
  /** 日程新建表单的默认提前提醒分钟数 */
  reminderLead: number
  /** 模型思考程度：off=不思考（默认），standard/deep 对应 DashScope 的思考预算 */
  thinkingLevel: ThinkingLevel
}

/** 思考程度取值（与后端 chat.dto.ThinkingLevel 对齐） */
export type ThinkingLevel = 'off' | 'standard' | 'deep'

/** 思考程度的中文标签（UI 与测试共用一处，避免两处文案漂移） */
export const THINKING_LABELS: Record<ThinkingLevel, string> = {
  off: '关闭',
  standard: '标准',
  deep: '深度',
}

/** 思考档位的说明：悬浮提示用，说明「深」到底深在哪、代价是什么 */
export const THINKING_HINTS: Record<ThinkingLevel, string> = {
  off: '不思考：最快、最省，日常简单问答够用',
  standard: '标准思考：适合一般推理，首字节略慢',
  deep: '深度思考：复杂排错与方案权衡，首字节明显变慢',
}

export const SETTINGS_DEFAULTS: SettingsState = {
  knowledgeDefaultOn: false,
  knowledgeTopK: 4,
  enterSend: true,
  reminderLead: 15,
  thinkingLevel: 'off',
}

/** 检索条数边界（与后端 StreamChatRequest.MAX_TOP_K 对齐） */
export const TOP_K_MIN = 1
export const TOP_K_MAX = 20
/** 提前提醒边界（与日程表单的 el-input-number 一致） */
export const LEAD_MIN = 0
export const LEAD_MAX = 10080

/** localStorage 键名（单一来源，store 与重置逻辑都用它） */
export const SETTINGS_KEYS = {
  knowledgeDefaultOn: 'os.settings.knowledgeDefaultOn',
  knowledgeTopK: 'os.settings.knowledgeTopK',
  enterSend: 'os.settings.enterSend',
  reminderLead: 'os.settings.reminderLead',
  thinkingLevel: 'os.settings.thinkingLevel',
} as const

/** 会话级增强开关的键前缀（C-03） */
export const CONVERSATION_KNOWLEDGE_PREFIX = 'os.chat.knowledge.'

function clampInt(value: unknown, min: number, max: number, fallback: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return fallback
  }
  return Math.min(Math.max(Math.round(value), min), max)
}

function clampBoolean(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback
}

/** topK 收敛到 [1, 20]；非数字 / NaN / 越界都回落默认值 */
export function clampTopK(value: unknown): number {
  return clampInt(value, TOP_K_MIN, TOP_K_MAX, SETTINGS_DEFAULTS.knowledgeTopK)
}

/**
 * 思考程度收敛：只接受三个合法值，其余（含脏值、大小写变体）一律回落默认。
 *
 * <p>刻意不做大小写归一：合法值由前端自己产生，出现 'OFF' 这类值说明存储被手改过，
 * 此时按默认处理比「猜测用户想开思考」更安全。
 */
export function clampThinkingLevel(value: unknown): ThinkingLevel {
  return value === 'standard' || value === 'deep' ? value : SETTINGS_DEFAULTS.thinkingLevel
}

/** 提前提醒分钟数收敛到 [0, 10080] */
export function clampLead(value: unknown): number {
  return clampInt(value, LEAD_MIN, LEAD_MAX, SETTINGS_DEFAULTS.reminderLead)
}

/**
 * 把 localStorage 读到的原始值合并成合法设置。
 *
 * 脏值（手改 / 旧版本残留）一律回落默认——设置项坏掉不该让页面白屏。
 */
export function mergeSettings(stored: Partial<Record<keyof SettingsState, unknown>>): SettingsState {
  return {
    knowledgeDefaultOn: clampBoolean(stored.knowledgeDefaultOn, SETTINGS_DEFAULTS.knowledgeDefaultOn),
    knowledgeTopK: clampTopK(stored.knowledgeTopK),
    enterSend: clampBoolean(stored.enterSend, SETTINGS_DEFAULTS.enterSend),
    reminderLead: clampLead(stored.reminderLead),
    thinkingLevel: clampThinkingLevel(stored.thinkingLevel),
  }
}

/**
 * 会话级增强开关的存储键。
 *
 * @param conversationId 会话标识（服务端 UUID）
 * @returns 键名；conversationId 为空或含非法字符时返回 null（调用方应回落默认值）
 */
export function conversationKnowledgeKey(conversationId: string | null): string | null {
  if (!conversationId || !/^[A-Za-z0-9-]{1,64}$/.test(conversationId)) {
    return null
  }
  return `${CONVERSATION_KNOWLEDGE_PREFIX}${conversationId}`
}

/** 全部本地偏好键的前缀（「重置偏好」按前缀清除） */
export const PREFERENCE_KEY_PREFIX = 'os.'
