/**
 * 供应商预设库（参照 cc-switch 的 provider presets）。
 *
 * <h2>为什么预设只放前端</h2>
 * 预设是「一串填好的默认值」，不是业务数据：它不随用户操作变化，不需要跨设备同步，
 * 也不想为增删一个供应商做一次数据库迁移。放前端常量里，改一行就生效。
 *
 * <p>数据库那边的 `ai_model_profile.preset_id` 只存一个标识字符串，
 * 用来在卡片上显示供应商标识、以及「这条配置来自哪个预设」。
 * 即使将来某个预设被删掉，老数据也只是显示不出 logo，不会报错。
 *
 * <h2>字段取舍</h2>
 * 只放本项目真正用得上的三项：端点、对话模型、嵌入模型。
 * cc-switch 里还有 authMethod / reasoningEffort / upstreamFormat 等，
 * 那些是 Claude Code / Codex / Gemini CLI 各自配置文件的差异，
 * 本项目统一走 OpenAI 兼容协议，用不上。
 *
 * <h2>默认模型名会过时</h2>
 * 模型名迭代很快。预设里填的只是**建议值**，用户建立配置后可以随意改；
 * 填错也不会静默出错——保存后的「测试连通性」会明确报出 404 / 模型不存在。
 */

/** 一个供应商预设 */
export interface ProviderPreset {
  /** 稳定标识，存进 `ai_model_profile.preset_id` */
  id: string
  /** 展示名 */
  name: string
  /** 归属厂商/说明，用于搜索与分组 */
  vendor: string
  /** OpenAI 兼容端点根地址（不含 /chat/completions） */
  baseUrl: string
  /** 建议的对话模型名 */
  chatModel: string
  /** 建议的嵌入模型名；留空表示该供应商不提供嵌入 */
  embeddingModel: string
  /** 需要填密钥吗（本地服务如 Ollama 不需要） */
  requiresKey: boolean
  /** 一句话说明，显示在选择面板里 */
  note: string
}

/**
 * 预设列表。
 *
 * <p>顺序即推荐顺序：国内可直连的排在前面（本项目主要在国内网络环境下使用），
 * 需要外网的排后面，本地服务与自定义放最后。
 */
export const PROVIDER_PRESETS: ProviderPreset[] = [
  {
    id: 'dashscope',
    name: '阿里云百炼',
    vendor: 'DashScope',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    chatModel: 'qwen3.8-flash',
    embeddingModel: 'qwen3.7-text-embedding-flash',
    requiresKey: true,
    note: '国内直连，对话/工具/读图与嵌入都有，本项目默认使用',
  },
  {
    id: 'deepseek',
    name: 'DeepSeek',
    vendor: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com/v1',
    chatModel: 'deepseek-chat',
    embeddingModel: '',
    requiresKey: true,
    note: '国内直连，长上下文便宜；不提供嵌入模型',
  },
  {
    id: 'moonshot',
    name: 'Moonshot 月之暗面',
    vendor: 'Moonshot',
    baseUrl: 'https://api.moonshot.cn/v1',
    chatModel: 'moonshot-v1-8k',
    embeddingModel: '',
    requiresKey: true,
    note: '国内直连，中文长文档处理见长',
  },
  {
    id: 'zhipu',
    name: '智谱 GLM',
    vendor: 'Zhipu',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    chatModel: 'glm-4-flash',
    embeddingModel: 'embedding-3',
    requiresKey: true,
    note: '国内直连，flash 档免费额度较大',
  },
  {
    id: 'openai',
    name: 'OpenAI 官方',
    vendor: 'OpenAI',
    baseUrl: 'https://api.openai.com/v1',
    chatModel: 'gpt-4o-mini',
    embeddingModel: 'text-embedding-3-small',
    requiresKey: true,
    note: '需外网；嵌入模型维度需与向量表一致（1024）',
  },
  {
    id: 'openrouter',
    name: 'OpenRouter',
    vendor: 'OpenRouter',
    baseUrl: 'https://openrouter.ai/api/v1',
    chatModel: 'openai/gpt-4o-mini',
    embeddingModel: '',
    requiresKey: true,
    note: '需外网；一个密钥聚合多家模型',
  },
  {
    id: 'ollama',
    name: '本地 Ollama',
    vendor: 'Local',
    baseUrl: 'http://localhost:11434/v1',
    chatModel: 'qwen2.5:7b',
    embeddingModel: 'bge-m3',
    requiresKey: false,
    note: '本机运行，无需密钥；需先 ollama pull 对应模型',
  },
  {
    id: 'custom',
    name: '自定义 / 中转站',
    vendor: 'Custom',
    baseUrl: '',
    chatModel: '',
    embeddingModel: '',
    requiresKey: true,
    note: '任何 OpenAI 兼容服务均可；自行填写端点与模型名',
  },
]

/** 按 id 取预设；取不到返回 null（老数据里的 presetId 可能已下线） */
export function findPreset(id: string | null | undefined): ProviderPreset | null {
  if (!id) {
    return null
  }
  return PROVIDER_PRESETS.find((preset) => preset.id === id) ?? null
}

/**
 * 按关键词搜索预设（名称 / 厂商 / 域名）。
 *
 * <p>三种都匹配是有意的：用户可能记得「阿里云」、也可能只知道域名
 * `dashscope.aliyuncs.com`、还可能只记得英文名 `DeepSeek`。
 * 只匹配名称会让另外两种情况都搜不到。
 *
 * @param keyword 关键词；空串返回全部
 */
export function searchPresets(keyword: string): ProviderPreset[] {
  const trimmed = keyword.trim().toLowerCase()
  if (trimmed.length === 0) {
    return PROVIDER_PRESETS
  }
  return PROVIDER_PRESETS.filter((preset) => {
    const haystack = `${preset.name} ${preset.vendor} ${preset.baseUrl} ${preset.chatModel}`.toLowerCase()
    return haystack.includes(trimmed)
  })
}

/**
 * 预设 → 新建配置的初始名。
 *
 * <p>用「预设名」而不是「预设名 + 序号」：同一种供应商建多条时，
 * 用户自己会改名（比如「百炼-主力」「百炼-备用」），
 * 自动加序号反而每次都要先删掉后缀。
 */
export function defaultProfileName(preset: ProviderPreset): string {
  return preset.name
}
