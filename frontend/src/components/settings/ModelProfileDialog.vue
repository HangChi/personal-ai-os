<script setup lang="ts">
/**
 * 新建 / 编辑模型配置的对话框。
 *
 * <h2>为什么用对话框而不是内联展开</h2>
 * 表单有 6 个字段（名称 / 端点 / 密钥 / 对话模型 / 温度 / 嵌入模型），
 * 内联展开会把卡片列表推得很长，而且一次只能编辑一张。对话框让
 * 「正在编辑哪一套」有明确的边界，取消即丢弃，不会留下半改状态。
 *
 * <h2>密钥永不回填</h2>
 * 编辑时密钥框留空 = 不改动。把后端返回的掩码 `****8f3a` 填进去再提交，
 * 会把真实密钥覆盖成那七个字符 —— 所以这里始终 `apiKey: ''`。
 */
import { Download, QuestionFilled, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { probeModelList, probeModelWindow } from '@/api/model-profile'
import { confirmDanger } from '@/lib/confirm-box'
import {
  MODEL_NAME_MAX,
  PROFILE_NAME_MAX,
  PROTOCOL_OPTIONS,
  SOURCE_LABELS,
  SOURCE_TAG_TYPES,
  TEMPERATURE_MAX,
  TEMPERATURE_MIN,
  buildProfilePayload,
  emptyForm,
  formatContextWindow,
  mergeProbedWindows,
  moveModel,
  modelCandidatesFor,
  modelOptionsFor,
  parseContextWindow,
  probeAlertType,
  probeBlockedReason,
  probeResultHint,
  probeResultSummary,
  shouldShowSource,
  sourceHint,
  toForm,
  validateProfileForm,
  type ModelProfileForm as FormShape,
} from '@/lib/model-profile'
import type { ProviderPreset } from '@/lib/provider-presets'
import type { ModelListResult, ModelProfile, SaveModelProfilePayload } from '@/types/api'

const props = defineProps<{
  modelValue: boolean
  /** 编辑目标；null 表示新建 */
  profile: ModelProfile | null
  /** 新建时的预设（来自预设库）；编辑时为 null */
  preset: ProviderPreset | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submit', payload: SaveModelProfilePayload, id: string | null): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const form = reactive<FormShape>(emptyForm())
const submitting = ref(false)
/** 用户点了「清除密钥」（尚未提交） */
const pendingClearKey = ref(false)

/** 正在探测模型列表 */
const probing = ref(false)
/** 最近一次探测结果；null 表示还没探测过 */
const probeResult = ref<ModelListResult | null>(null)
const probingWindow = ref<string | null>(null)
const completingWindows = ref(false)
interface WindowFeedback {
  text: string
  sourceLabel: string
  summary: string
  sourceUrl?: string | null
  reference: boolean
  detected?: number
  differs?: boolean
  failed?: boolean
}
const windowFeedback = ref<Record<string, WindowFeedback>>({})
let windowProbeEpoch = 0

function resetWindowProbes(): void {
  windowProbeEpoch += 1
  probingWindow.value = null
  completingWindows.value = false
  windowFeedback.value = {}
}

watch(
  () => [form.protocol, form.baseUrl, form.apiKey, pendingClearKey.value, props.profile?.id, props.modelValue],
  resetWindowProbes,
  { flush: 'sync' },
)

/**
 * 「加入清单」输入框的草稿。
 *
 * <p>必须声明在下面的 `watch(..., { immediate: true })` **之前**：那个 watch
 * 在定义时就会立刻跑一次，若草稿在它后面用 `const` 声明，回调里赋值会撞
 * 暂时性死区（ReferenceError），整个组件都渲染不出来。
 *
 * <p>不直接绑到 `form.chatModels` 上：清单是**有序数组**，顺序本身就是
 * 「哪个是默认」的语义，不能边输入边改。草稿是待加入的候选，加入后立刻清空 ——
 * 否则再点一次「加入」会把同一个模型名加两遍（后端虽然会去重，
 * 但界面上先出现重复行再消失很怪）。
 */
const chatModelDraft = ref('')
const embeddingModelDraft = ref('')
const audioModelDraft = ref('')

const isEdit = computed(() => props.profile !== null)

/** 编辑态下这套配置已保存过密钥吗（探测可回落到它） */
const hasStoredKey = computed(() => props.profile?.hasApiKey === true)

/** 探测按钮被禁的原因；null 表示可点 */
const probeBlocked = computed(() =>
  probeBlockedReason(form.protocol, form.baseUrl, form.apiKey, hasStoredKey.value && !pendingClearKey.value),
)

/** 探测出来的模型名，供两个下拉框共用 */
const availableModels = computed<string[]>(() => (probeResult.value?.ok ? probeResult.value.models : []))

/**
 * 打开时初始化表单。
 *
 * <p>依赖 `modelValue` 而不只是 `profile`：连续新建两次时 profile 都是 null，
 * 只看 profile 不会重置，第二次打开会残留上一次的输入。
 */
watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    pendingClearKey.value = false
    submitting.value = false
    // 草稿必须一起清：上次关框时留了半截输入，下次打开会直接看到它
    chatModelDraft.value = ''
    embeddingModelDraft.value = ''
    audioModelDraft.value = ''
    // 每次打开都清掉上次的探测结果：它属于「上一次填的端点」，
    // 留着会让用户以为当前端点的模型就是这些
    probeResult.value = null
    probing.value = false
    if (props.profile) {
      Object.assign(form, toForm(props.profile))
    } else {
      Object.assign(form, emptyForm(props.preset?.id ?? null))
      // 新建时从预设填入清单（预设只有一个模型名 → 单元素清单）
      if (props.preset) {
        form.name = props.preset.name
        form.baseUrl = props.preset.baseUrl
        form.protocol = props.preset.protocol
        form.chatModels = props.preset.chatModel ? [props.preset.chatModel] : []
        form.embeddingModels = props.preset.embeddingModel ? [props.preset.embeddingModel] : []
      }
    }
  },
  { immediate: true },
)

