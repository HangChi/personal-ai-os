import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

import {
  normalizePreference,
  resolveTheme,
  THEME_STORAGE_KEY,
  type ResolvedTheme,
  type ThemePreference,
} from '@/lib/theme'

/**
 * UI 偏好状态（阶段 18）：主题三态 + 各页面视图偏好。
 *
 * 主题落地形态（contracts C-02）：
 * - `documentElement.dataset.theme` = 解析值（供 CSS 与测试读取）
 * - `documentElement.classList.toggle('dark', ...)`：EP 暗色 css-vars 的选择器是 `html.dark`
 *
 * ⚠️ 首帧必须同步应用主题，不能在 onMounted 里做（会白闪）。
 * 同步部分在 main.ts 挂载前执行 + index.html 内联引导脚本兜底；
 * 本 store 只负责「挂载后」的响应式切换。
 */
export const useUiStore = defineStore('ui', () => {
  /** 用户偏好（三态） */
  const theme = ref<ThemePreference>(normalizePreference(localStorage.getItem(THEME_STORAGE_KEY)))
  /** 解析后的实际主题 */
  const resolvedTheme = ref<ResolvedTheme>(resolveTheme(theme.value, prefersDark()))

  /** 任务页视图（C-05 / FR-010）：table | board */
  const tasksView = ref<'table' | 'board'>(localStorage.getItem('os.tasks.view') === 'board' ? 'board' : 'table')
  /** 知识库 Tab（T026）：documents | search | ask */
  const knowledgeTab = ref<'documents' | 'search' | 'ask'>(
    localStorage.getItem('os.knowledge.tab') === 'search'
      ? 'search'
      : localStorage.getItem('os.knowledge.tab') === 'ask'
        ? 'ask'
        : 'documents',
  )

  /** 侧栏导航折叠（阶段 18 追加）：true = 纯图标模式，localStorage 持久化 */
  const navCollapsed = ref(localStorage.getItem('os.nav.collapsed') === '1')

  const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')

  // system 偏好下跟随操作系统；显式偏好不受系统变化影响。
  // 监听器注册在 store 初始化时（store 在 main.ts 同步创建），组件卸载不失效。
  mediaQuery.addEventListener('change', (event) => {
    if (theme.value === 'system') {
      resolvedTheme.value = event.matches ? 'dark' : 'light'
    }
  })

  function prefersDark(): boolean {
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  }

  /** 切换主题：light/dark 之间翻转；从 system 出发时按当前解析值取反 */
  function toggleTheme(): void {
    theme.value = resolvedTheme.value === 'dark' ? 'light' : 'dark'
  }

  // 偏好或解析值变化 → 落盘 + 应用到 <html>。
  // 写回是幂等的（同值不触发 watch），不会自反馈。
  watch(
    theme,
    (next) => {
      localStorage.setItem(THEME_STORAGE_KEY, next)
      resolvedTheme.value = resolveTheme(next, prefersDark())
    },
    { immediate: false },
  )

  watch(resolvedTheme, (next) => {
    document.documentElement.dataset.theme = next
    document.documentElement.classList.toggle('dark', next === 'dark')
  })

  /*
   * 视图偏好的持久化走 watch 而不是 setter：el-tabs / el-radio-group 的 v-model
   * 会直接写 ref，绕过任何 setter 函数——只提供 setter 的话，通过 v-model 的
   * 修改永远不会落盘（刷新即丢）。watch 对两条写入路径（v-model 与命令式）都生效。
   */
  watch(tasksView, (next) => localStorage.setItem('os.tasks.view', next))
  watch(knowledgeTab, (next) => localStorage.setItem('os.knowledge.tab', next))
  watch(navCollapsed, (next) => localStorage.setItem('os.nav.collapsed', next ? '1' : '0'))

  return {
    theme,
    resolvedTheme,
    tasksView,
    knowledgeTab,
    navCollapsed,
    toggleTheme,
  }
})
