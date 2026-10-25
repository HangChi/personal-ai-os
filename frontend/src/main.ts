import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from '@/api/http'
import { normalizePreference, resolveTheme, THEME_STORAGE_KEY } from '@/lib/theme'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
// Element Plus 官方暗色变量表：只在 html.dark 下生效（EP 约定），
// 与 tokens.css 的 html.dark 阴影修正配合（specs/018 research R-04）
import 'element-plus/theme-chalk/dark/css-vars.css'
// highlight.js 亮色主题（github）；暗色覆盖见 styles/hljs-dark.css（R-02）
import 'highlight.js/styles/github.css'
import './styles/hljs-dark.css'
import './styles/main.css'

/**
 * 前端入口。
 *
 * Element Plus 按需引入（2026-10-14，宪法 follow-up TODO #2 的落地）：
 * 组件与命令式 API（ElMessage 等）由 vite.config.ts 的 unplugin 自动解析，
 * 不再全量注册——全量引入会把约 990 KB 无关组件塞进主包。
 * 语言包沿用中文默认，无需额外配置。
 *
 * 主题首帧同步应用（阶段 18，contracts C-02 反模式）：
 * 必须在 createApp().mount() **之前**把解析值写到 <html>，否则首帧按默认浅色渲染、
 * 挂载后才翻深色——用户看到一次白闪。index.html 里还有一段内联引导脚本兜底
 * （CSS 未加载完成的窗口期），两处逻辑保持一致：读同一个 key、同一个解析规则。
 */
function applyThemeBeforeMount(): void {
  const preference = normalizePreference(localStorage.getItem(THEME_STORAGE_KEY))
  const systemDark = window.matchMedia('(prefers-color-scheme: dark)').matches
  const resolved = resolveTheme(preference, systemDark)
  document.documentElement.dataset.theme = resolved
  document.documentElement.classList.toggle('dark', resolved === 'dark')
}

applyThemeBeforeMount()

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

// 401 → 清本地登录态并回登录页。
// 用注册回调而不是让 http.ts 直接 import store：那会形成循环依赖。
setUnauthorizedHandler(() => {
  const auth = useAuthStore(pinia)
  auth.clear()
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    void router.push({ name: 'login', query: { redirect: current.fullPath } })
  }
})

// UI store 提前实例化：注册 matchMedia 监听，让 system 偏好实时跟随系统主题变化
useUiStore(pinia)

app.mount('#app')
