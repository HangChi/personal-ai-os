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
  defaultChatModel,
  deleteBlockedReason,
  emptyForm,
  hasChatModel,
  keySummary,
  modelSummary,
  moveItem,
  moveModel,
  normalizeModelList,
  probeAlertType,
  probeBlockedReason,
  probeResultHint,
  probeResultSummary,
  reorderBefore,
  sessionModelOptions,
  classifyModel,
  modelCandidatesFor,
  modelOptionsFor,
  shouldShowSource,
  sourceHint,
  testResultSummary,
  toForm,
  validateProfileForm,
  MODEL_LIST_MAX,
  MODEL_NAME_MAX,
  PROFILE_NAME_MAX,
  PROTOCOL_HINTS,
  PROTOCOL_LABELS,
  PROTOCOL_OPTIONS,
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
  // V23：清单第一项恒等于上面的单值列
  chatModels: ['qwen3.8-flash'],
  embeddingModels: ['qwen3.7-text-embedding-flash'],
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
  const payload = buildProfilePayload({ ...toForm(PROFILE), chatModels: ['qwen-max'] })
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

  const badModel = validateProfileForm({ ...emptyForm(), name: 'n', chatModels: ['m'.repeat(MODEL_NAME_MAX + 1)] })
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
  eq('空模型名给出说明而不是空白', modelSummary({ ...PROFILE, chatModel: '  ', chatModels: [] }), '未指定模型')

  // 关键：有多个模型时必须让用户看出来，否则会以为「只配了一个」而反复编辑核对
  eq(
    '多模型时报出总数并用第一项作主名',
    modelSummary({ ...PROFILE, chatModels: ['qwen3.8-flash', 'qwen-max', 'qwen-long'] }),
    'qwen3.8-flash 等 3 个',
  )
  eq('老后端只返回单值时仍能显示', modelSummary({ ...PROFILE, chatModels: undefined }), 'qwen3.8-flash')

  eq('有密钥时显示掩码', keySummary(PROFILE), '****LKmg')
  eq('无密钥时说清楚而不是留空', keySummary({ ...PROFILE, hasApiKey: false }), '未设置密钥')
}

// ---------------------------------------------------------------- 模型清单

{
  // toForm：清单优先，缺失时用单值列兜底（老后端 / V22 数据）
  eq('toForm 带出对话清单', toForm(PROFILE).chatModels.join(','), 'qwen3.8-flash')
  eq(
    'toForm 在清单缺失时用单值列兜底：否则编辑框会显示「没有模型」而实际有',
    toForm({ ...PROFILE, chatModels: undefined }).chatModels.join(','),
    'qwen3.8-flash',
  )
  eq(
    'toForm 在清单为空数组时也用单值列兜底',
    toForm({ ...PROFILE, chatModels: [] }).chatModels.join(','),
    'qwen3.8-flash',
  )
  eq('toForm 带出嵌入清单', toForm(PROFILE).embeddingModels.join(','), 'qwen3.7-text-embedding-flash')
  eq(
    'toForm 在嵌入模型也没有时给空清单',
    toForm({ ...PROFILE, embeddingModels: [], embeddingModel: '' }).embeddingModels.length,
    0,
  )

  const form = emptyForm()
  eq('emptyForm 的对话清单为空', form.chatModels.length, 0)
  eq('emptyForm 的嵌入清单为空', form.embeddingModels.length, 0)
}

{
  // normalizeModelList：裁剪、去空、去重（保序）
  eq('规范化裁剪空白', normalizeModelList([' a ', 'b']).join(','), 'a,b')
  eq('规范化丢弃空串', normalizeModelList(['a', '', '  ']).join(','), 'a')
  eq('规范化按首次出现去重并保序', normalizeModelList(['b', 'a', 'b']).join(','), 'b,a')
  eq('重复点「加入」不会让后端撞唯一约束', normalizeModelList(['x', 'x', 'x']).length, 1)
  eq('全空时返回空数组', normalizeModelList([]).length, 0)
}

{
  // moveModel：设默认 = 挪到第一位
  eq('设为默认（移到首位）', moveModel(['a', 'b', 'c'], 2, 0).join(','), 'c,a,b')
  eq('移到原位不变', moveModel(['a', 'b'], 1, 1).join(','), 'a,b')
  const source = ['a', 'b']
  const moved = moveModel(source, 9, 0)
  eq('越界索引返回原顺序', moved.join(','), 'a,b')
  check('越界时返回副本而不是原数组', moved !== source)
  eq('原数组不被修改', source.join(','), 'a,b')
}

