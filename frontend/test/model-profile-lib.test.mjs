import { check, eq } from './assert.mjs'

/**
 * 多套模型配置页纯函数。
 *
 * 断言口径集中在三类「写错了不报错、只表现为行为莫名其妙」的地方：
 * ① 密钥三态 —— 弄错会把用户密钥静默清掉，或覆盖成掩码字符串；
 * ② 删除保护 —— 弄错会让用户删掉最后一/生效中的配置，聊天永久不可用；
 * ③ 拖拽换位 —— 弄错会让卡片顺序跳乱，且刷新后回不去。
 */

const {
  buildProfilePayload,
  deleteBlockedReason,
  emptyForm,
  keySummary,
  modelSummary,
  moveItem,
  reorderBefore,
  sourceHint,
  testResultSummary,
  toForm,
  validateProfileForm,
  MODEL_NAME_MAX,
  PROFILE_NAME_MAX,
  SOURCE_LABELS,
  TEMPERATURE_MAX,
} = await import('@/lib/model-profile')

/** 一份合法的生效配置 */
const PROFILE = {
  id: 'p1',
  name: '百炼主力',
  presetId: 'dashscope',
  baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  apiKeyMasked: '****LKmg',
  hasApiKey: true,
  chatModel: 'qwen3.8-flash',
  temperature: 0.7,
  embeddingModel: 'qwen3.7-text-embedding-flash',
  active: true,
  sortOrder: 0,
  revision: 3,
  updatedAt: '2026-10-28T06:00:00Z',
  sources: {
    baseUrl: 'DATABASE',
    apiKey: 'DATABASE',
    chatModel: 'DATABASE',
    embeddingModel: 'ENVIRONMENT',
    temperature: 'DATABASE',
  },
}

// ---------------------------------------------------------------- 表单转换

{
  const form = toForm(PROFILE)
  eq('toForm 带出名称', form.name, '百炼主力')
  eq('toForm 的密钥恒为空：填掩码会把真实密钥覆盖成那七个字符', form.apiKey, '')
  eq('toForm 带出温度', form.temperature, 0.7)
  eq('toForm 带出预设标识', form.presetId, 'dashscope')
}

{
  const form = emptyForm('openai')
  eq('emptyForm 记住预设标识', form.presetId, 'openai')
  eq('emptyForm 密钥为空', form.apiKey, '')
  eq('emptyForm 温度未设置', form.temperature, null)
}

// ---------------------------------------------------------------- 密钥三态

{
  // ① 没碰密钥框（空串）→ 不传该字段，后端保持原值
  const payload = buildProfilePayload({ ...toForm(PROFILE), chatModel: 'qwen-max' })
  eq('密钥输入框留空时不传 apiKey（后端保持原值）', payload.apiKey, undefined)
  eq('其他字段照常提交', payload.chatModel, 'qwen-max')
}

{
  // ② 填了新值 → 覆盖
  const payload = buildProfilePayload({ ...toForm(PROFILE), apiKey: 'sk-new-key' })
  eq('填了密钥就提交明文以覆盖', payload.apiKey, 'sk-new-key')
}

{
  // ③ 显式点「清除」→ 传空串，后端清空并回落环境变量
  const payload = buildProfilePayload({ ...toForm(PROFILE), apiKey: '' }, true)
  eq('显式清除时传空串', payload.apiKey, '')
}

{
  // 空白字符不算「填了密钥」
  const payload = buildProfilePayload({ ...toForm(PROFILE), apiKey: '   ' })
  eq('只有空白的密钥框视为未填', payload.apiKey, undefined)
}

// ---------------------------------------------------------------- 表单校验