/** 当前正在编辑的配置（编辑态才有），用于显示来源徽章 */
const current = computed(() => props.profile)

async function handleClearKey(): Promise<void> {
  const ok = await confirmDanger({
    title: '清除 API Key',
    message:
      '将删除这套配置里保存的 API Key，之后回落到 .env 里的 OPENAI_API_KEY。若环境变量也没有密钥，使用这套配置时将无法对话。',
    confirmText: '清除',
  })
  if (!ok) {
    return
  }
  pendingClearKey.value = true
  form.apiKey = ''
}

function handleSubmit(): void {
  const invalid = validateProfileForm(form)
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  submitting.value = true
  emit('submit', buildProfilePayload(form, pendingClearKey.value), props.profile?.id ?? null)
}

/**
 * 探测该端点支持哪些模型。
 *
 * <h3>为什么允许在保存之前探测</h3>
 * 用户往往先填端点与密钥、想看看有什么模型再决定用哪个。若必须保存才能探测，
 * 「新建一套」就变成「先瞎填模型名保存 → 再回来探测 → 再改」，
 * 而瞎填模型名正是这个功能要消除的。
 *
 * <h3>为什么失败不弹错误框</h3>
 * 「这个端点不提供模型列表」很常见（自建、中转站），但它们能正常聊天。
 * 弹红色错误框会让人以为配置坏了。这里用一行可读提示，
 * 并把「手动填写」这条路明确指出来。
 */
async function handleProbe(): Promise<void> {
  if (probeBlocked.value) {
    ElMessage.warning(probeBlocked.value)
    return
  }
  probing.value = true
  probeResult.value = null
  try {
    probeResult.value = await probeModelList({
      protocol: form.protocol,
      baseUrl: form.baseUrl,
      // 密钥框空着时传 undefined，让后端回落到该配置已保存的密钥
      apiKey: form.apiKey.trim().length > 0 ? form.apiKey : undefined,
      profileId: props.profile?.id ?? null,
      /*
       * 带上当前清单：后端据此优先给这些模型补查窗口。
       *
       * <p>不传的后果是「窗口识别时灵时不灵」——后端只能按字母序取前 40 个
       * 去问详情，而 DashScope 的 qwen3.8-flash 排第 148 位、qwen-max 排第 65 位，
       * 正好都在截断之外。传上去之后，用户关心的这 2~6 个模型必定被问到。
       */
      models: [...form.chatModels, ...form.embeddingModels, ...form.audioModels],
    })
    /*
     * 把探测到的窗口补进表单。
     *
     * <p>**只补空缺、不覆盖用户手填的值**：探测可以重复点，整体替换会把
     * 用户按文档手填的窗口冲掉 —— 而点这个按钮的本意是「看看有什么模型」。
     * 详见 `mergeProbedWindows`。
     *
     * <p>只对已在本配置清单里的模型生效：探测结果里往往有一百多个模型，
     * 全写进表单会让「表单」变成「端点模型台账」，提交时还得再过滤一遍。
     */
    if (probeResult.value.ok) {
      form.contextWindows = mergeProbedWindows(form.contextWindows, probeResult.value.contextWindows, form.chatModels)
    }
  } catch (cause) {
    probeResult.value = {
      ok: false,
      models: [],
      latencyMs: 0,
      error: cause instanceof Error ? cause.message : '探测请求失败',
      // 请求本身没能完成（网络/后端异常）—— 这是真错误，不是「端点不支持」
      kind: 'ERROR',
    }
  } finally {
    probing.value = false
  }
}

/**
 * 下拉候选：按用途过滤，再去掉已经在清单里的。
 *
 * <p>厂商的 `/v1/models` 是不带类型字段的扁平数组（实测百炼一个端点
 * 返回 183 个模型，其中只有 2 个是嵌入模型），两个下拉若共用同一份，
 * 「嵌入模型」里会混进 145 个对话大模型 —— 全是选了必报错的死选项。
 * 分类规则见 `classifyModel`。
 *
 * <p>过滤后为空不是死路：模板会切回普通输入框，用户仍可手打模型名。
 */
const chatModelOptions = computed<string[]>(() => modelOptionsFor(availableModels.value, form.chatModels, 'CHAT'))
const embeddingModelOptions = computed<string[]>(() =>
  modelOptionsFor(availableModels.value, form.embeddingModels, 'EMBEDDING'),
)
/**
 * 语音模型下拉候选（V24）。
 *
 * <p>分类见 `classifyModel` 的 AUDIO 分支。注意 omni 那批已被归为 CHAT：
 * 它们能当对话模型用，只是另外支持实时语音，放进语音栏会让用户以为
 * 「选了它就能转写」—— 而 omni 的实时语音走的是 WebSocket 协议，
 * 与这里的 `/v1/audio/transcriptions` 不是一回事。
 */
const audioModelOptions = computed<string[]>(() => modelOptionsFor(availableModels.value, form.audioModels, 'AUDIO'))

/**
 * 端点实际提供了几个嵌入模型（**不剔除已选项**）。
 *
 * <p>只用于「这端点没有嵌入模型」那句提示的判据。拿
 * {@link embeddingModelOptions} 判断会漏掉「用户已把它加进清单」的情况，
 * 于是提示与清单里那一行自相矛盾（实测确实能触发）。
 */
const embeddingCandidates = computed<string[]>(() => modelCandidatesFor(availableModels.value, 'EMBEDDING'))