{
  // buildProfilePayload：清单第一项必须与单值字段一致
  const payload = buildProfilePayload({ ...emptyForm(), name: 'n', chatModels: ['qwen-max', 'qwen-plus'] })
  eq('payload 提交完整清单', payload.chatModels.join(','), 'qwen-max,qwen-plus')
  eq('单值字段取清单第一项（两处存同一事实，不许不一致）', payload.chatModel, 'qwen-max')

  const empty = buildProfilePayload({ ...emptyForm(), name: 'n' })
  eq('清单为空时单值字段传 null', empty.chatModel, null)
  eq('清单为空时提交空数组而不是 undefined', empty.chatModels.length, 0)

  // 清单里的空白与重复在提交前就被清掉，避免后端 500
  const dirty = buildProfilePayload({ ...emptyForm(), name: 'n', chatModels: [' qwen-max ', 'qwen-max', ''] })
  eq('提交前清掉重复与空白', dirty.chatModels.join(','), 'qwen-max')

  // 删光清单后单值字段必须一起变 null，否则库里会留着一个已不属于清单的旧模型
  const cleared = buildProfilePayload({ ...toForm(PROFILE), chatModels: [] })
  eq('清单删光后单值字段跟着清空', cleared.chatModel, null)
}

{
  // 校验：条数上限与嵌入清单同样受管
  const tooMany = validateProfileForm({
    ...emptyForm(),
    name: 'n',
    chatModels: Array.from({ length: MODEL_LIST_MAX + 1 }, (_, i) => `m${i}`),
  })
  check('对话模型超过上限被拒', tooMany !== null && tooMany.includes('对话模型'))

  const tooManyEmbedding = validateProfileForm({
    ...emptyForm(),
    name: 'n',
    embeddingModels: Array.from({ length: MODEL_LIST_MAX + 1 }, (_, i) => `m${i}`),
  })
  check('嵌入模型超过上限被拒', tooManyEmbedding !== null && tooManyEmbedding.includes('嵌入模型'))

  const longEmbedding = validateProfileForm({
    ...emptyForm(),
    name: 'n',
    embeddingModels: ['e'.repeat(MODEL_NAME_MAX + 1)],
  })
  check('嵌入模型名超长被拒', longEmbedding !== null && longEmbedding.includes('不能超过'))

  // 清单为空是合法的：留空表示沿用环境变量
  eq('空清单合法', validateProfileForm({ ...emptyForm(), name: 'n' }), null)
}

// ---------------------------------------------------------------- 会话级模型选择

{
  // 候选来自生效配置的清单
  eq('候选即生效配置的清单', sessionModelOptions(['qwen-max', 'qwen-plus'], null).join(','), 'qwen-max,qwen-plus')
  eq('会话未选模型时不会多出空项', sessionModelOptions(['qwen-max'], null).length, 1)

  // 关键：会话记着一个已从配置里删掉的模型名 → 必须补进选项，
  // 否则 el-select 无从回显，用户也选不到「取消」
  eq('已从配置删掉的会话模型仍出现在候选里', sessionModelOptions(['qwen-max'], 'gpt-4o').join(','), 'gpt-4o,qwen-max')
  eq('已在清单里的会话模型不重复添加', sessionModelOptions(['qwen-max', 'x'], 'x').join(','), 'qwen-max,x')

  eq('清单为空但会话有值时只剩那一项', sessionModelOptions([], 'gpt-4o').join(','), 'gpt-4o')
  eq('清单为 null 时不炸', sessionModelOptions(null, null).length, 0)
  eq('会话值为空白视为未选', sessionModelOptions(['a'], '  ').join(','), 'a')
}

{
  // 「跟随配置」的说明文案取默认模型名
  eq('默认模型取清单第一项', defaultChatModel({ ...PROFILE, chatModels: ['a', 'b'] }), 'a')
  eq('清单缺失时用单值列兜底', defaultChatModel({ ...PROFILE, chatModels: [] }), 'qwen3.8-flash')
  eq('没有生效配置时给空串（不是 null，便于直接拼文案）', defaultChatModel(null), '')
}

