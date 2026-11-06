<script setup lang="ts">
/** 设置分组通过 query 保存，支持刷新与直接访问。 */
import {
  Bell,
  ChatDotRound,
  Connection,
  DataAnalysis,
  InfoFilled,
  Monitor,
  Reading,
  RefreshLeft,
  Setting,
  Tools,
} from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { computed, defineAsyncComponent, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import PageHeader from '@/components/PageHeader.vue'
import CalendarNotifyBanner from '@/components/calendar/CalendarNotifyBanner.vue'
import ModelProfilesPanel from '@/components/settings/ModelProfilesPanel.vue'
import { useCalendarStore } from '@/stores/calendar'
import { SIDEBAR_DEFAULT_WIDTH, SIDEBAR_MIN_WIDTH, SIDEBAR_MAX_WIDTH } from '@/lib/sidebar'
import {
  CHAT_SESSION_PANEL_DEFAULT_WIDTH,
  CHAT_SESSION_PANEL_MIN_WIDTH,
  CHAT_SESSION_PANEL_MAX_WIDTH,
} from '@/stores/ui'
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
const { theme, navCollapsed, sidebarWidth, sessionPanelWidth, tasksView, knowledgeTab } = storeToRefs(uiStore)
const { health, loading: healthLoading, error: healthError } = storeToRefs(appStore)

const MaintenanceView = defineAsyncComponent(() => import('@/views/MaintenanceView.vue'))
const calendarStore = useCalendarStore()
const { notificationPermission, requestingPermission } = storeToRefs(calendarStore)

type SectionId = 'model' | 'mcp' | 'appearance' | 'chat' | 'calendar' | 'workspace' | 'shortcuts' | 'data' | 'about'

/** 分组入口说明以实际能力命名，旧 section 查询参数继续有效。 */
const navItems = [
  { id: 'model', label: '模型配置', description: '供应商、模型与连接', group: '连接与能力', icon: Connection },
  { id: 'mcp', label: 'MCP', description: '外部工具与本系统服务', group: '连接与能力', icon: Tools },
  { id: 'appearance', label: '外观', description: '主题与导航布局', group: '使用偏好', icon: Monitor },
  { id: 'workspace', label: '工作区', description: '默认视图与会话布局', group: '使用偏好', icon: Reading },
  { id: 'chat', label: '对话', description: '发送、检索与思考', group: '使用偏好', icon: ChatDotRound },
  { id: 'calendar', label: '日程', description: '提醒与浏览器通知', group: '使用偏好', icon: Bell },
  { id: 'shortcuts', label: '快捷键', description: '键盘操作速查', group: '使用偏好', icon: Setting },
  { id: 'data', label: '数据与维护', description: '备份、校验与运行健康', group: '系统', icon: DataAnalysis },
  { id: 'about', label: '关于', description: '服务状态与版本', group: '系统', icon: InfoFilled },
] as const
const preferenceSections = new Set<SectionId>(['appearance', 'workspace', 'chat', 'calendar'])
const knowledgeOptions = [
  { value: 'documents', label: '文档' },
  { value: 'search', label: '检索' },
  { value: 'ask', label: '问答' },
  { value: 'captures', label: '网页采集' },
  { value: 'governance', label: '资料治理' },
  { value: 'memory', label: '长期记忆' },
]
const shortcutRows = computed(() => [
  { action: '全局搜索', keys: '⌘ / Ctrl + K', hint: '从任意工作区定位任务、对话与资料' },
  {
    action: '发送消息',
    keys: settings.value.enterSend ? 'Enter' : '⌘ / Ctrl + Enter',
    hint: '按照对话设置中的发送方式生效',
  },
  { action: '消息换行', keys: settings.value.enterSend ? 'Shift + Enter' : 'Enter', hint: '在对话输入框中另起一行' },
  { action: '关闭弹窗', keys: 'Esc', hint: '关闭支持退出的弹窗或抽屉' },
  { action: '调整侧栏', keys: '← / → · Home', hint: '先将焦点移到侧栏边缘；Home 恢复默认宽度' },
])

const settingsNav = ref<HTMLElement | null>(null)
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
watch(activeSection, (section) => {
  visitedSections.value.add(section)
  void nextTick(revealActiveNav)
})
function revealActiveNav(): void {
  const nav = settingsNav.value
  const active = nav?.querySelector<HTMLElement>('[aria-current="page"]')
  if (!nav || !active || nav.scrollWidth <= nav.clientWidth) return
  const bounds = nav.getBoundingClientRect()
  const target = active.getBoundingClientRect()
  if (target.left < bounds.left) nav.scrollLeft += target.left - bounds.left
  else if (target.right > bounds.right) nav.scrollLeft += target.right - bounds.right
}

const themeOptions: Array<{ label: string; value: ThemePreference }> = [
  { label: '浅色', value: 'light' },
  { label: '深色', value: 'dark' },
  { label: '跟随系统', value: 'system' },
]

function dependencyTagType(value: string): 'success' | 'info' | 'danger' {
  return value === 'UP' || value === 'CONFIGURED' ? 'success' : value === 'NOT_CONFIGURED' ? 'info' : 'danger'
}

/** 思考档位顺序：关闭 → 标准 → 深度（与界面从左到右一致） */
const thinkingLevels = ['off', 'standard', 'deep'] as const

onMounted(() => {
  void appStore.loadHealth()
  revealActiveNav()
})

// ---------------------------------------------------------------- 数据

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
    <PageHeader size="page" title="设置" description="连接服务，调整使用习惯，管理数据与运行状态。">
      <span v-if="preferenceSections.has(activeSection)" class="settings__saved" role="status"
        >偏好自动保存在此浏览器</span
      >
    </PageHeader>

    <div class="settings__layout">
      <!-- 左侧子导航 -->
      <nav ref="settingsNav" class="settings__nav" aria-label="设置分组">
        <template v-for="(item, index) in navItems" :key="item.id">
          <span v-if="index === 0 || navItems[index - 1]?.group !== item.group" class="settings__nav-group">{{
            item.group
          }}</span>
          <button
            type="button"
            class="settings__nav-item"
            :class="{ 'settings__nav-item--active': item.id === activeSection }"
            :aria-current="item.id === activeSection ? 'page' : undefined"
            @click="activeSection = item.id"
          >
            <el-icon aria-hidden="true"><component :is="item.icon" /></el-icon>
            <span class="settings__nav-copy"
              ><span class="settings__nav-label">{{ item.label }}</span
              ><small>{{ item.description }}</small></span
            >
          </button>
        </template>
      </nav>

      <!-- 每组保留自己的滚动容器，异步加载和分组切换都不改变外层布局。 -->
      <div class="settings__content">
        <template v-for="item in navItems" :key="item.id">
          <section
            v-if="visitedSections.has(item.id)"
            v-show="item.id === activeSection"
            class="settings__panel"
            :class="{ 'settings__panel--mcp': item.id === 'mcp', 'settings__panel--data': item.id === 'data' }"
            :aria-label="item.label"
          >
            <header v-if="item.id !== 'mcp' && item.id !== 'data'" class="settings__panel-head">
              <h2 class="settings__panel-title">{{ item.label }}</h2>
              <p class="settings__panel-description">{{ item.description }}</p>
            </header>

            <!-- 模型配置 -->
            <ModelProfilesPanel v-if="item.id === 'model'" :active="item.id === activeSection" />

            <McpSettingsPanel v-else-if="item.id === 'mcp'" :active="item.id === activeSection" />

            <!-- 外观 -->
            <template v-else-if="item.id === 'appearance'">
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">界面主题</span>
                  <span class="settings__row-hint">配色随主题变化，跟随系统可自动切换深浅模式。</span>
                </div>
                <el-radio-group v-model="theme" aria-label="界面主题">
                  <el-radio-button v-for="option in themeOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </el-radio-button>
                </el-radio-group>
              </div>
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">收起侧边栏</span>
                  <span class="settings__row-hint">只显示图标，为内容保留更多空间。</span>
                </div>
                <el-switch v-model="navCollapsed" aria-label="收起侧边栏" />
              </div>
            </template>

            <!-- 外观与布局使用已有偏好，不改变主题色值。 -->
            <template v-if="item.id === 'appearance'">
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">展开时侧栏宽度</span
                  ><span class="settings__row-hint">也可拖动主导航右边缘，窄屏会自动限制宽度。</span>
                </div>
                <div class="settings__inline">
                  <el-input-number
                    v-model="sidebarWidth"
                    aria-label="侧栏宽度"
                    :min="SIDEBAR_MIN_WIDTH"
                    :max="SIDEBAR_MAX_WIDTH"
                    :step="8"
                  /><span class="settings__unit">px</span
                  ><el-button text @click="sidebarWidth = SIDEBAR_DEFAULT_WIDTH">复位</el-button>
                </div>
              </div>
            </template>

            <template v-if="item.id === 'workspace'">
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">任务默认视图</span
                  ><span class="settings__row-hint">与任务页的视图选择同步。</span>
                </div>
                <el-radio-group v-model="tasksView" aria-label="任务默认视图"
                  ><el-radio-button value="table">表格</el-radio-button
                  ><el-radio-button value="board">看板</el-radio-button></el-radio-group
                >
              </div>
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">知识库默认页面</span
                  ><span class="settings__row-hint">与知识库标签选择同步；带标签的分享链接优先。</span>
                </div>
                <el-select v-model="knowledgeTab" aria-label="知识库默认页面" class="settings__select"
                  ><el-option
                    v-for="option in knowledgeOptions"
                    :key="option.value"
                    :value="option.value"
                    :label="option.label"
                /></el-select>
              </div>
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">会话列表宽度</span
                  ><span class="settings__row-hint">与对话页拖动调整同步，手机端使用抽屉。</span>
                </div>
                <div class="settings__inline">
                  <el-input-number
                    v-model="sessionPanelWidth"
                    aria-label="会话列表宽度"
                    :min="CHAT_SESSION_PANEL_MIN_WIDTH"
                    :max="CHAT_SESSION_PANEL_MAX_WIDTH"
                    :step="10"
                  /><span class="settings__unit">px</span
                  ><el-button text @click="sessionPanelWidth = CHAT_SESSION_PANEL_DEFAULT_WIDTH">复位</el-button>
                </div>
              </div>
            </template>

            <!-- 对话 -->
            <template v-if="item.id === 'chat'">
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">新会话默认开启知识库增强</span>
                </div>
                <el-switch v-model="settings.knowledgeDefaultOn" aria-label="新会话默认开启知识库增强" />
              </div>
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">默认检索条数</span
                  ><span class="settings__row-hint">更多条目提供更多参考，也会占用更多上下文。</span>
                </div>
                <el-input-number v-model="settings.knowledgeTopK" aria-label="默认检索条数" :min="1" :max="20" />
              </div>
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">Enter 直接发送</span>
                  <span class="settings__row-hint">关闭后 Enter 换行，⌘ / Ctrl + Enter 发送</span>
                </div>
                <el-switch v-model="settings.enterSend" aria-label="Enter 直接发送" />
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
                  <span class="settings__row-label">默认思考程度</span
                  ><span class="settings__row-hint">仅对支持思考的模型生效，深度思考可能增加等待时间。</span>
                </div>
                <el-radio-group v-model="settings.thinkingLevel" aria-label="默认思考程度">
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
                  <span class="settings__row-label">默认提前提醒</span
                  ><span class="settings__row-hint">用于新建日程，不修改已有日程的提醒。</span>
                </div>
                <div class="settings__inline">
                  <el-input-number
                    v-model="settings.reminderLead"
                    aria-label="默认提前提醒分钟数"
                    :min="0"
                    :max="10080"
                    :step="5"
                  />
                  <span class="settings__unit">分钟</span>
                </div>
              </div>
            </template>

            <template v-if="item.id === 'calendar'">
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">浏览器通知</span
                  ><span class="settings__row-hint">权限由浏览器管理；后台推送需服务端启用。</span>
                </div>
                <CalendarNotifyBanner
                  :permission="notificationPermission"
                  :requesting="requestingPermission"
                  @enable="calendarStore.enableNotifications()"
                />
              </div>
              <div class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">常用提醒时间</span
                  ><span class="settings__row-hint">选择后立即作为新日程的默认值。</span>
                </div>
                <div class="settings__inline">
                  <el-button
                    v-for="lead in [0, 5, 15, 30, 60]"
                    :key="lead"
                    :type="settings.reminderLead === lead ? 'primary' : undefined"
                    plain
                    @click="settings.reminderLead = lead"
                    >{{ lead === 0 ? '准时' : `${lead} 分钟` }}</el-button
                  >
                </div>
              </div>
              <div class="settings__note">
                当前时区：{{
                  Intl.DateTimeFormat().resolvedOptions().timeZone
                }}。日历导入、导出与冲突检查可在日程页的“日历工具”中使用。
              </div>
            </template>
            <template v-if="item.id === 'shortcuts'">
              <div v-for="row in shortcutRows" :key="row.action" class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">{{ row.action }}</span
                  ><span class="settings__row-hint">{{ row.hint }}</span>
                </div>
                <kbd class="settings__key">{{ row.keys }}</kbd>
              </div>
            </template>
            <template v-if="item.id === 'data'">
              <MaintenanceView embedded />
              <div class="settings__reset">
                <div class="settings__row-text">
                  <span class="settings__row-label">重置全部偏好</span
                  ><span class="settings__row-hint">清除本浏览器的界面与会话偏好；业务数据、模型配置不受影响。</span>
                </div>
                <el-button :icon="RefreshLeft" @click="handleReset">重置偏好</el-button>
              </div>
            </template>

            <!-- 关于 -->
            <template v-if="item.id === 'about'">
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
              <div v-if="health?.components" class="settings__row">
                <div class="settings__row-text">
                  <span class="settings__row-label">依赖组件</span>
                  <span class="settings__row-hint">查看数据库与模型等依赖的当前状态。</span>
                </div>
                <div class="settings__inline">
                  <el-tag
                    v-for="(value, key) in health.components"
                    :key="key"
                    :type="dependencyTagType(value)"
                    effect="plain"
                    >{{ key }} · {{ value }}</el-tag
                  >
                </div>
              </div>
              <div class="settings__note">
                {{ health?.application || 'Personal AI OS' }} · Apache-2.0<br />自托管个人工作区。连接配置存于服务端，界面偏好仅保存在当前浏览器。
              </div>
              <div class="settings__inline">
                <el-button @click="router.push('/activity')">查看运行中心</el-button
                ><el-button @click="activeSection = 'data'">数据与维护</el-button>
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
 * 两列：固定 204px 的子导航 + 弹性内容。
 * 用 grid 而不是 flex：内容区的 minmax(0, 1fr) 能防止长端点地址
 * 把整列撑宽（flex 的 flex:1 在没有 min-width:0 时会被内容撑开）。
 */
.settings__layout {
  display: grid;
  grid-template-columns: 204px minmax(0, 1fr);
  gap: var(--os-space-5);
  grid-template-rows: minmax(0, 1fr);
  flex: 1;
  min-height: 0;
}

.settings__nav {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-1);
  align-self: start;
  max-height: 100%;
  min-height: 0;
  overflow-y: auto;
}

