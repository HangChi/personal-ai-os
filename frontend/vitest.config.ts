import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/**
 * 组件测试配置（P2，2026-10-27）。
 *
 * <h2>为什么是第二条测试链路，而不是替换现有的 .mjs</h2>
 * 现有 `test/*.test.mjs` 走 Node 原生 ESM + 自写断言，跑纯函数与 store，
 * 快且零依赖 —— 三百多条断言一秒内跑完，这个优势不该丢。
 * 但 `.vue` 文件需要 SFC 编译与 DOM，Node 原生做不到。
 *
 * <p>两者分工：
 * <ul>
 *   <li>{@code npm run test:logic} —— 纯逻辑，毫秒级，改代码时随手跑；</li>
 *   <li>{@code npm run test:component} —— 模板与交互，需要浏览器环境。</li>
 * </ul>
 *
 * <h2>为什么不复用 vite.config.ts</h2>
 * 主配置里开了 `optimizeDeps.noDiscovery`（那是为了 dev server 在沙箱里不崩），
 * 以及两个 unplugin —— 它们在测试环境没有意义，反而会注入未使用的自动导入语句。
 * 这里只保留 `vue` 插件与 `@` 别名，配置越小越不容易出意外。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    // happy-dom 比 jsdom 轻，且对 Element Plus 常用的 DOM API 覆盖足够
    environment: 'happy-dom',
    include: ['test/component/**/*.test.ts'],
    setupFiles: ['test/component/setup.ts'],
    // 组件测试不像纯函数那样瞬时完成，给足时间但不无限等待
    testTimeout: 10_000,
  },
})
