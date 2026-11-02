<script setup lang="ts">
/** 设置分组通过 query 保存，支持刷新与直接访问。 */
import { Download, RefreshLeft } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { downloadExport } from '@/api/export'
import PageHeader from '@/components/PageHeader.vue'
import McpSettingsPanel from '@/components/settings/McpSettingsPanel.vue'
import { confirmAction } from '@/lib/confirm-box'
import { THINKING_LABELS } from '@/lib/settings'
import { useAppStore } from '@/stores/app'
import { useSettingsStore } from '@/stores/settings'
import { useUiStore } from '@/stores/ui'
import type { ThemePreference } from '@/lib/theme'

const settingsStore = useSettingsStore()
const uiStore = useUiStore()
const appStore = useAppStore()
const { settings } = storeToRefs(settingsStore)
const { theme, navCollapsed } = storeToRefs(uiStore)
const { health, loading: healthLoading, error: healthError } = storeToRefs(appStore)

type SectionId = 'model' | 'mcp' | 'appearance' | 'chat' | 'calendar' | 'data' | 'about'

interface NavItem {
  id: SectionId
  label: string
}

/** 设置分组。 */
const navItems: NavItem[] = [
  { id: 'model', label: '模型配置' },
  { id: 'mcp', label: 'MCP' },
  { id: 'appearance', label: '外观' },
  { id: 'chat', label: '对话' },
  { id: 'calendar', label: '日程' },
  { id: 'data', label: '数据' },
  { id: 'about', label: '关于' },
]

const route = useRoute()
const router = useRouter()
const activeSection = computed<SectionId>({
  get: () => navItems.find((item) => item.id === route.query.section)?.id ?? 'model',
  set: (section) => {
    void router.replace({ query: { ...route.query, section: section === 'model' ? undefined : section } })
  },
})

// 首次访问时才挂载；切回时保留已加载的数据、展开项和各分组的滚动位置。
const visitedSections = ref(new Set<SectionId>([activeSection.value]))
watch(activeSection, (section) => visitedSections.value.add(section))

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
 * 破坏性操作：二次确认 + 明确告知影响范围（不只是设置页这几项，主题 / 侧栏 /
 * 各页视图 / 会话级开关一并归零）。确认后 store 负责清键并刷新页面。
 */
async function handleReset(): Promise<void> {
  // 「重置偏好」可恢复（重置后重新选一遍即可），不属于删除类，确认键不用危险色
  const ok = await confirmAction({
    title: '重置全部偏好',
    message:
      '将清除全部本地偏好（主题、侧栏、各页视图模式、会话级知识库开关等），并恢复默认值。业务数据和模型配置不受影响。',
    confirmText: '重置',
  })
  if (!ok) {
    return
  }
  settingsStore.resetAllPreferences()
}
</script>

<template>
  <div class="settings">
    <PageHeader size="page" title="设置" />

    <div class="settings__layout">
      <!-- 左侧子导航 -->
      <nav class="settings__nav" aria-label="设置分组">
        <button
          v-for="item in navItems"
          :key="item.id"
          type="button"
          class="settings__nav-item"
          :class="{ 'settings__nav-item--active': item.id === activeSection }"
          :aria-current="item.id === activeSection ? 'page' : undefined"
          @click="activeSection = item.id"
        >
          {{ item.label }}
        </button>
      </nav>

      <!-- 每组保留自己的滚动容器，异步加载和分组切换都不改变外层布局。 -->
      <div class="settings__content">
        <template v-for="item in navItems" :key="item.id">
          <section
            v-if="visitedSections.has(item.id)"
            v-show="item.id === activeSection"
            class="settings__panel"
            :class="{ 'settings__panel--mcp': item.id === 'mcp' }"
            :aria-label="item.label"
          >
            <header v-if="item.id !== 'mcp'" class="settings__panel-head">
              <h2 class="settings__panel-title">{{ item.label }}</h2>
            </header>

            <!-- 模型配置 -->
            <ModelProfilesPanel v-if="item.id === 'model'" :active="item.id === activeSection" />

            <McpSettingsPanel v-else-if="item.id === 'mcp'" :active="item.id === activeSection" />

            <!-- 外观 -->
            <template v-else-if="item.id === 'appearance'">
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">主题</span>
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
                </div>
                <el-switch v-model="navCollapsed" />
              </div>
            </template>

            <!-- 对话 -->
            <template v-else-if="item.id === 'chat'">
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">新会话默认开启知识库增强</span>
                </div>
                <el-switch v-model="settings.knowledgeDefaultOn" />
              </div>
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">默认检索条数</span>
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
                  <span class="settings__row-label">执行中发送消息</span>
                  <span class="settings__row-hint">{{
                    settings.followupMode === 'STEER'
                      ? '直接补充当前对话；正在执行的工具会先完成。'
                      : '当前任务完成后，按发送顺序处理新消息。'
                  }}</span>
                </div>
                <el-radio-group v-model="settings.followupMode" aria-label="执行中发送消息的处理方式">
                  <el-radio-button value="QUEUE">排队处理</el-radio-button>
                  <el-radio-button value="STEER">引导当前对话</el-radio-button>
                </el-radio-group>
              </div>
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">默认思考程度</span>
                </div>
                <el-radio-group v-model="settings.thinkingLevel">
                  <el-radio-button v-for="level in thinkingLevels" :key="level" :value="level">
                    {{ THINKING_LABELS[level] }}
                  </el-radio-button>
                </el-radio-group>
              </div>
            </template>

            <!-- 日程 -->
            <template v-else-if="item.id === 'calendar'">
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">默认提前提醒</span>
                </div>
                <div class="settings__inline">
                  <el-input-number v-model="settings.reminderLead" :min="0" :max="10080" :step="5" />
                  <span class="settings__unit">分钟</span>
                </div>
              </div>
            </template>

            <!-- 数据 -->
            <template v-else-if="item.id === 'data'">
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">导出数据</span>
                  <span class="settings__row-hint"> 任务、日程、记忆、知识库与对话历史（JSON） </span>
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
            </template>

            <!-- 关于 -->
            <template v-else-if="item.id === 'about'">
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
                <el-button :loading="healthLoading" @click="appStore.loadHealth()">刷新</el-button>
              </div>
            </template>
          </section>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-5);
  width: 100%;
  flex: 1;
  min-height: 0;
}