{
  eq('名称为空被拒', validateProfileForm({ ...emptyForm(), name: '' }), '请填写配置名称')
  eq('纯空白名称被拒', validateProfileForm({ ...emptyForm(), name: '   ' }), '请填写配置名称')

  const tooLong = validateProfileForm({ ...emptyForm(), name: 'x'.repeat(PROFILE_NAME_MAX + 1) })
  check('名称超长被拒', tooLong !== null && tooLong.includes(String(PROFILE_NAME_MAX)))

  const badUrl = validateProfileForm({ ...emptyForm(), name: 'n', baseUrl: 'ftp://x' })
  check('非 http(s) 端点被拒', badUrl !== null && badUrl.includes('http'))

  const badTemp = validateProfileForm({ ...emptyForm(), name: 'n', temperature: TEMPERATURE_MAX + 0.5 })
  check('温度越界被拒', badTemp !== null && badTemp.includes('温度'))

  const badModel = validateProfileForm({ ...emptyForm(), name: 'n', chatModel: 'm'.repeat(MODEL_NAME_MAX + 1) })
  check('模型名超长被拒', badModel !== null && badModel.includes('不能超过'))

  // 端点留空是合法的（回落到环境变量）
  eq('端点留空合法', validateProfileForm({ ...emptyForm(), name: 'n', baseUrl: '' }), null)
  // 温度 null 合法（沿用服务端默认）
  eq('温度为 null 合法', validateProfileForm({ ...emptyForm(), name: 'n', temperature: null }), null)
  // 温度 0 合法，且不能因为「falsy」被当成未设置
  eq('温度 0 合法', validateProfileForm({ ...emptyForm(), name: 'n', temperature: 0 }), null)
}

// ---------------------------------------------------------------- 删除保护

{
  eq('生效中的配置不可删', deleteBlockedReason({ ...PROFILE, active: true }, 3)?.includes('生效'), true)

  // 唯一一套：即使它不是生效配置也不可删（否则列表空了没入口恢复）
  const only = deleteBlockedReason({ ...PROFILE, active: false }, 1)
  check('最后一套不可删', only !== null && only.includes('至少'))

  eq('非生效且非最后一套可删', deleteBlockedReason({ ...PROFILE, active: false }, 2), null)
}

// ---------------------------------------------------------------- 卡片摘要

{
  eq('模型摘要取模型名', modelSummary(PROFILE), 'qwen3.8-flash')
  eq('空模型名给出说明而不是空白', modelSummary({ ...PROFILE, chatModel: '  ' }), '未指定模型')
  eq('有密钥时显示掩码', keySummary(PROFILE), '****LKmg')
  eq('无密钥时说清楚而不是留空', keySummary({ ...PROFILE, hasApiKey: false }), '未设置密钥')
}

// ---------------------------------------------------------------- 顺序调整

{
  eq('向下移动', moveItem(['a', 'b', 'c'], 0, 2).join(','), 'b,c,a')
  eq('向上移动', moveItem(['a', 'b', 'c'], 2, 0).join(','), 'c,a,b')
  eq('移到原位不变', moveItem(['a', 'b', 'c'], 1, 1).join(','), 'a,b,c')

  // 非法索引返回副本而不是抛异常：拖拽越界不该让整页崩
  const original = ['a', 'b']
  const outOfRange = moveItem(original, 5, 0)
  eq('越界索引返回原顺序', outOfRange.join(','), 'a,b')
  check('越界时返回的是副本', outOfRange !== original)

  eq('reorderBefore 插到目标前', reorderBefore(['a', 'b', 'c'], 'c', 'a').join(','), 'c,a,b')
  eq('未知 id 不改变顺序', reorderBefore(['a', 'b'], 'x', 'a').join(','), 'a,b')
}

// ---------------------------------------------------------------- 来源与提示

{
  eq('数据库来源在多配置语境下叫「本配置」', SOURCE_LABELS.DATABASE, '本配置')
  check('来源说明都提到卡片', sourceHint('DATABASE').includes('卡片'))
  check('环境变量说明提到 .env', sourceHint('ENVIRONMENT').includes('.env'))
  check('默认来源说明提到内置', sourceHint('DEFAULT').includes('内置'))
}

// ---------------------------------------------------------------- 测试结果摘要

{
  const ok = testResultSummary({ ok: true, latencyMs: 320, replyPreview: '正常', error: null })
  check('成功摘要含毫秒', ok.includes('320 毫秒') && ok.includes('正常'))

  const slow = testResultSummary({ ok: true, latencyMs: 1500, replyPreview: null, error: null })
  check('超过一秒改用秒', slow.includes('1.5 秒'))

  const failed = testResultSummary({ ok: false, latencyMs: 0, replyPreview: null, error: '认证失败' })
  eq('失败时原样给出后端的原因', failed, '认证失败')

  const noReason = testResultSummary({ ok: false, latencyMs: 0, replyPreview: null, error: null })
  eq('没有原因时也有可读文案', noReason, '测试失败')
}
