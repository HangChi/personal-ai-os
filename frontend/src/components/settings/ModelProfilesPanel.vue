<script setup lang="ts">
/**
 * 模型配置面板：卡片列表 + 预设库 + 编辑对话框。
 *
 * <h2>交互参照 cc-switch 的 provider 管理</h2>
 * <ul>
 *   <li>多套配置并列成卡片，一眼看到各自用什么模型；</li>
 *   <li>点「启用」即切换，不用改任何文件；</li>
 *   <li>当前生效的那套不能删（先切走再删）；</li>
 *   <li>从预设库加新配置时只需填密钥。</li>
 * </ul>
 * 不做 cc-switch 的 Routing / Aggregation：那是本地反向代理与多模型聚合，
 * 本项目单用户直连，引入一层代理只会增加一条会坏的链路。
 *
 * <h2>为什么「测试连通性」用当前生效的配置</h2>
 * 表单草稿可能有错（模型名写错、端点写不通），测草稿会有两种截然不同的结果
 * 混在一起：「草稿不通」与「当前配置不通」。只测当前生效配置，语义单一。
 * 想验新配置就先启用它 —— 启用失败本身就会报错，与测试等价。
 */
import { Plus, Refresh, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'

import { confirmDanger } from '@/lib/confirm-box'
import { deleteBlockedReason, testResultSummary } from '@/lib/model-profile'
import type { ProviderPreset } from '@/lib/provider-presets'
import { useModelProfileStore } from '@/stores/model-profile'
import type { ModelConnectionTestResult, SaveModelProfilePayload } from '@/types/api'

const store = useModelProfileStore()

const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const pickedPreset = ref<ProviderPreset | null>(null)
const dialogRef = ref<{ finish: () => void } | null>(null)

const testing = ref(false)
const testResult = ref<ModelConnectionTestResult | null>(null)

/** 编辑中的配置（null = 新建） */
const editingProfile = computed(() =>
  editingId.value === null ? null : (store.profiles.find((p) => p.id === editingId.value) ?? null),
)

/** 是否有切换正在进行：此时禁用所有卡片动作，避免并发重建模型 */
const busy = computed(() => store.activatingId !== null)

onMounted(() => {
  void store.load()
})

function openCreate(): void {
  editingId.value = null
  pickedPreset.value = null
  dialogVisible.value = true
}

function openEdit(id: string): void {
  editingId.value = id
  pickedPreset.value = null
  dialogVisible.value = true
}

/** 从预设库选中一个预设 → 带着预填值打开表单 */
function handlePickPreset(preset: ProviderPreset): void {
  editingId.value = null
  pickedPreset.value = preset
  dialogVisible.value = true
}

async function handleSubmit(payload: SaveModelProfilePayload, id: string | null): Promise<void> {
  const error = id === null ? await store.create(payload) : await store.update(id, payload)
  dialogRef.value?.finish()
  if (error) {
    ElMessage.error(error)
    return
  }
  dialogVisible.value = false
  ElMessage.success(id === null ? '已添加配置' : '已保存')
}

async function handleActivate(id: string): Promise<void> {
  const error = await store.activate(id)
  if (error) {
    // 切换失败时后端可能已经改了 active 状态（配置落库、模型初始化失败），
    // store 内部已经重拉过列表，这里只报错。
    ElMessage.error(error)
    return
  }
  testResult.value = null
  ElMessage.success('已切换')
}

async function handleDelete(id: string, name: string): Promise<void> {
  const ok = await confirmDanger({
    title: '删除模型配置',
    message: `将删除「${name}」这套配置。此操作不可撤销，但可以重新添加。`,
    confirmText: '删除',
  })
  if (!ok) {
    return
  }
  const error = await store.remove(id)
  if (error) {
    ElMessage.error(error)
    return
  }
  ElMessage.success('已删除')
}

async function handleTest(): Promise<void> {
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await store.test()
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

/** 卡片不可删除的原因（与后端规则一致，前端提前拦） */
function blockedReason(id: string): string | null {
  const profile = store.profiles.find((p) => p.id === id)
  return profile ? deleteBlockedReason(profile, store.profiles.length) : null
}
</script>

<template>
  <section class="model-profiles">
    <header class="model-profiles__head">
      <div>
        <p class="model-profiles__lead">
          可以并存多套供应商配置，点「启用」即时切换，不用重启。当前生效的那套同时用于对话、工具调用与知识库问答。
        </p>
      </div>
      <div class="model-profiles__head-actions">
        <el-tooltip content="用当前生效的配置真发一次最小请求" placement="top">
          <el-button :loading="testing" :disabled="store.isEmpty" @click="handleTest">
            <el-icon><VideoPlay /></el-icon>
            测试连通性
          </el-button>
        </el-tooltip>
        <el-button :loading="store.loading" @click="store.load()">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          添加配置
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="testResult"
      class="model-profiles__alert"
      :type="testResult.ok ? 'success' : 'error'"
      :closable="false"
      show-icon
      :title="testResultSummary(testResult)"
    >
      <span v-if="testResult.model" class="model-profiles__test-meta">模型：{{ testResult.model }}</span>
    </el-alert>

    <el-alert
      v-if="store.loadError"
      class="model-profiles__alert"
      type="error"
      :closable="false"
      show-icon
      :title="`读取配置失败：${store.loadError}`"
    >
      <el-button size="small" @click="store.load()">重试</el-button>
    </el-alert>

    <div v-if="store.loading && store.isEmpty" class="model-profiles__loading">加载中…</div>

    <!-- 空态：引导从预设库加第一套 -->
    <div v-else-if="store.isEmpty && !store.loadError" class="model-profiles__empty">
      <p class="model-profiles__empty-title">还没有任何模型配置</p>
      <p class="model-profiles__empty-hint">
        从下面选一个供应商，填上你的 API Key 就能开始用。也可以先用环境变量里的配置。
      </p>
      <ProviderPresetPicker @pick="handlePickPreset" />
    </div>

    <template v-else>
      <!-- 卡片列表 -->
      <div class="model-profiles__list">
        <ModelProfileCard
          v-for="profile in store.profiles"
          :key="profile.id"
          :profile="profile"
          :activating="store.activatingId === profile.id"
          :disabled="busy"
          :delete-blocked="blockedReason(profile.id)"
          @activate="handleActivate(profile.id)"
          @edit="openEdit(profile.id)"
          @delete="handleDelete(profile.id, profile.name)"
        />
      </div>

      <!-- 添加更多：折叠的预设库 -->
      <el-collapse class="model-profiles__add-more">
        <el-collapse-item name="presets">
          <template #title>
            <span class="model-profiles__add-more-title">
              <el-icon><Plus /></el-icon>
              从预设库添加更多供应商
            </span>
          </template>
          <ProviderPresetPicker @pick="handlePickPreset" />
        </el-collapse-item>
      </el-collapse>

      <p class="model-profiles__foot">
        提示：配置里留空的项（端点 / 模型 / 密钥）会回落到 .env 中的同名变量，
        因此可以只填想覆盖的那几项。删除当前生效的配置前需要先切换到别的配置。
      </p>
    </template>

    <ModelProfileDialog
      ref="dialogRef"
      v-model="dialogVisible"
      :profile="editingProfile"
      :preset="pickedPreset"
      @submit="handleSubmit"
    />
  </section>
</template>

<style scoped>
.model-profiles {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-4);
}

.model-profiles__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--os-space-4);
  flex-wrap: wrap;
}

