<script setup lang="ts">
/**
 * 设置页：左侧子导航 + 右侧内容。
 *
 * <h2>为什么从「长滚动卡片」改成子导航</h2>
 * 改造前是两张卡片串起来的单列布局，实测文档高度 1856px —— 一屏放不下，
 * 找「日程」要滚两屏。子导航把六个分组变成平级入口，每屏只看一组。
 *
 * <h2>分组层级为什么重新划分</h2>
 * 改造前的层级是矛盾的：「大模型 API」独占一张卡片当老大，
 * 「外观 / 对话 / 日程 / 数据 / 关于」全塞进第二张卡片当小标题 ——
 * 视觉上暗示「模型配置」比其余五项加起来还重要。现在六组平级。
 *
 * <h2>主题为什么只在这里出现</h2>
 * 改造前顶栏有个月亮图标、设置页外观里又有主题选择，两个入口且都表达不了
 * 「跟随系统」。现在顶栏那个只做**快速切换**（浅/深两态循环），
 * 设置页这里是唯一的**完整**入口（三态含跟随系统）。
 * 两者共享同一个 store，不会不一致。
 */
import { Download, RefreshLeft } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref } from 'vue'

import { downloadExport } from '@/api/export'
import PageHeader from '@/components/PageHeader.vue'
import { confirmAction } from '@/lib/confirm-box'
import { THINKING_HINTS, THINKING_LABELS } from '@/lib/settings'
import { useAppStore } from '@/stores/app'
import { useSettingsStore } from '@/stores/settings'
import { useUiStore } from '@/stores/ui'
import type { ThemePreference } from '@/lib/theme'

const settingsStore = useSettingsStore()
const uiStore = useUiStore()
const appStore = useAppStore()
const { settings } = storeToRefs(settingsStore)
const { theme, navCollapsed, resolvedTheme } = storeToRefs(uiStore)
const { health, loading: healthLoading, error: healthError } = storeToRefs(appStore)

type SectionId = 'model' | 'appearance' | 'chat' | 'calendar' | 'data' | 'about'

interface NavItem {
  id: SectionId
  label: string
  hint: string
}

/**
 * 导航项。
 *
 * <p>`hint` 是右侧的一句话说明：用户不必点进去就知道这一组管什么。
 * 六个标签里「对话」「数据」这类词单看含义太泛，需要一句补充。
 */
const navItems: NavItem[] = [
  { id: 'model', label: '模型配置', hint: '供应商、端点、密钥与模型切换' },
  { id: 'appearance', label: '外观', hint: '主题与侧边栏' },
  { id: 'chat', label: '对话', hint: '知识库增强、发送方式与思考程度' },
  { id: 'calendar', label: '日程', hint: '默认提醒时间' },
  { id: 'data', label: '数据', hint: '导出与重置偏好' },
  { id: 'about', label: '关于', hint: '后端状态与版本' },
]

const activeSection = ref<SectionId>('model')

const activeItem = computed(() => navItems.find((item) => item.id === activeSection.value) ?? navItems[0])

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
      '将清除全部本地偏好（主题、侧栏、各页视图模式、会话级知识库开关等），并恢复默认值。不影响任何业务数据，也不影响模型配置（它存在服务端）。',
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
    <PageHeader
      size="page"
      title="设置"
      description="模型配置存在服务端数据库里、跨设备一致；其余偏好只保存在此浏览器（localStorage），换设备不会同步。"
    />

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

      <!-- 右侧内容 -->
      <section class="settings__panel">
        <header class="settings__panel-head">
          <h2 class="settings__panel-title">{{ activeItem.label }}</h2>
          <p class="settings__panel-hint">{{ activeItem.hint }}</p>
        </header>

        <!-- 模型配置 -->
        <ModelProfilesPanel v-if="activeSection === 'model'" />

        <!-- 外观 -->
        <template v-else-if="activeSection === 'appearance'">
          <div class="settings__row">
            <div class="settings__row-text">
              <span class="settings__row-label">主题</span>
              <span class="settings__row-hint">
                当前：{{ resolvedTheme === 'dark' ? '深色' : '浅色' }}；「跟随系统」会随操作系统的深浅色自动切换
              </span>
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
        </template>

        <!-- 对话 -->
        <template v-else-if="activeSection === 'chat'">
          <div class="settings__row">
            <div class="settings__row-text">
              <span class="settings__row-label">新会话默认开启知识库增强</span>
              <span class="settings__row-hint"> 开启后每条消息先检索知识库，回答带 [n] 引用；单个会话可随时覆盖 </span>
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
              <span class="settings__row-hint">
                {{ THINKING_HINTS[settings.thinkingLevel] }}；输入框上可随时临时切换
              </span>
            </div>
            <el-radio-group v-model="settings.thinkingLevel">
              <el-radio-button v-for="level in thinkingLevels" :key="level" :value="level">
                {{ THINKING_LABELS[level] }}
              </el-radio-button>
            </el-radio-group>
          </div>
        </template>

        <!-- 日程 -->
        <template v-else-if="activeSection === 'calendar'">
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
        </template>

        <!-- 数据 -->
        <template v-else-if="activeSection === 'data'">
          <div class="settings__row">
            <div class="settings__row-text">
              <span class="settings__row-label">导出数据</span>
              <span class="settings__row-hint"> 全部任务、日程、记忆、知识库与对话历史导出为一个 JSON 文件 </span>
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
        <template v-else-if="activeSection === 'about'">
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
    </div>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-5);
  width: 100%;
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
  align-items: start;
}

.settings__nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  position: sticky;
  top: var(--os-space-4);
}

.settings__nav-item {
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

.settings__panel {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-3);
  min-width: 0;
  padding: var(--os-space-4) var(--os-space-5) var(--os-space-5);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-lg);
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

.settings__panel-hint {
  margin: var(--os-space-1) 0 0;
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
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
    gap: var(--os-space-3);
  }

  .settings__nav {
    flex-direction: row;
    position: static;
    gap: var(--os-space-1);
    overflow-x: auto;
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

  .settings__panel {
    padding: var(--os-space-4);
  }
}
</style>