/**
 * 该不该说「这端点没有嵌入模型」。
 *
 * <p>判据用 {@link embeddingCandidates}（未剔除已选项）而不是
 * {@link embeddingModelOptions}（已剔除）：用户把端点里唯一的嵌入模型加进
 * 清单后候选会变空，但「端点有嵌入模型」这个事实没变 —— 用后者会说出一句
 * 与清单里那一行自相矛盾的话（实测确实如此）。
 *
 * <p>同时要求清单里没有——已经配好了再说「没有嵌入模型」没有意义。
 */
const showNoEmbeddingHint = computed(
  () => probeResult.value?.ok === true && embeddingCandidates.value.length === 0 && form.embeddingModels.length === 0,
)

/**
 * 「加入」按钮的可用状态。
 *
 * <p>`el-select` 的 `@change` **不再**直接加入清单。改造前它是
 * `@change="handleAddChatModel"` —— 只要在下拉里点中一个模型，它立刻进清单，
 * 「加入」按钮形同虚设，用户甚至没机会反悔。回归测试里这种「选了就等于提交」
 * 是最容易被当成 bug 报上来的交互。
 *
 * <p>现在的语义统一成：**选/输 → 草稿，点「加入」→ 进清单**。
 * 两条路径（下拉、手输）行为一致，`Enter` 也走同一个入口。
 */
const canAddChatModel = computed(() => chatModelDraft.value.trim().length > 0)
const canAddEmbeddingModel = computed(() => embeddingModelDraft.value.trim().length > 0)
const canAddAudioModel = computed(() => audioModelDraft.value.trim().length > 0)

function handleAddChatModel(): void {
  const before = form.chatModels
  form.chatModels = appendModel(form.chatModels, chatModelDraft.value)
  // 真加进去了才补窗口（appendModel 遇到重复项会返回原数组）。
  // 用探测值预填：用户在「获取模型列表」之后加模型，窗口大概率已经在
  // probeResult 里了，让他再手抄一遍纯属多余。
  if (form.chatModels !== before) {
    form.contextWindows = mergeProbedWindows(form.contextWindows, probeResult.value?.contextWindows, form.chatModels)
    const name = chatModelDraft.value.trim()
    if (!form.contextWindows[name] && !probeBlocked.value) void handleProbeWindow(name)
  }
  chatModelDraft.value = ''
}

function handleAddEmbeddingModel(): void {
  form.embeddingModels = appendModel(form.embeddingModels, embeddingModelDraft.value)
  embeddingModelDraft.value = ''
}

function handleAddAudioModel(): void {
  form.audioModels = appendModel(form.audioModels, audioModelDraft.value)
  audioModelDraft.value = ''
}

/**
 * 往清单末尾追加一个模型名。
 *
 * <p>先 `trim` 再查重：`「qwen-max 」` 与 `「qwen-max」` 在模型名语义上是同一个，
 * 但字符串比较不等 —— 放进去会变成两行、且后端唯一索引会因空白差异判定为不同值。
 */
function appendModel(picked: string[], value: string | null | undefined): string[] {
  const name = (value ?? '').trim()
  if (name.length === 0) {
    return picked
  }
  return picked.includes(name) ? picked : [...picked, name]
}

/** 移除清单里的一项。默认模型（第一项）移除后由原来的第二项顶上，符合「第一个即默认」 */
function removeModel(kind: 'chat' | 'embedding' | 'audio', index: number): void {
  if (kind === 'chat') {
    form.chatModels = form.chatModels.filter((_, i) => i !== index)
    return
  }
  if (kind === 'audio') {
    form.audioModels = form.audioModels.filter((_, i) => i !== index)
    return
  }
  form.embeddingModels = form.embeddingModels.filter((_, i) => i !== index)
}

/** 提交结束后由父组件调用，关闭对话框（失败时父组件不关） */
function finish(): void {
  submitting.value = false
}

/**
 * 查单个模型的上下文窗口（阶段 24）。
 *
 * <h3>只填空缺、不覆盖已填的值</h3>
 * 这个按钮解决的是「刚加进来的模型窗口是空的」。而端点返回的
 * `max_input_tokens` 常常是**实例默认参数**而不是能力上限（DashScope 对
 * 几乎所有模型都回 129024，包括文档写着 1M 的模型）。若无条件覆盖，
 * 用户点一下就会把自己按文档填的 1M 冲成 126K —— 那正是要避免的事。
 * 所以：空 → 写入；已有值且不同 → 只提示不覆盖，让用户自己决定。
 *
 * @param name 模型名
 */