.settings__nav-item {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
  min-height: 56px;
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

.settings__panel--mcp,
.settings__panel--data {
  padding: 0;
  background: transparent;
  border: 0;
}

.settings__panel-head {
  padding-bottom: var(--os-space-4);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.settings__panel-title {
  margin: 0;
  font-size: var(--os-font-xl);
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.settings__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-4);
  flex-wrap: wrap;
  padding: var(--os-space-4) 0;
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
  flex: 1 1 220px;
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
  flex-wrap: wrap;
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

  .settings__nav-group,
  .settings__nav-copy small {
    display: none;
  }

  .settings__nav-item {
    white-space: nowrap;
    min-height: 40px;
    flex: none;
  }
}

@media (max-width: 768px) {
  .settings__row {
    align-items: flex-start;
  }

  .settings__panel:not(.settings__panel--mcp):not(.settings__panel--data) {
    padding: var(--os-space-4);
  }
}
.settings__nav-group {
  padding: var(--os-space-3) var(--os-space-3) var(--os-space-1);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}
.settings__nav-copy {
  display: grid;
  gap: 3px;
}
.settings__nav-copy small {
  font-size: 11px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.settings__panel-description {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
}
.settings__saved {
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
  padding-top: var(--os-space-2);
}
.settings__select {
  width: 180px;
}
.settings__note {
  padding: var(--os-space-4);
  border-radius: var(--os-radius-md);
  background: var(--el-fill-color-lighter);
  font-size: var(--os-font-sm);
  color: var(--el-text-color-secondary);
  line-height: 1.8;
}
.settings__key {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-sm);
  padding: 6px 10px;
  background: var(--el-fill-color-lighter);
  font: inherit;
  font-size: var(--os-font-sm);
}
.settings__reset {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-4);
  padding: var(--os-space-5);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-lg);
  background: var(--el-bg-color);
}
</style>
