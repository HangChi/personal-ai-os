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
import { QuestionFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { confirmDanger } from '@/lib/confirm-box'
import {
  MODEL_NAME_MAX,
  PROFILE_NAME_MAX,
  SOURCE_LABELS,
  SOURCE_TAG_TYPES,
  TEMPERATURE_MAX,
  TEMPERATURE_MIN,
  buildProfilePayload,
  emptyForm,
  sourceHint,
  toForm,
  validateProfileForm,
  type ModelProfileForm as FormShape,
} from '@/lib/model-profile'
import type { ProviderPreset } from '@/lib/provider-presets'
import type { ModelProfile, SaveModelProfilePayload } from '@/types/api'

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

const isEdit = computed(() => props.profile !== null)

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
    if (props.profile) {
      Object.assign(form, toForm(props.profile))
    } else {
      Object.assign(form, emptyForm(props.preset?.id ?? null))
      if (props.preset) {
        form.name = props.preset.name
        form.baseUrl = props.preset.baseUrl
        form.chatModel = props.preset.chatModel
        form.embeddingModel = props.preset.embeddingModel
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

/** 提交结束后由父组件调用，关闭对话框（失败时父组件不关） */
function finish(): void {
  submitting.value = false
}

defineExpose({ finish })
</script>

<template>
  <el-dialog
    v-model="visible"
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

      <!-- 对话模型 -->
      <label class="profile-form__field">
        <span class="profile-form__label">
          对话模型
          <template v-if="current">
            <el-tag size="small" :type="SOURCE_TAG_TYPES[current.sources.chatModel]">
              {{ SOURCE_LABELS[current.sources.chatModel] }}
            </el-tag>
          </template>
        </span>
        <el-input v-model="form.chatModel" placeholder="qwen3.8-flash" clearable :maxlength="MODEL_NAME_MAX" />
        <span class="profile-form__help">模型名须与端点支持的名称完全一致</span>
      </label>

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

      <!-- 嵌入模型 -->
      <label class="profile-form__field">
        <span class="profile-form__label">
          嵌入模型
          <template v-if="current">
            <el-tag size="small" :type="SOURCE_TAG_TYPES[current.sources.embeddingModel]">
              {{ SOURCE_LABELS[current.sources.embeddingModel] }}
            </el-tag>
          </template>
          <el-tag size="small" type="warning">需重启</el-tag>
        </span>
        <el-input
          v-model="form.embeddingModel"
          placeholder="qwen3.7-text-embedding-flash"
          clearable
          :maxlength="MODEL_NAME_MAX"
        />
        <span class="profile-form__help">
          知识库向量化用，可留空。改动需重启后端：向量表维度由建表语句固定，维度一变写入会失败。
        </span>
      </label>
    </div>

    <template #footer>
      <el-button :disabled="submitting" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">
        {{ isEdit ? '保存' : '添加' }}
      </el-button>
    </template>
  </el-dialog>
</template>

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
