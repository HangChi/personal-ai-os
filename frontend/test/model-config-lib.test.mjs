import { check, eq } from './assert.mjs'

/**
 * 模型配置页纯函数（设置页「大模型 API」区）。
 *
 * 断言口径集中在两类最易出错的地方：
 * ① 密钥三态（不改动 / 覆盖 / 清空）—— 弄错会把用户密钥静默清掉或覆盖成掩码字符串；
 * ② 来源标注与脏值判定 —— 弄错会让用户看不出「我改的为什么没生效」。
 */

const {
  apiKeyDisplay,
  buildSavePayload,
  isDirty,
  sourceHint,
  testResultSummary,
  toForm,
  validateForm,
  MODEL_NAME_MAX,
  SOURCE_LABELS,
  TEMPERATURE_MAX,
} = await import('@/lib/model-config')

/** 一份合法的生效配置 */
const CONFIG = {
  baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  apiKeyMasked: '****LKmg',
  hasApiKey: true,
  chatModel: 'qwen3.8-flash',
  temperature: 0.7,
  embeddingModel: 'qwen3.7-text-embedding-flash',
  revision: 2,
  updatedAt: '2026-10-28T03:45:21Z',
  sources: {
    baseUrl: 'DATABASE',
    apiKey: 'DATABASE',
    chatModel: 'DATABASE',
    embeddingModel: 'DATABASE',
    temperature: 'DATABASE',
  },
  hotReloadable: true,
  embeddingHotReloadable: false,
}

// ---- toForm：密钥永不回填 ----

const form = toForm(CONFIG)
eq('表单回填 baseUrl', form.baseUrl, CONFIG.baseUrl)
eq('表单回填 chatModel', form.chatModel, CONFIG.chatModel)
eq('表单回填 temperature', form.temperature, 0.7)
eq('密钥框永远为空（不回填掩码）', form.apiKey, '')
check('表单里不含掩码字符串', !JSON.stringify(form).includes('****'))

// ---- buildSavePayload：密钥三态 ----

const unchanged = buildSavePayload(toForm(CONFIG))
eq('未碰密钥时 apiKey 为 undefined（不改动）', unchanged.apiKey, undefined)
check('未碰密钥时请求体里该字段不可枚举地存在', 'apiKey' in unchanged)

const replaced = buildSavePayload({ ...toForm(CONFIG), apiKey: '  sk-new-key  ' })
eq('填了密钥则去掉首尾空白后提交', replaced.apiKey, 'sk-new-key')

const cleared = buildSavePayload(toForm(CONFIG), true)
eq('显式清空时提交空串（回落到环境变量）', cleared.apiKey, '')

// 关键回归：如果哪天有人把掩码值塞进表单，这里必须失败
const maskedSubmitted = buildSavePayload({ ...toForm(CONFIG), apiKey: '****LKmg' })
eq('把掩码当密钥提交会被原样送出（因此调用方绝不能回填）', maskedSubmitted.apiKey, '****LKmg')

// 空串字段归一为 null（= 回落到环境变量）
const blanked = buildSavePayload({
  baseUrl: '   ',
  apiKey: '',
  chatModel: '',
  temperature: null,
  embeddingModel: '  ',
})
eq('空白 baseUrl 归一为 null', blanked.baseUrl, null)
eq('空白 chatModel 归一为 null', blanked.chatModel, null)
eq('空白 embeddingModel 归一为 null', blanked.embeddingModel, null)

// ---- validateForm ----

eq('默认表单通过校验', validateForm(toForm(CONFIG)), null)
eq(
  '端点地址协议必须是 http/https',
  validateForm({ ...toForm(CONFIG), baseUrl: 'file:///etc/passwd' }),
  '端点地址必须以 http:// 或 https:// 开头',
)
eq('空端点放行（回落到环境变量）', validateForm({ ...toForm(CONFIG), baseUrl: '   ' }), null)
eq(
  '温度超上限被拒',
  validateForm({ ...toForm(CONFIG), temperature: TEMPERATURE_MAX + 0.5 }),
  `温度必须在 0 到 ${TEMPERATURE_MAX} 之间`,
)
eq('温度超下限被拒', validateForm({ ...toForm(CONFIG), temperature: -0.1 }), `温度必须在 0 到 ${TEMPERATURE_MAX} 之间`)
eq('温度 NaN 被拒', validateForm({ ...toForm(CONFIG), temperature: NaN }), '温度必须是一个数字')
eq('温度为 null 放行（沿用服务端默认）', validateForm({ ...toForm(CONFIG), temperature: null }), null)
eq('温度 0 是合法值', validateForm({ ...toForm(CONFIG), temperature: 0 }), null)
eq(
  '超长模型名被拒',
  validateForm({ ...toForm(CONFIG), chatModel: 'x'.repeat(MODEL_NAME_MAX + 1) }),
  `对话模型名长度不能超过 ${MODEL_NAME_MAX} 个字符`,
)

// ---- isDirty ----

eq('原样表单不算脏', isDirty(toForm(CONFIG), CONFIG), false)
eq('改了模型名算脏', isDirty({ ...toForm(CONFIG), chatModel: 'x' }, CONFIG), true)
eq('填了密钥算脏', isDirty({ ...toForm(CONFIG), apiKey: 'sk-x' }, CONFIG), true)
eq('配置未加载时不算脏', isDirty(toForm(CONFIG), null), false)
eq(
  '温度 null → 0 算脏（语义不同）',
  isDirty({ ...toForm(CONFIG), temperature: 0 }, { ...CONFIG, temperature: null }),
  true,
)
eq('温度 0 → 0 不算脏', isDirty({ ...toForm(CONFIG), temperature: 0 }, { ...CONFIG, temperature: 0 }), false)

// ---- apiKeyDisplay ----

eq('有密钥时显示掩码', apiKeyDisplay(CONFIG), '****LKmg')
eq('无密钥时显示「未设置」', apiKeyDisplay({ ...CONFIG, hasApiKey: false, apiKeyMasked: '' }), '未设置')
eq('未加载时显示占位', apiKeyDisplay(null), '—')

// ---- 来源标注 ----

eq('设置页标签', SOURCE_LABELS.DATABASE, '设置页')
eq('环境变量标签', SOURCE_LABELS.ENVIRONMENT, '环境变量')
check('环境变量说明提到「保存后覆盖」', sourceHint('ENVIRONMENT').includes('保存后'))
check('默认值说明提到「内置」', sourceHint('DEFAULT').includes('内置'))
check('设置页说明提到「优先」', sourceHint('DATABASE').includes('优先'))

// ---- 测试结果摘要 ----

check(
  '成功且带回复时含回复内容',
  testResultSummary({ ok: true, latencyMs: 1995, replyPreview: '正常', error: null }).includes('正常'),
)
check(
  '耗时超过 1 秒用秒显示',
  testResultSummary({ ok: true, latencyMs: 1995, replyPreview: null, error: null }).includes('2.0 秒'),
)
check(
  '耗时不超 1 秒用毫秒显示',
  testResultSummary({ ok: true, latencyMs: 468, replyPreview: null, error: null }).includes('468 毫秒'),
)
eq(
  '失败时直接显示原因',
  testResultSummary({ ok: false, latencyMs: 468, replyPreview: null, error: '认证失败（401）' }),
  '认证失败（401）',
)
eq(
  '失败但无原因时给兜底文案',
  testResultSummary({ ok: false, latencyMs: 0, replyPreview: null, error: null }),
  '测试失败',
)
