<script setup lang="ts">
/**
 * 设置页（阶段 19，User Story 3 / FR-011）。
 *
 * 分两类设置，界面上刻意分成两块卡片：
 *
 * <ol>
 *   <li><b>本机偏好</b>：全部存 localStorage（os.settings.* + 复用 os.theme /
 *       os.nav.collapsed），换设备不同步。这是本页原有内容。</li>
 *   <li><b>服务端配置</b>：大模型 API（端点 / 密钥 / 模型 / 温度）。存在数据库里、
 *       跨设备一致、保存即热切换。见 {@link ModelConfigSection}。</li>
 * </ol>
 *
 * 之所以要分开呈现：两类设置的「作用范围」和「失效方式」完全不同。
 * 偏好清掉浏览器数据就回默认；服务端配置存在库里，清浏览器没用。
 * 混在一张卡片里，用户会以为清缓存能恢复模型配置。
 */
import { Download, RefreshLeft } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { storeToRefs } from 'pinia'
import { onMounted, ref } from 'vue'

import { downloadExport } from '@/api/export'
import { useAppStore } from '@/stores/app'
import { useSettingsStore } from '@/stores/settings'
import { useUiStore } from '@/stores/ui'
import { THINKING_HINTS, THINKING_LABELS } from '@/lib/settings'
import type { ThemePreference } from '@/lib/theme'

const settingsStore = useSettingsStore()
const uiStore = useUiStore()
const appStore = useAppStore()
const { settings } = storeToRefs(settingsStore)
const { theme, navCollapsed, resolvedTheme } = storeToRefs(uiStore)
const { health, loading: healthLoading, error: healthError } = storeToRefs(appStore)

const themeOptions: Array<{ label: string; value: ThemePreference }> = [
  { label: '浅色', value: 'light' },
  { label: '深色', value: 'dark' },
  { label: '跟随系统', value: 'system' },
]

/** 思考档位顺序：关闭 → 标准 → 深度（与界面从左到右一致） */
const thinkingLevels = ['off', 'standard', 'deep'] as const

onMounted(() => {
  void appStore.loadHealth()
})

// ---------------------------------------------------------------- 数据

const exporting = ref(false)

async function handleExport(): Promise<void> {
  exporting.value = true
  try {
    const filename = await downloadExport()
    ElMessage.success(`已导出 ${filename}`)
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '导出失败，请稍后重试')
  } finally {
    exporting.value = false
  }
}

/**
 * 重置全部本地偏好（FR-013）。
 *
 * 破坏性操作：二次确认 + 明确告知影响范围（不只是设置页四项，主题 / 侧栏 /
 * 各页视图 / 会话级开关一并归零）。确认后 store 负责清键并刷新页面。
 */
