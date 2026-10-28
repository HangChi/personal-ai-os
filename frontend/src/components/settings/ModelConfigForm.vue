<script setup lang="ts">
/**
 * 模型配置表单。
 *
 * <p>接 {@code config} 作为**必填 prop**，自身不负责加载。这样拆是为了让
 * 模板里的 `config` 在类型上恒为非空——否则 vue-tsc 无法把
 * `v-if="config"` 的缩窄带进子元素，每个字段访问都要写一次非空断言，
 * 而断言恰恰会把「忘了判空」这类真 bug 一起吞掉。
 *
 * <p>父组件 {@link ModelConfigSection} 负责读取、失败重试、加载态。
 */
import { QuestionFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { saveModelConfig, testModelConnection } from '@/api/model-config'
import {
  MODEL_NAME_MAX,
  SOURCE_LABELS,
  SOURCE_TAG_TYPES,
  TEMPERATURE_MAX,
  TEMPERATURE_MIN,
  apiKeyDisplay,
  buildSavePayload,
  isDirty,
  sourceHint,
  testResultSummary,
  toForm,
  validateForm,
  type ModelConfigForm,
} from '@/lib/model-config'
import type { ModelConfig, ModelConnectionTestResult } from '@/types/api'

const props = defineProps<{
  /** 当前生效的配置；父组件保证非空 */
  config: ModelConfig
}>()

const emit = defineEmits<{
  /** 保存成功（或保存后回读），携带最新生效配置 */
  (e: 'saved', config: ModelConfig): void
  /** 需要父组件重新拉取（保存失败但配置可能已落库） */
  (e: 'reload'): void
}>()

const saving = ref(false)
const testing = ref(false)
const testResult = ref<ModelConnectionTestResult | null>(null)

const form = reactive<ModelConfigForm>(toForm(props.config))

/** 用户是否点了「清除密钥」（尚未保存） */
const pendingClearKey = ref(false)

/**
 * 外部配置变化时重置表单。
 *
 * <p>只在 `config` 引用变化时触发（父组件每次拉取都会产生新对象）。
 * 不做深比较：保存后要的是「以服务端为准」，而不是「保留用户草稿」。
 */
watch(
  () => props.config,
  (next) => {
    Object.assign(form, toForm(next))
    pendingClearKey.value = false
    testResult.value = null
  },
)

const dirty = computed(() => pendingClearKey.value || isDirty(form, props.config))

/** 保存按钮的禁用原因；空串表示可保存 */
const saveDisabledReason = computed(() => (dirty.value ? '' : '没有改动'))

async function handleSave(): Promise<void> {
  const invalid = validateForm(form)
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  saving.value = true
  try {
    const saved = await saveModelConfig(buildSavePayload(form, pendingClearKey.value))
    emit('saved', saved)
    ElMessage.success('已保存并生效')
  } catch (cause) {
    // 这里有个容易误判的情形：后端「配置已保存，但按该配置初始化模型失败」
    // 会返回错误，而配置**确实已经落库**。所以除了报错，还要让父组件重新拉取，
    // 使界面显示真实的当前值，而不是停留在用户的草稿上。
    ElMessage.error(cause instanceof Error ? cause.message : '保存失败')
    emit('reload')
  } finally {
    saving.value = false
  }
}

async function handleTest(): Promise<void> {
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await testModelConnection()
  } catch (cause) {
    testResult.value = {
      ok: false,
      latencyMs: 0,
      model: null,
      replyPreview: null,
      error: cause instanceof Error ? cause.message : '测试请求失败',
    }
  } finally {
    testing.value = false
  }
}

