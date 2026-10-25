import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import './styles/main.css'

/**
 * 前端入口。
 *
 * Element Plus 按需引入（2026-10-14，宪法 follow-up TODO #2 的落地）：
 * 组件与命令式 API（ElMessage 等）由 vite.config.ts 的 unplugin 自动解析，
 * 不再全量注册——全量引入会把约 990 KB 无关组件塞进主包。
 * 语言包沿用中文默认，无需额外配置。
 */
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

app.mount('#app')