async function handleProbeWindow(name: string): Promise<boolean> {
  if (probingWindow.value || probeBlocked.value) return false
  const epoch = windowProbeEpoch
  probingWindow.value = name
  try {
    const result = await probeModelWindow({
      protocol: form.protocol,
      baseUrl: form.baseUrl,
      apiKey: form.apiKey.trim().length > 0 ? form.apiKey : undefined,
      profileId: props.profile?.id ?? null,
      model: name,
    })
    if (epoch !== windowProbeEpoch || !form.chatModels.includes(name)) return false
    if (!result.ok || typeof result.window !== 'number') {
      windowFeedback.value[name] = {
        text: result.error ?? '未取得窗口，可手动填写',
        summary: result.error ?? '未取得窗口，可手动填写',
        sourceLabel: '未取得',
        reference: false,
        failed: true,
      }
      if (!completingWindows.value) ElMessage.warning(windowFeedback.value[name].text)
      return !['AUTH_FAILED', 'FORBIDDEN', 'RATE_LIMITED', 'NETWORK_ERROR', 'TIMEOUT', 'HTTP_ERROR'].includes(
        result.kind ?? '',
      )
    }
    const detected = result.window
    const reference = result.source === 'reference' || result.source === 'catalog'
    const label = reference
      ? result.source === 'catalog'
        ? '内置参考'
        : '官方参考'
      : result.source === 'input-limit'
        ? '端点输入上限'
        : '端点窗口'
    const current = form.contextWindows[name]
    const currentText = current === undefined || current === null ? '' : String(current).trim()
    const differs = currentText.length > 0 && parseContextWindow(currentText) !== detected
    if (!currentText) form.contextWindows[name] = String(detected)
    windowFeedback.value[name] = {
      text: result.message ?? (reference ? '参考值尚未经过当前端点的容量验证。' : '此数值由当前端点返回。'),
      sourceLabel: label,
      summary: differs
        ? `${reference ? '参考' : '查询'} ${formatContextWindow(detected)}，已保留当前填写值`
        : '已填入，保存后生效',
      sourceUrl: result.sourceUrl,
      reference,
      detected,
      differs,
    }
    if (!completingWindows.value) {
      if (reference || differs)
        ElMessage.info(differs ? '查询结果不同，可按需采用；当前值已保留' : '参考信息已更新，可展开详情查看来源')
      else ElMessage.success(`${name}：${formatContextWindow(detected)}`)
    }
    return true
  } catch (cause) {
    if (epoch === windowProbeEpoch && form.chatModels.includes(name)) {
      windowFeedback.value[name] = {
        text: cause instanceof Error ? cause.message : '查询窗口失败，请重试',
        summary: cause instanceof Error ? cause.message : '查询窗口失败，请重试',
        sourceLabel: '查询失败',
        reference: false,
        failed: true,
      }
    }
    return false
  } finally {
    if (epoch === windowProbeEpoch) probingWindow.value = null
  }
}

const missingWindows = computed(() => form.chatModels.filter((name) => !String(form.contextWindows[name] ?? '').trim()))

async function completeMissingWindows(): Promise<void> {
  if (completingWindows.value || probingWindow.value || probeBlocked.value) return
  completingWindows.value = true
  const epoch = windowProbeEpoch
  const targets = [...missingWindows.value]
  try {
    // Sequential metadata requests avoid bursting the provider's rate limit.
    for (const name of targets) {
      if (epoch !== windowProbeEpoch) break
      if (!(await handleProbeWindow(name))) break
    }
  } finally {
    if (epoch === windowProbeEpoch) completingWindows.value = false
  }
}

function applyDetectedWindow(name: string): void {
  const feedback = windowFeedback.value[name]
  if (!feedback?.detected) return
  form.contextWindows[name] = String(feedback.detected)
  feedback.differs = false
  feedback.summary = '已采用，保存后生效'
}

function updateWindow(name: string, value: string): void {
  form.contextWindows[name] = value
  delete windowFeedback.value[name]
}

/**
 * 某一行的窗口输入提示。
 *
 * <p>分三种语气，与输入框的三种状态一一对应：
 * <ul>
 *   <li>空 → 说清「留空会怎样」（回落内置表/全局默认），这是最常见的状态；</li>
 *   <li>合法 → 只报换算结果，不啰嗦；</li>
 *   <li>非法 → 指出为什么不行（下限 1024），并说明提交时会怎样。</li>
 * </ul>
 */
function windowHint(name: string): string {
  const raw = form.contextWindows[name]
  if (raw === undefined || raw === null || String(raw).trim().length === 0) {
    return '留空表示未知，按内置模型对照表或全局默认窗口处理'
  }
  const parsed = parseContextWindow(raw)
  if (parsed === null) {
    return '认不出这个值。可以填 32768、32k、1m 这类写法，最小 1024'
  }
  return `${parsed} token。该模型的上下文上限；实际输入预算会收敛在此范围内`
}

/** 输入框右侧的换算短写；非法时给出警示文案 */
function windowNote(name: string): { text: string; bad: boolean; hint: string } {
  const raw = form.contextWindows[name]
  if (raw === undefined || raw === null || String(raw).trim().length === 0) {
    // 空着不显示「自动」两个字：那个词会让人以为这里有个开关在生效。
    // 真要知道兜底逻辑，输入框的 title 里有完整说明。
    return { text: '', bad: false, hint: '' }
  }
  const parsed = parseContextWindow(raw)
  if (parsed === null) {
    return { text: '无效', bad: true, hint: windowHint(name) }
  }
  return { text: formatContextWindow(parsed), bad: false, hint: windowHint(name) }
}

defineExpose({ finish })
</script>