async function handleClearKey(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将删除设置页保存的 API Key，之后回落到 .env 里的 OPENAI_API_KEY。若环境变量也没有密钥，所有对话将不可用。',
      '清除 API Key',
      { type: 'warning', confirmButtonText: '清除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  pendingClearKey.value = true
  form.apiKey = ''
  ElMessage.info('提交保存后生效')
}
</script>

<template>
  <div class="model-form">
    <div class="model-form__grid">
      <!-- 端点 -->
      <label class="model-form__field model-form__field--wide">
        <span class="model-form__label">
          端点地址
          <el-tag size="small" :type="SOURCE_TAG_TYPES[config.sources.baseUrl]">
            {{ SOURCE_LABELS[config.sources.baseUrl] }}
          </el-tag>
          <el-tooltip :content="sourceHint(config.sources.baseUrl)" placement="top">
            <el-icon class="model-form__hint-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
        </span>
        <el-input v-model="form.baseUrl" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" clearable />
        <span class="model-form__help">OpenAI 兼容的根地址，不要带 /chat/completions</span>
      </label>

      <!-- 密钥 -->
      <label class="model-form__field model-form__field--wide">
        <span class="model-form__label">
          API Key
          <el-tag size="small" :type="SOURCE_TAG_TYPES[config.sources.apiKey]">
            {{ SOURCE_LABELS[config.sources.apiKey] }}
          </el-tag>
          <span class="model-form__current">当前：{{ apiKeyDisplay(config) }}</span>
        </span>
        <div class="model-form__key-row">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="pendingClearKey ? '待清除（保存后回落环境变量）' : '留空表示不修改'"
          />
          <el-button :disabled="!config.hasApiKey || pendingClearKey" @click="handleClearKey"> 清除 </el-button>
        </div>
        <span class="model-form__help"> 出于安全考虑不回填明文，只显示末 4 位。留空提交不会改动现有密钥。 </span>
      </label>

      <!-- 对话模型 -->
      <label class="model-form__field">
        <span class="model-form__label">
          对话模型
          <el-tag size="small" :type="SOURCE_TAG_TYPES[config.sources.chatModel]">
            {{ SOURCE_LABELS[config.sources.chatModel] }}
          </el-tag>
        </span>
        <el-input v-model="form.chatModel" placeholder="qwen3.8-flash" clearable />
        <span class="model-form__help">模型名须与端点支持的名称一致</span>
      </label>

      <!-- 温度 -->
      <div class="model-form__field">
        <span class="model-form__label">
          温度
          <el-tag size="small" :type="SOURCE_TAG_TYPES[config.sources.temperature]">
            {{ SOURCE_LABELS[config.sources.temperature] }}
          </el-tag>
        </span>
        <div class="model-form__inline">
          <el-slider
            v-model="form.temperature"
            :min="TEMPERATURE_MIN"
            :max="TEMPERATURE_MAX"
            :step="0.1"
            show-input
            :show-input-controls="false"
            class="model-form__slider"
          />
          <el-button v-if="form.temperature !== null" link @click="form.temperature = null"> 用默认 </el-button>
        </div>
        <span class="model-form__help">越低越确定、越高越发散；「用默认」表示交给服务端</span>
      </div>

      <!-- 嵌入模型 -->
      <label class="model-form__field model-form__field--wide">
        <span class="model-form__label">
          嵌入模型
          <el-tag size="small" :type="SOURCE_TAG_TYPES[config.sources.embeddingModel]">
            {{ SOURCE_LABELS[config.sources.embeddingModel] }}
          </el-tag>
          <el-tag v-if="!config.embeddingHotReloadable" size="small" type="warning">需重启</el-tag>
        </span>
        <el-input
          v-model="form.embeddingModel"
          placeholder="qwen3.7-text-embedding-flash"
          clearable
          :maxlength="MODEL_NAME_MAX"
        />
        <span class="model-form__help">
          知识库向量化用。改动需重启后端：向量表维度由建表语句固定，维度一变写入会失败。
        </span>
      </label>
    </div>

    <!-- 操作区 -->
    <div class="model-form__actions">
      <el-button :disabled="!dirty" :loading="saving" type="primary" @click="handleSave"> 保存并生效 </el-button>
      <el-tooltip content="测试的是当前已保存的配置，草稿需先保存" placement="top">
        <el-button :loading="testing" @click="handleTest">测试连通性</el-button>
      </el-tooltip>
      <el-button text :disabled="!dirty" @click="emit('reload')">放弃改动</el-button>
      <span v-if="saveDisabledReason && !saving" class="model-form__actions-hint">
        {{ saveDisabledReason }}
      </span>
    </div>

    <!-- 测试结果 -->
    <el-alert
      v-if="testResult"
      class="model-form__alert"
      :type="testResult.ok ? 'success' : 'error'"
      :closable="false"
      show-icon
      :title="testResultSummary(testResult)"
    >
      <span v-if="testResult.model" class="model-form__test-meta">模型：{{ testResult.model }}</span>
    </el-alert>

    <p class="model-form__footer">
      <template v-if="config.revision">
        配置版本 {{ config.revision }}，最后保存
        {{ config.updatedAt ? new Date(config.updatedAt).toLocaleString() : '—' }}
      </template>
      <template v-else>当前尚未在设置页保存过，全部取自环境变量。</template>
    </p>
  </div>
</template>

<style scoped>
.model-form {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-4);
}

/* 两列栅格：宽字段占满一行，窄字段并排，窄屏退化为单列 */
.model-form__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--os-space-4);
}

.model-form__field {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
  min-width: 0;
}

.model-form__field--wide {
  grid-column: 1 / -1;
}

.model-form__label {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  flex-wrap: wrap;
  font-size: var(--os-font-sm);
  color: var(--el-text-color-primary);
  font-weight: 500;
}

.model-form__current {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  font-weight: 400;
}

.model-form__hint-icon {
  color: var(--el-text-color-placeholder);
  cursor: help;
  vertical-align: -1px;
}

.model-form__help {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.5;
}

.model-form__key-row {
  display: flex;
  gap: var(--os-space-2);
}

.model-form__key-row :deep(.el-input) {
  flex: 1;
}

.model-form__inline {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
}

.model-form__slider {
  flex: 1;
  min-width: 0;
}

.model-form__actions {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  flex-wrap: wrap;
  padding-top: var(--os-space-2);
}

.model-form__actions-hint {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.model-form__alert {
  margin: 0;
}

.model-form__test-meta {
  font-size: var(--os-font-xs);
}

.model-form__footer {
  margin: 0;
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
}

@media (max-width: 768px) {
  .model-form__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
