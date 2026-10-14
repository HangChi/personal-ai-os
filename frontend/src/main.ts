import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import router from './router'
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

app.use(createPinia())
app.use(router)

app.mount('#app')