<template>
  <el-dialog
    v-model="visible"
    class="profile-dialog"
    :title="isEdit ? '编辑模型配置' : '添加模型配置'"
    width="min(840px, calc(100vw - 32px))"
    :close-on-click-modal="false"
    append-to-body
  >
    <template #header>
      <div class="profile-dialog__heading">
        <div>
          <h2>{{ isEdit ? '编辑模型配置' : '添加模型配置' }}</h2>
          <p>管理连接信息与模型，保存后应用到这套配置。</p>
        </div>
        <span v-if="current?.active" class="profile-dialog__active">当前使用中</span>
      </div>
    </template>
    <div class="profile-form">
      <section class="profile-form__section" aria-labelledby="profile-connection-heading">
        <div class="profile-form__section-heading">
          <h3 id="profile-connection-heading">连接配置</h3>
          <span>供应商与访问凭据</span>
        </div>

        <div class="profile-form__grid">
          <label class="profile-form__field">
            <span class="profile-form__label">配置名称</span>
            <el-input
              v-model="form.name"
              placeholder="如：百炼主力"
              clearable
              :maxlength="PROFILE_NAME_MAX"
              show-word-limit
            />
          </label>

          <div class="profile-form__field">
            <span class="profile-form__label">
              协议
              <el-tooltip
                content="只决定「获取模型列表」时调哪个接口。聊天链路统一走 OpenAI 兼容协议，选哪个都能正常对话。"
                placement="top"
              >
                <el-icon class="profile-form__hint-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
            <el-select v-model="form.protocol">
              <el-option
                v-for="option in PROTOCOL_OPTIONS"
                :key="option.value"
                :value="option.value"
                :label="option.label"
              >
                <span class="profile-form__option-label">{{ option.label }}</span>
                <span class="profile-form__option-hint">{{ option.hint }}</span>
              </el-option>
            </el-select>
          </div>
        </div>

        <label class="profile-form__field">
          <span class="profile-form__label">
            端点地址
            <template v-if="current">
              <el-tag
                v-if="shouldShowSource(current.sources.baseUrl)"
                size="small"
                :type="SOURCE_TAG_TYPES[current.sources.baseUrl]"
              >
                {{ SOURCE_LABELS[current.sources.baseUrl] }}
              </el-tag>
              <el-tooltip :content="sourceHint(current.sources.baseUrl)" placement="top">
                <el-icon class="profile-form__hint-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
          </span>
          <el-input v-model="form.baseUrl" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" clearable />
          <span class="profile-form__help">根地址，不要带 /chat/completions。留空则用环境变量</span>
        </label>

        <label class="profile-form__field">
          <span class="profile-form__label">
            API Key
            <template v-if="current">
              <el-tag
                v-if="shouldShowSource(current.sources.apiKey)"
                size="small"
                :type="SOURCE_TAG_TYPES[current.sources.apiKey]"
              >
                {{ SOURCE_LABELS[current.sources.apiKey] }}
              </el-tag>
              <span class="profile-form__current">
                当前：{{ current.hasApiKey ? current.apiKeyMasked : '未设置' }}
              </span>
            </template>
          </span>
          <div class="profile-form__key-row">
            <el-input
              v-model="form.apiKey"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="pendingClearKey ? '保存后清除密钥' : isEdit ? '留空表示不修改' : '输入供应商的 API Key'"
            />
            <el-button v-if="current" :disabled="!current.hasApiKey || pendingClearKey" @click="handleClearKey">
              清除
            </el-button>
          </div>

          <span class="profile-form__help">密钥不会回显，留空沿用已有配置。</span>
        </label>
      </section>
      <section class="profile-form__section" aria-labelledby="profile-chat-heading">
        <div class="profile-form__field">
          <div class="profile-form__label-row">
            <span class="profile-form__label">
              <span id="profile-chat-heading">对话模型</span>
              <span class="profile-form__count">{{ form.chatModels.length }}</span>
              <template v-if="current">
                <el-tag
                  v-if="shouldShowSource(current.sources.chatModel)"
                  size="small"
                  :type="SOURCE_TAG_TYPES[current.sources.chatModel]"
                >
                  {{ SOURCE_LABELS[current.sources.chatModel] }}
                </el-tag>
              </template>
            </span>
            <el-tooltip :content="probeBlocked ?? '按当前端点与密钥拉取该端点支持的模型'" placement="top">
              <span>
                <el-button size="small" :loading="probing" :disabled="probeBlocked !== null" @click="handleProbe">
                  <el-icon v-if="!probing"><Download /></el-icon>
                  获取模型列表
                </el-button>
              </span>
            </el-tooltip>
          </div>

          <el-alert
            v-if="probeResult"
            class="profile-form__probe"
            :type="probeAlertType(probeResult)"
            :closable="false"
            show-icon
            :title="probeResultSummary(probeResult)"
          >
            <span v-if="!probeResult.ok" class="profile-form__probe-hint">{{ probeResultHint(probeResult) }}</span>
          </el-alert>

          <div v-if="form.chatModels.length > 0" class="profile-form__window-toolbar">
            <span>上下文窗口 <span class="profile-form__window-unit">token</span></span>
            <el-button
              link
              size="small"
              :loading="completingWindows"
              :disabled="!missingWindows.length || !!probeBlocked || probingWindow !== null"
              @click="completeMissingWindows"
              >补全空缺窗口</el-button
            >
          </div>
          <div v-if="form.chatModels.length > 0" class="profile-form__models profile-form__models--chat">
            <div
              v-for="(name, index) in form.chatModels"
              :key="`chat-${index}-${name}`"
              class="profile-form__model-row profile-form__model-row--chat"
            >
              <span class="profile-form__model-name" :title="name">{{ name }}</span>

              <span class="profile-form__model-window">
                <el-input
                  :model-value="form.contextWindows[name] ?? ''"
                  size="small"
                  clearable
                  :maxlength="12"
                  placeholder="待获取"
                  :title="windowHint(name)"
                  :aria-label="`${name} 的上下文窗口（token）`"
                  @update:model-value="(value: string) => updateWindow(name, value)"
                />

                <span
                  class="profile-form__model-window-note"
                  :class="{ 'profile-form__model-window-note--bad': windowNote(name).bad }"
                  :title="windowNote(name).hint"
                >
                  {{ windowNote(name).text }}
                </span>

                <el-button
                  class="profile-form__model-probe"
                  link
                  size="small"
                  :loading="probingWindow === name"
                  :disabled="!!probeBlocked || completingWindows || (probingWindow !== null && probingWindow !== name)"
                  title="向端点查询这个模型的上下文窗口（不会覆盖已填的值）"
                  :aria-label="`查询 ${name} 的上下文窗口`"
                  @click="handleProbeWindow(name)"
                >
                  <el-icon v-if="probingWindow !== name"><Refresh /></el-icon>
                </el-button>
              </span>

              <span class="profile-form__model-pick">
                <el-tag v-if="index === 0" size="small" type="success">默认</el-tag>
                <el-button
                  v-else
                  link
                  size="small"
                  title="设为默认（挪到第一位）"
                  @click="form.chatModels = moveModel(form.chatModels, index, 0)"
                >
                  设为默认
                </el-button>
              </span>
              <span class="profile-form__model-actions">
                <el-button link size="small" type="danger" @click="removeModel('chat', index)">移除</el-button>
              </span>
              <div
                v-if="windowFeedback[name]"
                class="profile-form__window-feedback"
                :class="{ 'profile-form__window-feedback--failed': windowFeedback[name].failed }"
                role="status"
              >
                <span class="profile-form__window-source">{{ windowFeedback[name].sourceLabel }}</span>
                <span class="profile-form__window-summary">{{ windowFeedback[name].summary }}</span>
                <el-button
                  v-if="windowFeedback[name].differs"
                  class="profile-form__apply-window"
                  type="primary"
                  link
                  size="small"
                  @click="applyDetectedWindow(name)"
                  >采用{{ windowFeedback[name].reference ? '参考值' : '查询值' }}</el-button
                >
                <details v-if="!windowFeedback[name].failed" class="profile-form__window-details">
                  <summary>详情</summary>
                  <div class="profile-form__window-explanation">
                    <span>查询值：{{ windowFeedback[name].detected?.toLocaleString() }} token</span>
                    <p>{{ windowFeedback[name].text }}</p>
                    <a
                      v-if="windowFeedback[name].sourceUrl"
                      :href="windowFeedback[name].sourceUrl!"
                      target="_blank"
                      rel="noopener noreferrer"
                      >查看官方说明 ↗</a
                    >
                  </div>
                </details>
              </div>
            </div>
          </div>

          <div class="profile-form__model-add">
            <el-select
              v-if="chatModelOptions.length > 0"
              v-model="chatModelDraft"
              class="profile-form__model-select"
              filterable
              allow-create
              default-first-option
              clearable
              :reserve-keyword="false"
              placeholder="从列表里选，或直接输入模型名"
            >
              <el-option v-for="name in chatModelOptions" :key="name" :value="name" :label="name" />
            </el-select>
            <el-input
              v-else
              v-model="chatModelDraft"
              placeholder="输入模型名，如 qwen3.8-flash"
              clearable
              :maxlength="MODEL_NAME_MAX"
              @keyup.enter="handleAddChatModel"
            />
            <el-button :disabled="!canAddChatModel" @click="handleAddChatModel">加入</el-button>
          </div>
          <span class="profile-form__help">
            第一个为默认模型，也可在对话中切换。窗口来源与详细说明可在查询后查看。
          </span>
        </div>

        <div class="profile-form__field profile-form__temperature">
          <span class="profile-form__label">
            温度
            <template v-if="current">
              <el-tag
                v-if="shouldShowSource(current.sources.temperature)"
                size="small"
                :type="SOURCE_TAG_TYPES[current.sources.temperature]"
              >
                {{ SOURCE_LABELS[current.sources.temperature] }}
              </el-tag>
            </template>
          </span>
          <div v-if="form.temperature !== null" class="profile-form__inline">
            <el-slider
              v-model="form.temperature"
              :min="TEMPERATURE_MIN"
              :max="TEMPERATURE_MAX"
              :step="0.1"
              class="profile-form__slider"
            />
            <span class="profile-form__temperature-value" aria-live="polite">
              {{ form.temperature.toFixed(1) }}
            </span>
            <el-button v-if="form.temperature !== null" link size="small" @click="form.temperature = null">
              用默认
            </el-button>
          </div>
          <div v-if="form.temperature === null" class="profile-form__temperature-default">
            <span>使用服务端默认值</span>
            <el-button link type="primary" size="small" @click="form.temperature = 0.7">自定义</el-button>
          </div>
          <span v-else class="profile-form__help">数值越低，回答越稳定。</span>
        </div>
      </section>
      <details class="profile-form__optional">
        <summary class="profile-form__optional-heading">
          <span><strong>嵌入与语音</strong><small>知识库向量化、语音转写</small></span>
          <span class="profile-form__optional-state"
            >{{ form.embeddingModels.length + form.audioModels.length }} 个模型 <span aria-hidden="true">⌄</span></span
          >
        </summary>
        <div class="profile-form__optional-body">
          <div class="profile-form__field">
            <span class="profile-form__label">
              嵌入模型
              <template v-if="current">
                <el-tag
                  v-if="shouldShowSource(current.sources.embeddingModel)"
                  size="small"
                  :type="SOURCE_TAG_TYPES[current.sources.embeddingModel]"
                >
                  {{ SOURCE_LABELS[current.sources.embeddingModel] }}
                </el-tag>
              </template>
            </span>
            <div v-if="form.embeddingModels.length > 0" class="profile-form__models">
              <div
                v-for="(name, index) in form.embeddingModels"
                :key="`embed-${index}-${name}`"
                class="profile-form__model-row"
              >
                <span class="profile-form__model-name">{{ name }}</span>
                <span class="profile-form__model-pick">
                  <el-tag v-if="index === 0" size="small" type="success">默认</el-tag>
                  <el-button
                    v-else
                    link
                    size="small"
                    title="设为默认（挪到第一位）"
                    @click="form.embeddingModels = moveModel(form.embeddingModels, index, 0)"
                  >
                    设为默认
                  </el-button>
                </span>
                <span class="profile-form__model-actions">
                  <el-button link size="small" type="danger" @click="removeModel('embedding', index)">移除</el-button>
                </span>
              </div>
            </div>

            <div class="profile-form__model-add">
              <el-select
                v-if="embeddingModelOptions.length > 0"
                v-model="embeddingModelDraft"
                class="profile-form__model-select"
                filterable
                allow-create
                default-first-option
                clearable
                :reserve-keyword="false"
                placeholder="从这些嵌入模型里选，或直接输入模型名"
              >
                <el-option v-for="name in embeddingModelOptions" :key="name" :value="name" :label="name" />
              </el-select>
              <el-input
                v-else
                v-model="embeddingModelDraft"
                placeholder="输入嵌入模型名"
                clearable
                :maxlength="MODEL_NAME_MAX"
                @keyup.enter="handleAddEmbeddingModel"
              />
              <el-button :disabled="!canAddEmbeddingModel" @click="handleAddEmbeddingModel">加入</el-button>
            </div>

            <span v-if="showNoEmbeddingHint" class="profile-form__help profile-form__help--muted">
              获取到的 {{ availableModels.length }} 个模型里没有嵌入模型（对话模型不能用于向量化），确实需要就手动输入。
            </span>
            <span v-else class="profile-form__help">
              知识库向量化用，可留空。换嵌入模型会让向量失效（维度可能变），需重建索引。
            </span>
          </div>

          <div class="profile-form__field">
            <span class="profile-form__label">
              语音模型
              <template v-if="current">
                <el-tag
                  v-if="shouldShowSource(current.sources.audioModel)"
                  size="small"
                  :type="SOURCE_TAG_TYPES[current.sources.audioModel]"
                >
                  {{ SOURCE_LABELS[current.sources.audioModel] }}
                </el-tag>
              </template>
            </span>
            <div v-if="form.audioModels.length > 0" class="profile-form__models">
              <div
                v-for="(name, index) in form.audioModels"
                :key="`audio-${index}-${name}`"
                class="profile-form__model-row"
              >
                <span class="profile-form__model-name">{{ name }}</span>
                <span class="profile-form__model-pick">
                  <el-tag v-if="index === 0" size="small" type="success">默认</el-tag>
                  <el-button
                    v-else
                    link
                    size="small"
                    title="设为默认（挪到第一位）"
                    @click="form.audioModels = moveModel(form.audioModels, index, 0)"
                  >
                    设为默认
                  </el-button>
                </span>
                <span class="profile-form__model-actions">
                  <el-button link size="small" type="danger" @click="removeModel('audio', index)">移除</el-button>
                </span>
              </div>
            </div>
            <div class="profile-form__model-add">
              <el-select
                v-if="audioModelOptions.length > 0"
                v-model="audioModelDraft"
                class="profile-form__model-select"
                filterable
                allow-create
                default-first-option
                clearable
                :reserve-keyword="false"
                placeholder="从这些语音模型里选，或直接输入模型名"
              >
                <el-option v-for="name in audioModelOptions" :key="name" :value="name" :label="name" />
              </el-select>
              <el-input
                v-else
                v-model="audioModelDraft"
                placeholder="输入语音模型名"
                clearable
                :maxlength="MODEL_NAME_MAX"
                @keyup.enter="handleAddAudioModel"
              />
              <el-button :disabled="!canAddAudioModel" @click="handleAddAudioModel">加入</el-button>
            </div>

            <span class="profile-form__help">
              聊天页麦克风用，可留空。留空时语音输入走浏览器自带识别（录音会送往浏览器厂商）；填写后录音改由这套配置转写。
            </span>
          </div>
        </div>
      </details>
    </div>

    <template #footer>
      <span class="profile-dialog__footer-note">更改在保存后生效</span>
      <div class="profile-dialog__footer-actions">
        <el-button :disabled="submitting" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ isEdit ? '保存' : '添加' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style>
