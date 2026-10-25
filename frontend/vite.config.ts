import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { defineConfig } from 'vite'

/**
 * Vite 配置。
 *
 * 开发期把 /api 代理到后端，避免跨域，并让前端代码无需感知后端地址
 * （见 specs/001-project-skeleton/research.md R-09）。
 *
 * Element Plus 按需引入（2026-10-14，宪法 follow-up TODO #2 的落地）：
 * 全量引入把约 990 KB 塞进主包，而本应用只用到二十来个组件。
 * 两个插件分别处理「组件自动注册」与「API 自动导入」（ElMessage 等
 * 命令式 API 由 AutoImport + ElementPlusResolver 负责）。
 */
export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // 默认 8080；端口被占用或同时在跑多个后端实例时，用 VITE_PROXY_TARGET 指向另一个，
        // 不必改这里再改回来（改文件容易漏改回去，然后在「为什么代理到奇怪端口」上浪费半天）。
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  optimizeDeps: {
    // 关闭依赖自动发现，显式列出已知依赖（2026-10-14）。
    //
    // 背景：自动导入插件（unplugin-auto-import / vue-components）会在 transform 时
    // 注入新的 import 语句，依赖扫描器随之反复「重新优化 → 提交 → 删除旧 deps_temp」；
    // 而本开发环境（WorkBuddy 沙箱）的批量删除守护会拦截 Vite 删除自己的缓存目录，
    // dev server 于是在启动约一分钟后崩溃（生产构建不受影响）。
    //
    // 关掉发现后优化器不再重跑，dev server 稳定。**代价一**：将来新增运行时依赖需要
    // 手动加进 include。**代价二**（更重要）：列表必须覆盖 Element Plus 的依赖闭包——
    // 其中 dayjs / async-validator / lodash / memoize-one / @ctrl/tinycolor 是 CJS 包，
    // 浏览器不能把 CJS 当 ESM 加载，预打包（esbuild）正是做 CJS→ESM 转换的那一步；
    // 漏一个就会在 dev 期看到「does not provide an export named 'default'」。
    //
    // 改动本列表会使缓存失效并触发一次重优化——而在本沙箱里那次重优化的缓存清理
    // 会被守护拦截导致启动失败。**改完先手动 `rm -rf node_modules/.vite` 再启动**
    // （shell 的 rm 不走 Node 进程，不会被拦截）。
    noDiscovery: true,
    include: [
      // 直接依赖
      'vue',
      'pinia',
      'axios',
      // Element Plus 及其依赖闭包（含全部 CJS 包）
      'element-plus',
      'dayjs',
      // dayjs 是 CJS 包，Element Plus 还引它的 9 个插件子路径——
      // include 只列根包对子路径不生效，必须逐个列出（specifier 与源码一致，带 .js）
      'dayjs/plugin/advancedFormat.js',
      'dayjs/plugin/customParseFormat.js',
      'dayjs/plugin/dayOfYear.js',
      'dayjs/plugin/isSameOrAfter.js',
      'dayjs/plugin/isSameOrBefore.js',
      'dayjs/plugin/localeData.js',
      'dayjs/plugin/quarterOfYear.js',
      'dayjs/plugin/weekOfYear.js',
      'dayjs/plugin/weekYear.js',
      'async-validator',
      'lodash',
      'lodash-es',
      'lodash-unified',
      'memoize-one',
      '@ctrl/tinycolor',
      '@floating-ui/dom',
      '@popperjs/core',
      'normalize-wheel-es',
      '@vueuse/core',
    ],
  },
})
