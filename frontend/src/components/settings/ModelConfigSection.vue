<script setup lang="ts">
/**
 * 大模型 API 配置区（设置页）。
 *
 * <h2>为什么单独成组件</h2>
 * 这是设置页里唯一**后端有状态**的一块：其余设置项都是 localStorage 偏好，
 * 只有它要读写服务端、要热切换模型、要发真实请求。混在偏好里会让人误以为
 * 清一下浏览器数据就恢复默认，而它其实存在数据库里。
 *
 * <h2>只做三件事</h2>
 * 读取、失败重试、加载态。表单本身在 {@link ModelConfigForm}——拆开是为了
 * 让那个组件能接非空 prop，模板里不必到处写非空断言。
 *
 * <h2>三个必须知道的行为</h2>
 * <ol>
 *   <li><b>密钥永不回填。</b>后端只回 `****8f3a` 掩码。输入框留空 = 不改动，
 *       填了新值 = 覆盖，点「清除」= 回落到环境变量。把掩码填进输入框再提交，
 *       会把真实密钥覆盖成那七个字符。</li>
 *   <li><b>保存即生效。</b>后端会重建模型实例，不用重启。嵌入模型是例外
 *       （向量维度由 DDL 固定），界面上如实标注「需重启」。</li>
 *   <li><b>保存与连通性测试是两件事。</b>测试发的是「当前生效」的配置，
 *       不是表单里的草稿 —— 想验新配置必须先保存。</li>
 * </ol>
 */
import { onMounted, ref } from 'vue'

import { getModelConfig } from '@/api/model-config'
import type { ModelConfig } from '@/types/api'

const loading = ref(false)
const loadError = ref<string | null>(null)
const config = ref<ModelConfig | null>(null)

onMounted(() => {
  void load()
})

async function load(): Promise<void> {
  loading.value = true
  loadError.value = null
  try {
    config.value = await getModelConfig()
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : '读取配置失败'
  } finally {
    loading.value = false
  }
}

/** 保存成功后直接用返回的生效值刷新，避免多打一次 GET */
function handleSaved(saved: ModelConfig): void {
  config.value = saved
}
</script>

<template>
  <section class="model-config">
    <header class="model-config__head">
      <h3 class="model-config__title">大模型 API</h3>
      <p class="model-config__lead">
        填写 OpenAI 兼容端点即可接入，保存后立即生效、无需重启。留空的项会回落到 .env 中的同名变量。
      </p>
    </header>

    <el-alert
      v-if="loadError"
      class="model-config__alert"
      type="error"
      :closable="false"
      show-icon
      :title="`读取配置失败：${loadError}`"
    >
      <el-button size="small" @click="load">重试</el-button>
    </el-alert>

    <div v-else-if="loading" class="model-config__loading">加载中…</div>

    <ModelConfigForm v-else-if="config" :config="config" @saved="handleSaved" @reload="load" />
  </section>
</template>

<style scoped>
.model-config {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-4);
}

.model-config__title {
  margin: 0;
  font-size: var(--os-font-md);
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.model-config__lead {
  margin: var(--os-space-2) 0 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.6;
  max-width: 68ch;
}

.model-config__loading {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
  padding: var(--os-space-4) 0;
}

.model-config__alert {
  margin: 0;
}
</style>