/* The dialog is teleported to body; its shell uses a unique global class. */
html .profile-dialog {
  display: flex;
  flex-direction: column;
  margin: 4vh auto;
  max-height: 92vh;
  max-height: 92dvh;
  padding: 0;
  overflow: hidden;
  border-radius: 16px;
}
.profile-dialog .el-dialog__header {
  flex-shrink: 0;
  margin: 0;
  padding: 24px 56px 20px 24px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.profile-dialog .el-dialog__headerbtn {
  top: 16px;
  right: 16px;
}
.profile-dialog .el-dialog__body {
  flex: 1;
  min-height: 0;
  padding: 24px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: var(--el-border-color) transparent;
}
.profile-dialog .el-dialog__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-shrink: 0;
  padding: 16px 24px;
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}
@media (max-width: 600px) {
  html .profile-dialog {
    margin: 12px auto;
    max-height: calc(100dvh - 24px);
  }
  .profile-dialog .el-dialog__header {
    padding: 20px 48px 16px 16px;
  }
  .profile-dialog .el-dialog__body {
    padding: 16px;
  }
  .profile-dialog .el-dialog__footer {
    padding: 12px 16px;
  }
}
</style>

<style scoped>
.profile-dialog__heading {
  display: flex;
  align-items: center;
  gap: 16px;
}
.profile-dialog__heading h2 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 20px;
  font-weight: 600;
  line-height: 1.4;
}
.profile-dialog__heading p {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.profile-dialog__active {
  flex-shrink: 0;
  margin-left: auto;
  padding: 4px 8px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.profile-dialog__footer-note {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.profile-dialog__footer-actions {
  display: flex;
  gap: 8px;
}
.profile-dialog__footer-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.profile-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.profile-form__section {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 20px;
  min-width: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
}
.profile-form__section-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}
.profile-form__section-heading h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.profile-form__section-heading > span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.profile-form__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 200px;
  gap: 16px;
}
.profile-form__field {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}
.profile-form__label {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 500;
}
.profile-form__label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.profile-form__label-row > .profile-form__label {
  font-size: 14px;
  font-weight: 600;
}
.profile-form__count {
  display: inline-flex;
  justify-content: center;
  min-width: 20px;
  padding: 1px 6px;
  color: var(--el-text-color-secondary);
  border-radius: 4px;
  background: var(--el-fill-color);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.profile-form__current,
.profile-form__help {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.profile-form__hint-icon {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}
.profile-form__key-row,
.profile-form__model-add {
  display: flex;
  align-items: center;
  gap: 8px;
}
.profile-form__key-row > .el-input,
.profile-form__model-add > .el-input,
.profile-form__model-select {
  flex: 1;
  min-width: 0;
  width: 100%;
}
.profile-form__option-label {
  float: left;
}
.profile-form__option-hint {
  float: right;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.profile-form__probe {
  margin: 4px 0;
}
.profile-form__probe-hint {
  font-size: 12px;
  line-height: 1.6;
}
.profile-form__window-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.profile-form__window-unit {
  margin-left: 4px;
  color: var(--el-text-color-placeholder);
}
.profile-form__models {
  display: flex;
  flex-direction: column;
  max-height: 180px;
  overflow-y: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  scrollbar-width: thin;
  scrollbar-color: var(--el-border-color) transparent;
}
.profile-form__models--chat {
  max-height: 360px;
}
.profile-form__model-row {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  gap: 12px;
  min-height: 44px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.profile-form__model-row:last-child {
  border-bottom: none;
}
.profile-form__model-row--chat {
  display: grid;
  grid-template-columns: minmax(100px, 1fr) 250px 56px 32px;
  column-gap: 12px;
  row-gap: 8px;
  padding: 12px;
}
.profile-form__model-name {
  flex: 1;
  min-width: 0;
  color: var(--el-text-color-primary);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.profile-form__model-window {
  display: grid;
  align-items: center;
  grid-template-columns: minmax(0, 1fr) 56px 32px;
  gap: 8px;
  min-width: 0;
}
.profile-form__model-window :deep(.el-input__inner) {
  font-variant-numeric: tabular-nums;
}
.profile-form__model-window-note {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  line-height: 1;
}
.profile-form__model-window-note--bad {
  color: var(--el-color-danger);
}
.profile-form__model-probe {
  width: 32px;
  min-height: 32px;
  padding: 0;
}
.profile-form__model-pick {
  flex-shrink: 0;
  display: flex;
  justify-content: flex-end;
  width: 56px;
}
.profile-form__model-actions {
  flex-shrink: 0;
}
.profile-form__model-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.profile-form__window-feedback {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.profile-form__window-source {
  flex-shrink: 0;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-size: 11px;
}
.profile-form__window-summary {
  overflow-wrap: anywhere;
}
.profile-form__window-feedback--failed {
  color: var(--el-color-danger);
}
.profile-form__window-details {
  margin-left: auto;
}
.profile-form__window-details summary {
  display: list-item;
  cursor: pointer;
  padding: 4px 0 4px 4px;
  color: var(--el-text-color-secondary);
}
.profile-form__window-details[open] {
  flex-basis: 100%;
  margin-left: 0;
}
.profile-form__window-explanation {
  margin-top: 4px;
  padding: 12px;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.profile-form__window-explanation p {
  margin: 4px 0;
}
.profile-form__window-explanation a {
  color: var(--el-color-primary);
  text-underline-offset: 3px;
}
.profile-form__temperature {
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.profile-form__temperature .profile-form__label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 500;
}
.profile-form__temperature-default {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.profile-form__inline {
  display: flex;
  align-items: center;
  gap: 10px;
}
.profile-form__slider {
  flex: 1;
  min-width: 0;
  padding: 0 4px;
}
.profile-form__temperature-value {
  flex: none;
  min-width: 38px;
  padding: 3px 7px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 5px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-lighter);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  text-align: center;
}
.profile-form__optional {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
}
.profile-form__optional-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 20px;
  cursor: pointer;
  list-style: none;
}
.profile-form__optional-heading::-webkit-details-marker {
  display: none;
}
.profile-form__optional-heading strong {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.profile-form__optional-heading small {
  display: block;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.profile-form__optional-state {
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.profile-form__optional-state > span {
  display: inline-block;
  margin-left: 8px;
}
.profile-form__optional[open] .profile-form__optional-state > span {
  transform: rotate(180deg);
}
.profile-form__optional-body {
  display: grid;
  gap: 24px;
  padding: 0 20px 20px;
}
@media (max-width: 700px) {
  .profile-form__model-row--chat {
    grid-template-columns: minmax(0, 1fr) 56px 32px;
    column-gap: 8px;
  }
  .profile-form__model-row--chat .profile-form__model-name {
    grid-column: 1 / -1;
  }
}
@media (max-width: 600px) {
  .profile-dialog__heading h2 {
    font-size: 18px;
  }
  .profile-dialog__heading {
    flex-wrap: wrap;
    gap: 8px;
  }
  .profile-dialog__active {
    margin-left: 0;
  }
  .profile-dialog__footer-note {
    display: none;
  }
  .profile-dialog__footer-actions {
    width: 100%;
    justify-content: flex-end;
  }
  .profile-form {
    gap: 16px;
  }
  .profile-form__section {
    padding: 14px;
    gap: 16px;
  }
  .profile-form__grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 16px;
  }
  .profile-form__section-heading > span {
    display: none;
  }
  .profile-form__model-row--chat {
    padding: 10px;
    column-gap: 6px;
  }
  .profile-form__model-row--chat .profile-form__model-name {
    grid-column: 1;
    grid-row: 1;
  }
  .profile-form__model-row--chat .profile-form__model-pick {
    grid-column: 2;
    grid-row: 1;
  }
  .profile-form__model-row--chat .profile-form__model-actions {
    grid-column: 3;
    grid-row: 1;
  }
  .profile-form__model-window {
    grid-column: 1 / -1;
    grid-row: 2;
    grid-template-columns: minmax(0, 1fr) 56px 44px;
    gap: 8px;
  }
  .profile-form__model-window :deep(.el-input) {
    height: 36px;
  }
  .profile-form__model-probe {
    width: 44px;
    min-height: 44px;
  }
  .profile-form__optional-heading {
    padding: 14px;
  }
  .profile-form__optional-body {
    padding: 0 14px 14px;
  }
  .profile-form__inline {
    gap: 8px;
  }
}
</style>
