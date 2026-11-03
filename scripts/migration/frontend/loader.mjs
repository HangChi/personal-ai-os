import { register } from 'node:module'

// 与常规单测的 HTTP 桩分离：本套件加载原客户端并连接 Go 临时服务。
register('./hooks.mjs', import.meta.url)
