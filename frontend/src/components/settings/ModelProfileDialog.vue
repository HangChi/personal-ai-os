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
  probeAlertType,
  probeBlockedReason,
  probeResultHint,
  probeResultSummary,
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
 * 下拉候选：去掉已经在清单里的。
 *
 * <p>不去掉的话，加完 `qwen-max` 之后下拉里还留着它，用户很容易再选一次
 * 然后疑惑「为什么没反应」。
 */
const chatModelOptions = computed<string[]>(() => remainingModels(availableModels.value, form.chatModels))
const embeddingModelOptions = computed<string[]>(() => remainingModels(availableModels.value, form.embeddingModels))

function remainingModels(candidates: string[], picked: string[]): string[] {
  if (candidates.length === 0) {
    return []
  }
  const taken = new Set(picked)
  return candidates.filter((name) => !taken.has(name))
}

function handleAddChatModel(value: string | null | undefined): void {
  form.chatModels = appendModel(form.chatModels, value)
  chatModelDraft.value = ''
}

function handleAddEmbeddingModel(value: string | null | undefined): void {
  form.embeddingModels = appendModel(form.embeddingModels, value)
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
    width="560px"
    :close-on-click-modal="false"
    append-to-body
  >
    <div class="profile-form">
      <!-- 名称 -->
      <label class="profile-form__field">
        <span class="profile-form__label">配置名称</span>
        <el-input
          v-model="form.name"
          placeholder="如：百炼主力 / DeepSeek 备用"
          clearable
          :maxlength="PROFILE_NAME_MAX"
          show-word-limit
        />
        <span class="profile-form__help">只用于在这张卡片上显示，随便起</span>
      </label>

      <!-- 端点 -->
      <label class="profile-form__field">
        <span class="profile-form__label">
          端点地址
          <template v-if="current">
            <el-tag size="small" :type="SOURCE_TAG_TYPES[current.sources.baseUrl]">
              {{ SOURCE_LABELS[current.sources.baseUrl] }}
            </el-tag>
            <el-tooltip :content="sourceHint(current.sources.baseUrl)" placement="top">
              <el-icon class="profile-form__hint-icon"><QuestionFilled /></el-icon>
            </el-tooltip>
          </template>
        </span>
        <el-input v-model="form.baseUrl" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" clearable />
        <span class="profile-form__help">OpenAI 兼容的根地址，不要带 /chat/completions。留空则用环境变量</span>
      </label>

      <!-- 密钥 -->
      <label class="profile-form__field">
        <span class="profile-form__label">
          API Key
          <template v-if="current">
            <el-tag size="small" :type="SOURCE_TAG_TYPES[current.sources.apiKey]">
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
        <span class="profile-form__help">出于安全不回填明文，只显示末 4 位。留空提交不会改动现有密钥。</span>
      </label>

      <!-- 协议 + 获取模型列表 -->
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
        <div class="profile-form__protocol-row">
          <el-select v-model="form.protocol" class="profile-form__protocol-select">
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
          <el-tooltip :content="probeBlocked ?? '按当前端点与密钥拉取该端点支持的模型'" placement="top">
            <!-- 用 span 包一层：disabled 的按钮不触发 mouseenter，tooltip 会失效 -->
            <span>
              <el-button :loading="probing" :disabled="probeBlocked !== null" @click="handleProbe">
                <el-icon v-if="!probing"><Download /></el-icon>
                获取模型列表
              </el-button>
            </span>
          </el-tooltip>
        </div>

        <!-- 探测结果：成功给数量，失败给原因并按语义选语气 -->
        <el-alert
          v-if="probeResult"
          class="profile-form__probe"
          :type="probeAlertType(probeResult)"
          :closable="false"
          show-icon
          :title="probeResultSummary(probeResult)"
        >
          <span class="profile-form__probe-hint">{{ probeResultHint(probeResult) }}</span>
        </el-alert>
      </div>

      <!-- 对话模型清单 -->
      <div class="profile-form__field">
        <span class="profile-form__label">
          对话模型
          <template v-if="current">
            <el-tag size="small" :type="SOURCE_TAG_TYPES[current.sources.chatModel]">
              {{ SOURCE_LABELS[current.sources.chatModel] }}
            </el-tag>
          </template>
        </span>
        <div class="profile-form__models">
          <div v-if="form.chatModels.length === 0" class="profile-form__models-empty">
            还没有模型 —— 点「获取模型列表」挑几个，或直接手动输入
          </div>
          <div v-for="(name, index) in form.chatModels" :key="`chat-${index}-${name}`" class="profile-form__model-row">
            <span class="profile-form__model-name">{{ name }}</span>
            <el-tag v-if="index === 0" size="small" type="success">默认</el-tag>
            <span class="profile-form__model-actions">
              <el-button
                link
                :disabled="index === 0"
                title="设为默认（挪到第一位）"
                @click="form.chatModels = moveModel(form.chatModels, index, 0)"
              >
                设为默认
              </el-button>
              <el-button link type="danger" @click="removeModel('chat', index)">移除</el-button>
            </span>
          </div>
        </div>
        <!--
          有探测结果时用「可输入的下拉」加入清单，没有时用普通输入框。
          不做成纯下拉：很多端点拿不到列表，纯下拉会让这些用户完全无法配置。
        -->
        <div class="profile-form__model-add">
          <el-select
            v-if="availableModels.length > 0"
            v-model="chatModelDraft"
            class="profile-form__model-select"
            filterable
            allow-create
            default-first-option
            clearable
            :reserve-keyword="false"
            placeholder="从列表里选，或直接输入模型名"
            @change="handleAddChatModel"
          >
            <el-option v-for="name in chatModelOptions" :key="name" :value="name" :label="name" />
          </el-select>
          <el-input
            v-else
            v-model="chatModelDraft"
            placeholder="qwen3.8-flash"
            clearable
            :maxlength="MODEL_NAME_MAX"
            @keyup.enter="handleAddChatModel(chatModelDraft)"
          />
          <el-button :disabled="chatModelDraft.trim().length === 0" @click="handleAddChatModel(chatModelDraft)">
            加入
          </el-button>
        </div>
        <span class="profile-form__help"> 可以加多个，第一个就是默认模型。每个会话还能单独选用哪个。 </span>
      </div>

      <!-- 温度 -->
      <div class="profile-form__field">
        <span class="profile-form__label">
          温度
          <template v-if="current">
            <el-tag size="small" :type="SOURCE_TAG_TYPES[current.sources.temperature]">
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
          <el-button v-if="form.temperature !== null" link @click="form.temperature = null"> 用默认 </el-button>
        </div>
        <span class="profile-form__help">越低越确定、越高越发散；「用默认」表示交给服务端</span>
      </div>

      <!-- 嵌入模型清单 -->
      <div class="profile-form__field">
        <span class="profile-form__label">
          嵌入模型
          <template v-if="current">
            <el-tag size="small" :type="SOURCE_TAG_TYPES[current.sources.embeddingModel]">
              {{ SOURCE_LABELS[current.sources.embeddingModel] }}
            </el-tag>
          </template>
          <el-tag size="small" type="warning">换模型需重建向量</el-tag>
        </span>
        <div class="profile-form__models">
          <div v-if="form.embeddingModels.length === 0" class="profile-form__models-empty">
            还没有嵌入模型（可留空，知识库功能将不可用）
          </div>
          <div
            v-for="(name, index) in form.embeddingModels"
            :key="`embed-${index}-${name}`"
            class="profile-form__model-row"
          >
            <span class="profile-form__model-name">{{ name }}</span>
            <el-tag v-if="index === 0" size="small" type="success">默认</el-tag>
            <span class="profile-form__model-actions">
              <el-button
                link
                :disabled="index === 0"
                title="设为默认（挪到第一位）"
                @click="form.embeddingModels = moveModel(form.embeddingModels, index, 0)"
              >
                设为默认
              </el-button>
              <el-button link type="danger" @click="removeModel('embedding', index)">移除</el-button>
            </span>
          </div>
        </div>
        <div class="profile-form__model-add">
          <el-select
            v-if="availableModels.length > 0"
            v-model="embeddingModelDraft"
            class="profile-form__model-select"
            filterable
            allow-create
            default-first-option
            clearable
            :reserve-keyword="false"
            placeholder="从列表里选，或直接输入模型名"
            @change="handleAddEmbeddingModel"
          >
            <el-option v-for="name in embeddingModelOptions" :key="name" :value="name" :label="name" />
          </el-select>
          <el-input
            v-else
            v-model="embeddingModelDraft"
            placeholder="qwen3.7-text-embedding-flash"
            clearable
            :maxlength="MODEL_NAME_MAX"
            @keyup.enter="handleAddEmbeddingModel(embeddingModelDraft)"
          />
          <el-button
            :disabled="embeddingModelDraft.trim().length === 0"
            @click="handleAddEmbeddingModel(embeddingModelDraft)"
          >
            加入
          </el-button>
        </div>
        <span class="profile-form__help">
          知识库向量化用，可留空。**换嵌入模型会让已有向量全部失效**（维度可能变化）， 需要重建知识库索引才能继续检索。
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
 * 底边到 829）。限制整体到 84vh 连这笔上边距一起兜住。
 */
.profile-dialog {
  max-height: 84vh;
  display: flex;
  flex-direction: column;
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
  gap: var(--os-space-4);
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

.profile-form__key-row {
  display: flex;
  gap: var(--os-space-2);
}

.profile-form__key-row :deep(.el-input) {
  flex: 1;
}

/*
 * 协议行：下拉占满剩余宽度，按钮固定靠右。
 *
 * 下拉不写死宽度：`「Anthropic」` 比 `「Ollama」` 宽，写死会让长标签被截断，
 * 而协议名恰恰是这个下拉唯一的辨识信息。
 */
.profile-form__protocol-row {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
}

.profile-form__protocol-select {
  flex: 1;
  min-width: 0;
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
 */
.profile-form__models {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-lighter);
  overflow: hidden;
}

.profile-form__models-empty {
  padding: var(--os-space-3);
  font-size: var(--os-font-xs);
  line-height: 1.5;
  text-align: center;
  color: var(--el-text-color-placeholder);
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

/* 加入行：输入/下拉占满剩余宽度，按钮固定靠右 */
.profile-form__model-add {
  display: flex;
  gap: var(--os-space-2);
}

.profile-form__model-add :deep(.el-select) {
  flex: 1;
  min-width: 0;
}

.profile-form__model-add :deep(.el-input) {
  flex: 1;
  min-width: 0;
}

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