.model-profiles__lead {
  margin: 0;
  max-width: 72ch;
  font-size: var(--os-font-xs);
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.model-profiles__head-actions {
  display: flex;
  align-items: center;
  gap: var(--os-button-gap);
  flex-wrap: wrap;
  flex-shrink: 0;
}

.model-profiles__alert {
  margin: 0;
}

.model-profiles__test-meta {
  font-size: var(--os-font-xs);
}

.model-profiles__loading {
  padding: var(--os-space-5) 0;
  font-size: var(--os-font-sm);
  color: var(--el-text-color-secondary);
}

.model-profiles__empty {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-3);
  padding: var(--os-space-5);
  background: var(--el-fill-color-lighter);
  border: 1px dashed var(--el-border-color);
  border-radius: var(--os-radius-lg);
}

.model-profiles__empty-title {
  margin: 0;
  font-size: var(--os-font-md);
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.model-profiles__empty-hint {
  margin: 0;
  font-size: var(--os-font-xs);
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

/*
 * 单列而非多列栅格。
 *
 * 试过 `repeat(auto-fill, minmax(320px, 1fr))`，1440 视口下得到 395px 宽的卡片，
 * 而端点地址 `https://dashscope.aliyuncs.com/compatible-mode/v1` 需要约 400px
 * —— 实测折成 2 行，把「模型 / 密钥」两列的整齐节奏打断了。
 * 卡片只有 4 个字段，单列时每张约 844px，端点单行显示、扫读成本更低；
 * 配置数量通常在 2~5 套，纵向多滚一点不成问题。
 */
.model-profiles__list {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-3);
}

.model-profiles__add-more {
  border-top: 1px solid var(--el-border-color-lighter);
  border-bottom: none;
}

.model-profiles__add-more-title {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-2);
  font-size: var(--os-font-sm);
  color: var(--el-text-color-regular);
}

.model-profiles__foot {
  margin: 0;
  font-size: var(--os-font-xs);
  line-height: 1.6;
  color: var(--el-text-color-placeholder);
}
</style>
