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
import { Download, QuestionFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { probeModelList } from '@/api/model-profile'
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
  moveModel,
  modelCandidatesFor,
  modelOptionsFor,
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
    })
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

function handleAddChatModel(): void {
  form.chatModels = appendModel(form.chatModels, chatModelDraft.value)
  chatModelDraft.value = ''
}

function handleAddEmbeddingModel(): void {
  form.embeddingModels = appendModel(form.embeddingModels, embeddingModelDraft.value)
  embeddingModelDraft.value = ''
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
function removeModel(kind: 'chat' | 'embedding', index: number): void {
  if (kind === 'chat') {
    form.chatModels = form.chatModels.filter((_, i) => i !== index)
    return
  }
  form.embeddingModels = form.embeddingModels.filter((_, i) => i !== index)
}

/** 提交结束后由父组件调用，关闭对话框（失败时父组件不关） */
function finish(): void {
  submitting.value = false
}

defineExpose({ finish })
</script>

<template>
  <el-dialog
    v-model="visible"
    class="profile-dialog"
    :title="isEdit ? '编辑模型配置' : '添加模型配置'"
    width="640px"
    :close-on-click-modal="false"
    append-to-body
  >
    <div class="profile-form">
      <!--
        连接信息：名称与协议并排两列。
        两者都短（名称十几字、协议选一个），各自独占一行时 640px 里右边
        全是空的白 —— 7 个字段一列排下来，整张表单像被拉长的便签。
      -->
      <div class="profile-form__grid">
        <!-- 名称 -->
        <label class="profile-form__field">
          <span class="profile-form__label">配置名称</span>
          <el-input
            v-model="form.name"
            placeholder="如：百炼主力"
            clearable
            :maxlength="PROFILE_NAME_MAX"
            show-word-limit
          />
          <!--
            这里原来有一句「仅用于在这张卡片上显示」。
            删掉的理由：placeholder 已经是「如：百炼主力」这种示例，
            用户看一眼就知道这只是个标签；再说一遍既没信息量，
            又让 7 个字段各带一行灰字，整张表单显得啰嗦。
            省下的 26px（一行 18px + 8px 间距）直接还给了内容区高度。
          -->
        </label>

        <!-- 协议 -->
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
          <!--
            不放说明文字：`只影响拉取模型列表` 与标题上那个 ? 的 tooltip
            讲的是同一件事（tooltip 还更完整）。而且这一格与「配置名称」
            同属网格的一行，行高由较高者决定 —— 留着它，即使名称那列
            没有说明，整行仍然被撑到 86px。
          -->
        </div>
      </div>

      <!-- 端点 -->
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

      <!-- 密钥 -->
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
            <span class="profile-form__current"> 当前：{{ current.hasApiKey ? current.apiKeyMasked : '未设置' }} </span>
          </template>
        </span>
        <div class="profile-form__key-row">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="pendingClearKey ? '待清除（提交后回落环境变量）' : '留空表示不修改'"
          />
          <el-button v-if="current" :disabled="!current.hasApiKey || pendingClearKey" @click="handleClearKey">
            清除
          </el-button>
        </div>
        <!--
          原来这里还有一句「留空提交不会改动现有密钥」，与 placeholder
          「留空表示不修改」几乎同义；加上「当前：****LKmg」，同一件事说了三遍。
          只留「不回填明文」这条用户真正不知道的信息。
        -->
        <span class="profile-form__help">出于安全不回填明文，只显示末 4 位</span>
      </label>

      <!-- 对话模型清单 -->
      <div class="profile-form__field">
        <!--
          「获取模型列表」放在这一块的标题行，不再挂在「协议」下面。
          它拉回来的东西只服务这两个模型清单，放在协议旁边时用户看不出
          按钮与下面清单的关系（改版前它在协议行，点完清单出现在两屏之外）。
          顺带把协议行腾出来，让协议能与名称并排成两列。
        -->
        <div class="profile-form__label-row">
          <span class="profile-form__label">
            对话模型
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
            <!-- 用 span 包一层：disabled 的按钮不触发 mouseenter，tooltip 会失效 -->
            <span>
              <el-button size="small" :loading="probing" :disabled="probeBlocked !== null" @click="handleProbe">
                <el-icon v-if="!probing"><Download /></el-icon>
                获取模型列表
              </el-button>
            </span>
          </el-tooltip>
        </div>

        <!--
          探测结果：成功只报数量，失败给原因 + 怎么修。

          成功时**不显示 hint 那一行**：原来标题「获取到 183 个模型」下面还有
          一句「下方的对话模型/嵌入模型已可从这些模型里挑选」，而下拉框的
          placeholder 本来就写着「从列表里选，或直接输入模型名」—— 同一件事
          说了两遍，代价是这条提示从 34px 变成 62px。
          实测这 28px 刚好把「嵌入模型」整块挤到可视区外（body 769px、
          内容 860px），用户点完「获取模型列表」就看丢了一个字段。
        -->
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

        <!--
          清单为空时**整块不渲染**，只留下面的输入行。
          原来空着时画一个虚线框、里面写「还没有模型 —— 点获取模型列表…」，
          结果虚框正下方就是已经填好值的输入框，两句话挨着说要打架
          （视觉复核原话：「提示语和已填内容挨得很近，读起来有自相矛盾的错觉」）。
          空清单本身不需要占位：输入行就是这个功能唯一的入口。
        -->
        <div v-if="form.chatModels.length > 0" class="profile-form__models">
          <div v-for="(name, index) in form.chatModels" :key="`chat-${index}-${name}`" class="profile-form__model-row">
            <span class="profile-form__model-name">{{ name }}</span>
            <!--
              第一行放「默认」徽章、不放「设为默认」按钮：
              已经是默认了还显示「设为默认」，既是冗余也是死操作。
              两者放在同一个**定宽**容器里，逐行切换内容而容器宽度不变 ——
              否则「移除」这一列会随行左右跳动（实测首行徽章在 x=927、
              其余行被「设为默认」挤到 x=869，差 58px，多行叠起来就是视觉上的锯齿）。
            -->
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
          </div>
        </div>
        <!--
          有**本类的**候选时用「可输入的下拉」，没有时用普通输入框。
          判据是 `chatModelOptions` 而不是 `availableModels`：后者是端点返回的
          全部模型（含嵌入/语音/图像），非空不代表这里有对话模型可用。
          不做成纯下拉：很多端点拿不到列表，纯下拉会让这些用户完全无法配置。
          两条路径都只是填草稿，点「加入」才真正进清单。
        -->
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
            placeholder="qwen3.8-flash"
            clearable
            :maxlength="MODEL_NAME_MAX"
            @keyup.enter="handleAddChatModel"
          />
          <el-button :disabled="!canAddChatModel" @click="handleAddChatModel">加入</el-button>
        </div>
        <span class="profile-form__help"> 可以加多个，第一个是默认模型。每个会话还能单独选用哪个。 </span>
      </div>

      <!-- 温度 -->
      <div class="profile-form__field">
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
        <div class="profile-form__inline">
          <el-slider
            v-model="form.temperature"
            :min="TEMPERATURE_MIN"
            :max="TEMPERATURE_MAX"
            :step="0.1"
            show-input
            :show-input-controls="false"
            class="profile-form__slider"
          />
          <el-button v-if="form.temperature !== null" link size="small" @click="form.temperature = null">
            用默认
          </el-button>
        </div>
        <span class="profile-form__help">越低越确定、越高越发散；「用默认」表示交给服务端</span>
      </div>

      <!-- 嵌入模型清单 -->
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
        <!--
          判据是 `embeddingModelOptions`（已按用途过滤），不是 `availableModels`。
          百炼端点返回 183 个模型、其中只有 2 个嵌入模型，用原始列表判断
          「有候选」会得到一个塞满对话模型的下拉；而某些端点只提供对话模型，
          过滤后为空就该切回手输，而不是给一个空下拉。
        -->
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
            placeholder="qwen3.7-text-embedding-flash"
            clearable
            :maxlength="MODEL_NAME_MAX"
            @keyup.enter="handleAddEmbeddingModel"
          />
          <el-button :disabled="!canAddEmbeddingModel" @click="handleAddEmbeddingModel">加入</el-button>
        </div>
        <!--
          「换模型会让已有向量失效」这条警告不做成标签挂在标题行：它是一段
          需要解释的后果，一行短标签只能写下结论、写不下原因，而原因
          （维度变化 → 必须重建索引）恰恰是用户判断「我现在能不能换」的依据。

          **这里始终只占一行**，两种文案二选一而不是两条并列：
          探测到该端点没有嵌入模型时，说「没有嵌入模型」比说「换模型需重建索引」
          有用得多（后者对一个压根配不上嵌入模型的端点毫无意义）；
          而两条都显示会占 36px，实测正是这 18px 让整个字段被挤出可视区。
        -->
        <span v-if="showNoEmbeddingHint" class="profile-form__help profile-form__help--muted">
          获取到的 {{ availableModels.length }} 个模型里没有嵌入模型（对话模型不能用于向量化），确实需要就手动输入。
        </span>
        <span v-else class="profile-form__help">
          知识库向量化用，可留空。换嵌入模型会让向量失效（维度可能变），需重建索引。
        </span>
      </div>
    </div>

    <template #footer>
      <el-button :disabled="submitting" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">
        {{ isEdit ? '保存' : '添加' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<!--
  非 scoped 的样式块：弹窗带 `append-to-body`，EP 会把 `.el-dialog` 整块
  移动到 `<body>` 下，移动后宿主元素上没有任何 `data-v-xxx` 属性
  （实测 `.el-dialog` 的 data-v 属性列表为空）。因此 scoped 里写
  `.profile-dialog :deep(.el-dialog__body)` 编译出的
  `.profile-dialog[data-v-xxx] .el-dialog__body` 永远匹配不上，
  规则会静默失效（`max-height` 实测仍是 `none`，底部的「保存」
  在 800px 高的屏幕上落到 y=1021，完全点不到）。

  作用范围靠 `.profile-dialog` 这个只在当前组件用的一次性类名限定，
  不影响页面上其它弹窗。
-->
<style>
/*
 * 高度上限：表单从 5 个字段长到 8 个（多了两个模型清单），紧凑屏幕下
 * 会超过视口 —— 实测 800px 高时弹窗 917px，底部「保存」按钮落在
 * y=1021，**完全点不到**。
 *
 * 做法是限制整块对话框的高度、让中间的表单区自己滚动，头部标题与
 * 底部按钮始终可见。
 *
 * 为什么用 `.el-dialog` 而不是 `.el-dialog__body` 限高：
 * EP 的 `.el-dialog` 默认 `margin-top: 15vh`，只压 body 的话
 * 「15vh + 头部 + body + 底部」仍会顶出视口（实测 800px 屏幕下
 * 底边到 829）。限制整体高度连这笔上边距一起兜住。
 */
.profile-dialog {
  display: flex;
  flex-direction: column;
}

/*
 * 上边距比 EP 默认的 15vh 收紧到 7vh。
 *
 * 15vh 是给「一行标题 + 一点内容」的轻量弹窗设计的；这张表单有 7 个字段，
 * 上限再叠 15vh 边距就顶到屏幕底边、底部按钮贴着状态栏。
 * 收紧边距把省下的 7vh 还给内容区。
 *
 * 上限 91vh：与 7vh 边距相加 98vh，1000px 与 640px 高的屏都留得住余量
 * （实测 800/700/640 视口下 footer 底边 752/656/598，均 ≤ 视口）。
 * 为什么不再往上抬：探测成功后多出一条 40px 的结果提示条，内容会比
 * 无提示时高 —— 抬到 91vh 后仍有约 23px 滚动，但滚动条常显，
 * 且被滚到的只是最底部那段说明，不影响「点哪儿加模型」。
 *
 * 加 `html` 前缀：EP 的 `.el-dialog` 是 0-1-0，本规则若也是 0-1-0
 * 就要靠样式注入顺序决胜（unplugin 注入晚于 theme.css，但早于/晚于
 * EP 的运行时样式并不稳定）。抬到 0-1-1 直接压过。
 */
html .profile-dialog {
  margin-top: 7vh;
  max-height: 91vh;
}

.profile-dialog .el-dialog__body {
  /* flex:1 + min-height:0 才能让中间这块在 flex 列里真正收缩并出现滚动条 */
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  /*
   * 滚动条常显（不用 overlay 自动隐藏）：macOS 的 overlay 滚动条只在
   * 滚动瞬间出现，用户打开弹窗时看到的是一个「被硬切掉的」嵌入模型栏，
   * 无从知道下面还有内容。实测连视觉复核都把这种情况读成「内容被裁切」。
   * 常显的细滚动条把「还能往下滚」变成视觉事实。
   */
  scrollbar-width: thin;
  scrollbar-color: var(--el-border-color) transparent;
}

/*
 * `.el-dialog__header` / `__footer` 默认 `flex-shrink:1`，
 * 空间不足时它们会被压扁（标题行变窄、按钮被切）。
 * 固定为 0，让「该滚的那块滚、不该滚的不缩」。
 */
.profile-dialog .el-dialog__header,
.profile-dialog .el-dialog__footer {
  flex-shrink: 0;
}
</style>

<style scoped>
.profile-form {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-3);
}

/*
 * 两列网格：名称 + 协议。
 *
 * 两者都很短，各自独占一行时 640px 宽里右边有一大片空白。
 * 用 `1fr 200px` 而不是 `1fr 1fr`：协议下拉只有「OpenAI 兼容 / Anthropic /
 * Ollama」三个选项，跟名称等宽纯属浪费。
 */
.profile-form__grid {
  display: grid;
  grid-template-columns: 1fr 200px;
  gap: var(--os-space-3);
  align-items: start;
}

.profile-form__field {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
  min-width: 0;
}

.profile-form__label {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  flex-wrap: wrap;
  font-size: var(--os-font-sm);
  font-weight: 500;
  color: var(--el-text-color-primary);
}

.profile-form__current {
  font-size: var(--os-font-xs);
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.profile-form__hint-icon {
  color: var(--el-text-color-placeholder);
  cursor: help;
  vertical-align: -1px;
}

.profile-form__help {
  font-size: var(--os-font-xs);
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

/* 比常规说明更弱一档：它是「补充解释」，不是必须读的操作指引 */
.profile-form__help--muted {
  color: var(--el-text-color-placeholder);
}

.profile-form__key-row {
  display: flex;
  gap: var(--os-space-2);
}

.profile-form__key-row :deep(.el-input) {
  flex: 1;
}

.profile-form__label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-2);
  min-height: var(--os-button-height);
}

.profile-form__option-label {
  margin-right: var(--os-space-2);
}

/*
 * 下拉项里的接口说明：小一号、次要色。
 * 它比协议名长得多，必须能折行 —— 否则会把下拉撑得比对话框还宽。
 */
.profile-form__option-hint {
  display: block;
  font-size: var(--os-font-xs);
  line-height: 1.4;
  color: var(--el-text-color-secondary);
  white-space: normal;
}

.profile-form__probe {
  margin-top: var(--os-space-1);
}

/*
 * 提示条文字改用正文色，不用 EP 的语义彩色。
 *
 * EP 的 is-light 提示条把整块染成语义色（`.el-alert--success.is-light { color: ... }`，
 * 特异性 0-2-0），标题自己没有 color 规则，于是继承它。浅色底上只有
 * 2.96:1（成功）/ 2.85:1（警告），远低于 AA 的 4.5:1，长句子很难读。
 * 语义由左侧图标 + 底色 + 描边承载就够了，文字回到正文色（7.1:1）。
 *
 * 为什么写成 `.profile-form__probe :deep(.el-alert__title)`（**平铺、不嵌套**）：
 * ① 必须 `:deep()` —— SFC 是 `scoped`，不穿透的话 `.el-alert__title` 会被编译成
 *    `.el-alert__title[data-v-x]`，而 EP 内部元素没有本组件的 data-v，永远不匹配。
 * ② 宿主与 `:deep()` 必须写在**同一条平铺规则**里。写成嵌套的
 *    `.profile-form__probe { :deep(.el-alert__title) {} }`，Vue 编译后会把宿主
 *    整个丢掉、只留 `[data-v-x] .el-alert__title`（实测产物如此），特异性掉到 0-2-0，
 *    与 EP 的 `.el-alert--success.is-light`（0-2-0）打平 —— EP 后加载，改色被静默吃掉。
 *    症状是「正文改成功、标题纹丝不动」（正文那条本来就有自己的 `__description`
 *    规则参与竞争，标题则纯靠继承）。平铺后编译成
 *    `.profile-form__probe[data-v-x] .el-alert__title`，0-3-0，稳过。
 */
.profile-form__probe :deep(.el-alert__title) {
  color: var(--el-text-color-primary);
}

.profile-form__probe :deep(.el-alert__content) {
  color: var(--el-text-color-regular);
}

.profile-form__probe-hint {
  display: block;
  font-size: var(--os-font-xs);
  line-height: 1.5;
  color: var(--el-text-color-regular);
}

.profile-form__model-select {
  width: 100%;
}

/*
 * 模型清单：一行一个，等宽行高。
 *
 * 不用 `<el-tag closable>` 堆成一个流式标签墙：清单是**有序**的（第一项就是默认），
 * 而标签墙会折行、看不出顺序。逐行列表还能给第一项挂「默认」徽章、
 * 给每行挂「设为默认 / 移除」两个动作。
 *
 * 限高 + 内部滚动：一个端点可能配十几二十个模型，清单不限高的话
 * 「对话模型」这一块能把整个弹窗撑到远超视口，而它下面的温度与嵌入模型
 * 就永远在滚动区最深处（实测 5 个模型时内容已 884px > 可视 719px）。
 * 限高后清单自己滚，表单整体高度不随模型数量增长。
 */
.profile-form__models {
  display: flex;
  flex-direction: column;
  max-height: 148px;
  overflow-y: auto;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-lighter);
  scrollbar-width: thin;
  scrollbar-color: var(--el-border-color) transparent;
}

/*
 * 行高用 `--os-button-height` 兜住动作按钮（link 按钮仍是 32px 高）。
 * 不按文字高度算：`font-size: 13px` 单行看起来只要 22px，
 * 但里面嵌了 `<el-button link>`，实测会把行撑到 32px 并顶出边框。
 */
.profile-form__model-row {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  min-height: var(--os-button-height);
  padding: 0 var(--os-space-2) 0 var(--os-space-3);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.profile-form__model-row:last-child {
  border-bottom: none;
}

/*
 * 模型名可能很长（`qwen3.8-text-embedding-flash-2025-09-01`），
 * 必须能省略而不是把「移除」按钮挤到框外。
 * `flex: 1` + `min-width: 0` 是让 ellipsis 生效的必要组合。
 */
.profile-form__model-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: var(--os-font-sm);
  color: var(--el-text-color-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-form__model-actions {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: var(--os-space-1);
}

/*
 * 「设为默认」与「移除」之间的实际间距会变成 12px —— 必须清掉 margin。
 *
 * 全局有 `.el-button + .el-button { margin-left: 8px }`（见 theme.css），
 * 与这里的 `gap: 4px` 叠加成 12px；而徽章到「设为默认」只有 4px。
 * 同一行里两个间距差三倍，多行叠起来右半部分就是「锯齿」
 * （实测 gaps: [12]，视觉复核也把它列进了「最丑的三处」）。
 */
.profile-form__model-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

/*
 * 「默认」徽章 / 「设为默认」按钮的共用定宽容器。
 *
 * 两者不会同时出现（首行是徽章，其余行是按钮），但**容器宽度必须固定**：
 * 不定宽的话「移除」这一列会随行左右跳动 —— 实测首行徽章 52px、
 * 其余行按钮 54px，加上间距差异，「移除」按钮位置能差 58px。
 *
 * 宽度取按钮的自然宽度再加 2px 余量。
 *
 * `justify-content: flex-end` 而不是 center：徽章 52px、按钮 54px，
 * 居中的话两者右边缘差 1px，而「移除」是紧贴容器右侧放的 ——
 * 于是每一行「徽章/按钮 → 移除」之间的留白都不等于 `gap`，
 * 多行叠起来就是视觉复核反复提到的「锯齿」。右对齐让这条留白恒定。
 */
.profile-form__model-pick {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: flex-end;
  width: 56px;
}

/* 加入行：输入/下拉占满剩余宽度，按钮固定靠右 */
.profile-form__model-add {
  display: flex;
  gap: var(--os-space-2);
  align-items: center;
}

/*
 * 「加入」按钮与输入框等高。
 *
 * 输入框实测 34px（`--el-component-size`），按钮 32px（`--os-button-height`），
 * 差 2px —— `align-items: center` 会把按钮居中，看着是「飘」在输入框里的，
 * 上边距 1px、下边距 1px，比两侧的 8px gap 更显眼。
 * 显式拉平到输入框高度，让两者成为一条齐平的横线。
 *
 * 不用 `:deep()`：`el-button` 是模板里直接写的元素，根部会带上本组件的
 * `data-v`，选择器自然命中。
 */
.profile-form__model-add > .el-button {
  height: var(--el-component-size);
}

.profile-form__model-add :deep(.el-select) {
  flex: 1;
  min-width: 0;
}

.profile-form__model-add :deep(.el-input) {
  flex: 1;
  min-width: 0;
}

/*
 * 温度行：滑块占满，末尾的「用默认」固定靠右。
 *
 * 视觉复核曾说「这一行右端短一截、与其它行不齐」。实测是误判：
 * `.el-slider` 的数字框停在 975、按钮到 1024，与「对话模型」里
 * 「加入」输入框停在 956、按钮到 1024 是**同一种结构** ——
 * 每行都是「内容占满 + 按钮收在右边线」，右边界一致。
 * 数字框停在 975 只因 EP 把 `show-input` 的宽度写死成 130px，
 * 它属于滑块控件内部，不是一个独立的右对齐元素。
 *
 * 不改 EP 的内部宽度：`show-input` 的数字框是滑块的一部分，
 * 拉开它只会让「滑块 + 数字框」这组控件内部出现空洞。
 */
.profile-form__inline {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
}

.profile-form__slider {
  flex: 1;
  min-width: 0;
}
</style>