/*
 * 两列：固定 176px 的子导航 + 弹性内容。
 * 用 grid 而不是 flex：内容区的 minmax(0, 1fr) 能防止长端点地址
 * 把整列撑宽（flex 的 flex:1 在没有 min-width:0 时会被内容撑开）。
 */
.settings__layout {
  display: grid;
  grid-template-columns: 176px minmax(0, 1fr);
  gap: var(--os-space-5);
  grid-template-rows: minmax(0, 1fr);
  flex: 1;
  min-height: 0;
}

.settings__nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  align-self: start;
  max-height: 100%;
  min-height: 0;
  overflow-y: auto;
}

.settings__nav-item {
  min-height: var(--os-button-height);
  padding: var(--os-space-2) var(--os-space-3);
  font-size: var(--os-font-sm);
  color: var(--el-text-color-regular);
  text-align: left;
  background: none;
  border: none;
  border-radius: var(--os-radius-sm);
  cursor: pointer;
  transition:
    background-color var(--os-duration-fast) var(--os-ease),
    color var(--os-duration-fast) var(--os-ease);
}

.settings__nav-item:hover {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}

/*
 * 选中态：浅主色底 + 主色字 + **左侧 3px 指示条**。
 *
 * 只靠底色与字色时，选中项与未选项的字重差异太小（视觉审查实测「选中态偏弱」）。
 * 加一条竖条后，位置本身成为一个不依赖对比度的信号 —— 暗色下尤其需要，
 * 因为暗色的 light-9 底色与页面底色几乎同亮度。
 *
 * 用 ::before 而不是 border-left：border 会让文字整体右移 3px，
 * 切换分组时标签会横向跳动。
 */
.settings__nav-item--active {
  position: relative;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 500;
}

.settings__nav-item--active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  width: 3px;
  height: 60%;
  transform: translateY(-50%);
  background: var(--el-color-primary);
  border-radius: 0 2px 2px 0;
}

.settings__nav-item:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

.settings__content {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
}

.settings__panel {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-3);
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  padding: var(--os-space-4) var(--os-space-5) var(--os-space-5);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-lg);
}

/* 子内容保持自然高度，剩余高度由设置容器承担。 */
.settings__panel > * {
  flex-shrink: 0;
}

.settings__panel--mcp {
  padding: 0;
  background: transparent;
  border: 0;
}

.settings__panel-head {
  padding-bottom: var(--os-space-2);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.settings__panel-title {
  margin: 0;
  font-size: var(--os-font-lg);
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

/* 窄屏：子导航转为横向可滚动标签条，内容降到下方 */
@media (max-width: 900px) {
  .settings__layout {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
    gap: var(--os-space-3);
  }

  .settings__nav {
    flex-direction: row;
    gap: var(--os-space-1);
    overflow-x: auto;
    overflow-y: hidden;
    max-height: none;
    padding-bottom: var(--os-space-1);
  }

  .settings__nav-item {
    white-space: nowrap;
  }
}

@media (max-width: 768px) {
  .settings__row {
    align-items: flex-start;
  }

  .settings__panel:not(.settings__panel--mcp) {
    padding: var(--os-space-4);
  }
}
</style>