async function handleReset(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将清除全部本地偏好（主题、侧栏、各页视图模式、会话级知识库开关等），并恢复默认值。不影响任何业务数据。',
      '重置全部偏好',
      { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  settingsStore.resetAllPreferences()
}
</script>

<template>
  <div class="settings">
    <header class="settings__head">
      <h1 class="settings__title">设置</h1>
      <p class="settings__subtitle">
        服务端配置（大模型 API）存在数据库里、跨设备一致；其余偏好只保存在此浏览器（localStorage）， 换设备不会同步。
      </p>
    </header>

    <!-- 服务端配置：与本地偏好分开卡片，避免「清缓存能恢复模型配置」的误解 -->
    <el-card shadow="never">
      <ModelConfigSection />
    </el-card>

    <el-card shadow="never">
      <!-- 外观 -->
      <section class="settings__section">
        <h3 class="settings__section-title">外观</h3>
        <div class="settings__row">
          <div class="settings__row-text">
            <span class="settings__row-label">主题</span>
            <span class="settings__row-hint">当前：{{ resolvedTheme === 'dark' ? '深色' : '浅色' }}</span>
          </div>
          <el-radio-group v-model="theme">
            <el-radio-button v-for="option in themeOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
        </div>
        <div class="settings__row">
          <div class="settings__row-text">
            <span class="settings__row-label">收起侧边栏</span>
            <span class="settings__row-hint">折叠为纯图标模式，随时可展开</span>
          </div>
          <el-switch v-model="navCollapsed" />
        </div>
      </section>

      <!-- 对话 -->
      <section class="settings__section">
        <h3 class="settings__section-title">对话</h3>
        <div class="settings__row">
          <div class="settings__row-text">
            <span class="settings__row-label">新会话默认开启知识库增强</span>
            <span class="settings__row-hint">开启后每条消息先检索知识库，回答带 [n] 引用；单个会话可随时覆盖</span>
          </div>
          <el-switch v-model="settings.knowledgeDefaultOn" />
        </div>
        <div class="settings__row">
          <div class="settings__row-text">
            <span class="settings__row-label">默认检索条数</span>
            <span class="settings__row-hint">每轮送入模型的片段数，1~20</span>
          </div>
          <el-input-number v-model="settings.knowledgeTopK" :min="1" :max="20" />
        </div>
        <div class="settings__row">
          <div class="settings__row-text">
            <span class="settings__row-label">Enter 直接发送</span>
            <span class="settings__row-hint">关闭后 Enter 换行、Ctrl + Enter 发送</span>
          </div>
          <el-switch v-model="settings.enterSend" />
        </div>
        <div class="settings__row">
          <div class="settings__row-text">
            <span class="settings__row-label">默认思考程度</span>
            <span class="settings__row-hint">{{ THINKING_HINTS[settings.thinkingLevel] }}；输入框上可随时临时切换</span>
          </div>
          <el-radio-group v-model="settings.thinkingLevel">
            <el-radio-button v-for="level in thinkingLevels" :key="level" :value="level">
              {{ THINKING_LABELS[level] }}
            </el-radio-button>
          </el-radio-group>
        </div>
      </section>

      <!-- 日程 -->
      <section class="settings__section">
        <h3 class="settings__section-title">日程</h3>
        <div class="settings__row">
          <div class="settings__row-text">
            <span class="settings__row-label">默认提前提醒</span>
            <span class="settings__row-hint">新建日程时「提前 N 分钟提醒」的默认值</span>
          </div>
          <div class="settings__inline">
            <el-input-number v-model="settings.reminderLead" :min="0" :max="10080" :step="5" />
            <span class="settings__unit">分钟</span>
          </div>
        </div>
      </section>

      <!-- 数据 -->
      <section class="settings__section">
        <h3 class="settings__section-title">数据</h3>
        <div class="settings__row">
          <div class="settings__row-text">
            <span class="settings__row-label">导出数据</span>
            <span class="settings__row-hint">全部任务、日程、记忆、知识库与对话历史导出为一个 JSON 文件</span>
          </div>
          <el-button :loading="exporting" @click="handleExport">
            <el-icon><Download /></el-icon>
            导出
          </el-button>
        </div>
        <div class="settings__row">
          <div class="settings__row-text">
            <span class="settings__row-label">重置全部偏好</span>
            <span class="settings__row-hint">清除本浏览器保存的全部界面偏好（不影响任何业务数据）</span>
          </div>
          <el-button type="danger" plain @click="handleReset">
            <el-icon><RefreshLeft /></el-icon>
            重置
          </el-button>
        </div>
      </section>

      <!-- 关于 -->
      <section class="settings__section">
        <h3 class="settings__section-title">关于</h3>
        <div class="settings__row">
          <div class="settings__row-text">
            <span class="settings__row-label">后端状态</span>
            <span class="settings__row-hint">
              <template v-if="healthError">无法连接后端服务：{{ healthError }}</template>
              <template v-else-if="health">
                {{ health.status }} · 版本 {{ health.version }} · 服务端时间 {{ health.timestamp }}
              </template>
              <template v-else>加载中…</template>
            </span>
          </div>
          <el-button size="small" :loading="healthLoading" @click="appStore.loadHealth()">刷新</el-button>
        </div>
      </section>
    </el-card>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-5);
  max-width: 760px;
  margin: 0 auto;
  width: 100%;
}

.settings__head {
  min-width: 0;
}

.settings__title {
  margin: 0;
  font-size: var(--os-font-2xl);
  font-weight: 650;
  letter-spacing: -0.2px;
  line-height: 1.3;
}

.settings__subtitle {
  margin: var(--os-space-2) 0 0;
  max-width: 62ch;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
  line-height: 1.65;
}

.settings__section + .settings__section {
  margin-top: var(--os-space-5);
  padding-top: var(--os-space-5);
  border-top: 1px solid var(--el-border-color-lighter);
}

.settings__section-title {
  margin: 0 0 var(--os-space-3);
  font-size: var(--os-font-md);
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.settings__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-4);
  flex-wrap: wrap;
  padding: var(--os-space-3) 0;
}

/* 相邻行之间一条极淡的分隔线：比加大 padding 更能表达「这是两条独立设置」 */
.settings__row + .settings__row {
  border-top: 1px solid var(--el-border-color-extra-light);
}

.settings__row-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.settings__row-label {
  font-size: var(--os-font-md);
  color: var(--el-text-color-primary);
}

.settings__row-hint {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.5;
}

.settings__inline {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
}

.settings__unit {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

/* 窄屏：行内控件换行到文字下方，左对齐 */
@media (max-width: 768px) {
  .settings__row {
    align-items: flex-start;
  }

  .settings__row :deep(.el-radio-group),
  .settings__row :deep(.el-switch),
  .settings__row :deep(.el-input-number),
  .settings__row :deep(.el-button) {
    margin-left: 0;
  }
}
</style>
