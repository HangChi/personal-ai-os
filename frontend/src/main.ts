import { createPinia } from 'pinia'
import { createApp } from 'vue'
import ElementPlus from 'element-plus'

import App from './App.vue'
import router from './router'
import './styles/main.css'
import 'element-plus/dist/index.css'

/**
 * 前端入口。
 *
 * 阶段 0 采用 Element Plus 全量引入（见 research.md R-08）：
 * 以最小的配置面换取「一次构建通过」的确定性，按需引入列为阶段 1 的优化项。
 * 中文语言包同理，留待阶段 1 与按需引入一并处理。
 */
const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus)

app.mount('#app')
