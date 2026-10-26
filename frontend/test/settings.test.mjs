import { check, eq } from './assert.mjs'

/**
 * 设置偏好纯函数（阶段 19，tasks T057 / contracts C-03）。
 */

const { SETTINGS_DEFAULTS, SETTINGS_KEYS, clampTopK, clampLead, mergeSettings, conversationKnowledgeKey } =
  await import('@/lib/settings')

// ---- 默认值 ----

eq('默认增强关', SETTINGS_DEFAULTS.knowledgeDefaultOn, false)
eq('默认 topK 4', SETTINGS_DEFAULTS.knowledgeTopK, 4)
eq('默认 Enter 发送', SETTINGS_DEFAULTS.enterSend, true)
eq('默认提前 15 分钟', SETTINGS_DEFAULTS.reminderLead, 15)

// ---- clampTopK ----

eq('topK 合法值原样', clampTopK(8), 8)
eq('topK 下限 1', clampTopK(0), 1)
eq('topK 负数归 1', clampTopK(-5), 1)
eq('topK 上限 20', clampTopK(99), 20)
eq('topK 小数取整', clampTopK(4.6), 5)
eq('topK 非数字回落默认', clampTopK('abc'), 4)
eq('topK NaN 回落默认', clampTopK(NaN), 4)
eq('topK null 回落默认', clampTopK(null), 4)
eq('topK Infinity 回落默认', clampTopK(Infinity), 4)

// ---- clampLead ----

eq('提前量合法值原样', clampLead(30), 30)
eq('提前量下限 0', clampLead(-1), 0)
eq('提前量上限 10080', clampLead(99999), 10080)
eq('提前量非数字回落默认', clampLead('x'), 15)

// ---- mergeSettings：脏值回落 ----

{
  const merged = mergeSettings({})
  eq('空对象 → 全默认', merged.knowledgeDefaultOn, false)
  eq('空对象 → topK 默认', merged.knowledgeTopK, 4)
}

{
  const merged = mergeSettings({
    knowledgeDefaultOn: true,
    knowledgeTopK: 12,
    enterSend: false,
    reminderLead: 45,
  })
  eq('合法值全收录（增强）', merged.knowledgeDefaultOn, true)
  eq('合法值全收录（topK）', merged.knowledgeTopK, 12)
  eq('合法值全收录（enterSend）', merged.enterSend, false)
  eq('合法值全收录（提前量）', merged.reminderLead, 45)
}

{
  const merged = mergeSettings({
    knowledgeDefaultOn: 'yes', // 脏：应为布尔
    knowledgeTopK: 999, // 脏：越界
    enterSend: 1, // 脏：应为布尔
    reminderLead: 'abc', // 脏：应为数字
  })
  eq('脏布尔回落默认', merged.knowledgeDefaultOn, false)
  eq('越界 topK 收敛', merged.knowledgeTopK, 20)
  eq('脏布尔回落默认（enterSend）', merged.enterSend, true)
  eq('脏数字回落默认（提前量）', merged.reminderLead, 15)
}

// ---- conversationKnowledgeKey ----

eq(
  '合法会话 id 构建键',
  conversationKnowledgeKey('cae6eee3-b88e-41a5-ba36-dac348e31a9b'),
  'os.chat.knowledge.cae6eee3-b88e-41a5-ba36-dac348e31a9b',
)
eq('null → null', conversationKnowledgeKey(null), null)
eq('空串 → null', conversationKnowledgeKey(''), null)
eq('非法字符（下划线）→ null', conversationKnowledgeKey('bad_id'), null)
eq('超长 id → null', conversationKnowledgeKey('a'.repeat(65)), null)

// ---- 键名不与既有偏好撞车 ----

check('设置键互不相同', new Set(Object.values(SETTINGS_KEYS)).size === 4)
check('会话键前缀不与设置键相同', !Object.values(SETTINGS_KEYS).some((key) => key.startsWith('os.chat.knowledge.')))