{
  // hasChatModel 与 modelSummary 必须同口径，否则会出现「文字不灰却写着未指定模型」
  eq('有清单时算有模型', hasChatModel(PROFILE), true)
  eq('只配了单值也算有模型', hasChatModel({ ...PROFILE, chatModels: [] }), true)
  eq('两处都空才算没有', hasChatModel({ ...PROFILE, chatModel: '', chatModels: [] }), false)
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

  // 标签上的来源徽章只在「不是本配置」时才显示。
  // 全显示的话一次弹窗里会出现 6 个重复的「本配置」，把真正需要注意的
  // 「环境变量」「内置默认」两类提示淹没掉（实测正是如此）。
  eq('本配置不挂徽章（默认预期，说了等于没说）', shouldShowSource('DATABASE'), false)
  eq('环境变量要挂徽章（用户没意识到的信息）', shouldShowSource('ENVIRONMENT'), true)
  eq('内置默认要挂徽章', shouldShowSource('DEFAULT'), true)
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

// ---------------------------------------------------------------- 协议

{
  eq('默认协议是 OpenAI 兼容：绝大多数端点都属于这一类', emptyForm().protocol, 'OPENAI')
  eq('toForm 带出协议', toForm({ ...PROFILE, protocol: 'ANTHROPIC' }).protocol, 'ANTHROPIC')

  // 老数据没有 protocol 字段时不能变成 undefined 而让下拉框空白
  eq('toForm 在 protocol 缺失时回落 OpenAI', toForm({ ...PROFILE, protocol: undefined }).protocol, 'OPENAI')

  const payload = buildProfilePayload({ ...toForm(PROFILE), protocol: 'OLLAMA' })
  eq('payload 带上协议', payload.protocol, 'OLLAMA')
  eq('协议不影响密钥三态', payload.apiKey, undefined)

  eq('三个协议都有中文标签', Object.keys(PROTOCOL_LABELS).length, 3)
  eq('OpenAI 兼容的标签写清楚是「兼容」', PROTOCOL_LABELS.OPENAI, 'OpenAI 兼容')
  check('Anthropic 说明提到 x-api-key（用 Bearer 会 401）', PROTOCOL_HINTS.ANTHROPIC.includes('x-api-key'))
  check('Ollama 说明提到 /api/tags', PROTOCOL_HINTS.OLLAMA.includes('/api/tags'))
  eq('协议下拉有三项且顺序为 OpenAI 优先', PROTOCOL_OPTIONS.map((o) => o.value).join(','), 'OPENAI,ANTHROPIC,OLLAMA')
}

// ---------------------------------------------------------------- 探测前置条件

{
  eq('端点为空时不可探测', probeBlockedReason('OPENAI', '  ', 'sk-x', false), '请先填写端点地址')

  // 非 Ollama 且无密钥、也没有已保存的密钥 → 拦住
  const noKey = probeBlockedReason('OPENAI', 'https://x.example.com', '', false)
  check('缺密钥时提示补密钥或先保存', noKey !== null && noKey.includes('API Key'))

  // 编辑态：密钥框空着，但这套配置已保存过密钥 → 允许（后端回落到已存密钥）
  eq('编辑态有已存密钥时允许探测', probeBlockedReason('OPENAI', 'https://x.example.com', '', true), null)

  // Ollama 本地不需要密钥
  eq('Ollama 不需要密钥', probeBlockedReason('OLLAMA', 'http://localhost:11434', '', false), null)

  eq('条件齐备时放行', probeBlockedReason('OPENAI', 'https://x.example.com', 'sk-x', false), null)
}

// ---------------------------------------------------------------- 探测结果摘要

{
  const ok = probeResultSummary({ ok: true, models: ['a', 'b', 'c'], latencyMs: 210, error: null, kind: null })
  check('成功摘要报出数量', ok.includes('3 个模型'))
  check('成功摘要含耗时', ok.includes('210 毫秒'))

  const slow = probeResultSummary({ ok: true, models: ['a'], latencyMs: 2400, error: null, kind: null })
  check('超过一秒改用秒', slow.includes('2.4 秒'))

  const failed = probeResultSummary({
    ok: false,
    models: [],
    latencyMs: 0,
    error: '接口不存在（404）',
    kind: 'UNSUPPORTED',
  })
  eq('失败时原样给出后端原因', failed, '接口不存在（404）')

  const noReason = probeResultSummary({ ok: false, models: [], latencyMs: 0, error: null, kind: 'ERROR' })
  eq('没有原因时也有可读文案', noReason, '获取模型列表失败')
}

// ---------------------------------------------------------------- 探测结果语气

{
  // 成功
  eq('成功用 success', probeAlertType({ ok: true, models: ['a'], latencyMs: 1, error: null, kind: null }), 'success')

  // 端点不支持列表 —— 正常情况，中性提示
  eq(
    '端点不支持时用 info 而非警示色',
    probeAlertType({ ok: false, models: [], latencyMs: 0, error: '404', kind: 'UNSUPPORTED' }),
    'info',
  )

  // 真错误 —— 必须警示
  eq(
    '认证失败等真错误用 warning',
    probeAlertType({ ok: false, models: [], latencyMs: 0, error: '401', kind: 'ERROR' }),
    'warning',
  )

  // kind 缺失（旧后端）按错误处理：宁可多提示一句，也别漏掉真错误
  eq(
    'kind 缺失时保守按真错误处理',
    probeAlertType({ ok: false, models: [], latencyMs: 0, error: 'x', kind: null }),
    'warning',
  )
}

// ---------------------------------------------------------------- 探测结果补充说明

{
  const okHint = probeResultHint({ ok: true, models: ['a'], latencyMs: 1, error: null, kind: null })
  check('成功说明指向下拉挑选', okHint.includes('挑选'))

  const unsupportedHint = probeResultHint({
    ok: false,
    models: [],
    latencyMs: 0,
    error: '404',
    kind: 'UNSUPPORTED',
  })
  check('端点不支持时明确说不影响使用', unsupportedHint.includes('不影响使用'))
  check('端点不支持时指引手动填写', unsupportedHint.includes('手动填写'))

  // 关键：密钥错了绝不能说不影响使用 —— 那是误导
  const errorHint = probeResultHint({ ok: false, models: [], latencyMs: 0, error: '401', kind: 'ERROR' })
  check('真错误不出现「不影响使用」', !errorHint.includes('不影响使用'))
  check('真错误提示可重试或手填', errorHint.includes('再试') || errorHint.includes('手动填写'))
}

// ---------------------------------------------------------------- 模型用途分类

/*
 * 分类的验收口径：**不能把对话模型判成嵌入模型**。
 * 反过来（漏判某个嵌入模型）只是它不出现在下拉里，用户还能手打；
 * 而误判会让用户配出一套必然报错的配置，且完全看不出原因。
 *
 * 样本全部取自百炼端点实测返回的 183 个真实模型名（不是编的）。
 */
{
  // 嵌入：应该被识别出来
  eq('text-embedding 判为嵌入', classifyModel('qwen3.7-text-embedding'), 'EMBEDDING')
  eq('带 -flash 后缀的嵌入模型仍识别', classifyModel('qwen3.7-text-embedding-flash'), 'EMBEDDING')
  eq('BGE 系列判为嵌入', classifyModel('bge-large-zh-v1.5'), 'EMBEDDING')
  eq('text2vec 判为嵌入', classifyModel('text2vec-large-chinese'), 'EMBEDDING')
  eq('gte 判为嵌入', classifyModel('gte-large'), 'EMBEDDING')
  eq('大小写不敏感', classifyModel('Qwen3.7-Text-Embedding'), 'EMBEDDING')

  // 对话模型：一个都不能被误判成嵌入
  for (const name of [
    'qwen-max',
    'qwen3.8-flash',
    'deepseek-v4.1-flash-1017',
    'glm-5.3',
    'kimi-k3',
    'MiniMax-M3',
    'qwen-long',
    'qwen-coder-plus-1106',
    'qwen-vl-ocr',
    'qwen3-vl-plus',
    'qwen-deep-research-2025-12-15',
  ]) {
    eq(`对话模型「${name}」不能判成嵌入`, classifyModel(name), 'CHAT')
  }

  // 其它用途：不该出现在对话下拉里，但也不该被算成嵌入
  eq('语音模型判为 AUDIO', classifyModel('qwen3-tts-flash'), 'AUDIO')
  eq('ASR 判为 AUDIO', classifyModel('fun-asr-flash-2026-06-15'), 'AUDIO')
  eq('图像模型判为 IMAGE', classifyModel('qwen-image-3.0-pro'), 'IMAGE')
  eq('z-image 判为 IMAGE', classifyModel('z-image-turbo'), 'IMAGE')
  eq('重排模型判为 RERANK', classifyModel('qwen3.7-text-rerank'), 'RERANK')

  /*
   * omni 是全模态，**能当对话模型**（这一组是回归用例）。
   *
   * 修正前的规则用 `realtime` 关键词一刀切，把 6 个 omni 变体全划成了 AUDIO，
   * 于是它们从对话下拉里消失 —— 用户看到的是「183 个模型里只有 145 个对话模型」，
   * 而实际应为 151 个。官方文档：输入 Text/Image/Video/Audio，输出 Text/Audio。
   */
  eq('omni 实时模型能当对话模型', classifyModel('qwen3.5-omni-plus-realtime'), 'CHAT')
  eq('omni 非 realtime 变体判为对话', classifyModel('qwen3.5-omni-plus'), 'CHAT')
  eq('omni-flash 判为对话', classifyModel('qwen3-omni-flash'), 'CHAT')
  eq('omni 全模态带视频后缀仍判为对话', classifyModel('qwen3-omni-video-plus'), 'CHAT')
  // 例外：omni 系列里明确只做语音合成/识别的，仍归 AUDIO
  eq('omni-tts 只做语音合成，判为 AUDIO', classifyModel('qwen3-omni-tts'), 'AUDIO')
  eq('omni-asr 只做语音识别，判为 AUDIO', classifyModel('qwen3-omni-asr'), 'AUDIO')
  // 非 omni 的 realtime 仍是语音（没有全模态能力声明就不要放宽）
  eq('普通 realtime 模型判为 AUDIO', classifyModel('gpt-4o-realtime-preview'), 'AUDIO')

  // 空值兜底：不抛异常，且归到安全侧
  eq('空串归为 CHAT', classifyModel(''), 'CHAT')
  eq('null 归为 CHAT 不抛异常', classifyModel(null), 'CHAT')
}

// ---------------------------------------------------------------- 按用途取候选

{
  const probed = [
    'qwen3.8-flash',
    'qwen-max',
    'qwen3.7-text-embedding',
    'qwen3.7-text-embedding-flash',
    'qwen3-tts-flash',
    'qwen-image-3.0',
    'qwen3.7-text-rerank',
  ]

  // 核心：嵌入下拉里绝不能出现对话/语音/图像模型
  eq(
    '嵌入下拉只留嵌入模型',
    modelOptionsFor(probed, [], 'EMBEDDING').join(','),
    'qwen3.7-text-embedding,qwen3.7-text-embedding-flash',
  )
  eq('对话下拉不含嵌入模型', modelOptionsFor(probed, [], 'CHAT').includes('qwen3.7-text-embedding'), false)
  eq('对话下拉不含语音模型', modelOptionsFor(probed, [], 'CHAT').includes('qwen3-tts-flash'), false)
  eq('对话下拉不含图像模型', modelOptionsFor(probed, [], 'CHAT').includes('qwen-image-3.0'), false)

  // 已在清单里的要去掉（否则用户再点一次会疑惑「为什么没反应」）
  eq('已加入的不再出现在候选里', modelOptionsFor(probed, ['qwen-max'], 'CHAT').includes('qwen-max'), false)
  eq('剔除时按 trim 后的值比对', modelOptionsFor(['qwen-max'], [' qwen-max '], 'CHAT').length, 0)

  // 过滤后为空 = 该端点没有这类模型 → 前端切回手输，不是死路
  eq('端点无嵌入模型时返回空数组', modelOptionsFor(['qwen-max'], [], 'EMBEDDING').length, 0)
  eq('没有探测结果时返回空数组', modelOptionsFor([], [], 'EMBEDDING').length, 0)
  eq('null 入参不抛异常', modelOptionsFor(null, null, 'EMBEDDING').length, 0)

  // 顺序必须保持厂商返回的原顺序：用户按自己的认知找模型，重排会让人找不到
  eq('保持原始顺序', modelOptionsFor(probed, [], 'CHAT').join(','), 'qwen3.8-flash,qwen-max')
}

// ---------------------------------------------------------------- 端点是否有某类模型（不剔除已选）

{
  const probed = ['qwen-max', 'qwen3.7-text-embedding']

  // 核心：用户已把唯一的嵌入模型加进清单，候选会变空，
  // 但「端点有嵌入模型」这个事实没变 —— 提示语必须据此判断，否则自相矛盾。
  eq('候选为空但端点确实有一个嵌入模型', modelOptionsFor(probed, ['qwen3.7-text-embedding'], 'EMBEDDING').length, 0)
  eq('同一个端点，不剔除已选时仍能数出 1 个', modelCandidatesFor(probed, 'EMBEDDING').length, 1)

  eq('端点完全没有嵌入模型时为空', modelCandidatesFor(['qwen-max'], 'EMBEDDING').length, 0)
  eq('对话候选计数不受已选影响', modelCandidatesFor(probed, 'CHAT').join(','), 'qwen-max')
  eq('null 入参不抛异常', modelCandidatesFor(null, 'EMBEDDING').length, 0)
}
