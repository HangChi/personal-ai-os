import { config } from '@vue/test-utils'
import ElementPlus from 'element-plus'

/**
 * 组件测试的全局环境。
 *
 * <h2>为什么要全量注册 Element Plus</h2>
 * 生产代码用的是 unplugin-vue-components 的**自动注册**：SFC 里写 `<el-collapse>`
 * 即可，不需要 import。测试环境没有那个插件，组件会解析失败并渲染成空标签 ——
 * 断言会变成「什么都找不到」，看起来像测试写错了。
 *
 * <p>这里直接全量注册：测试不关心包体积，只要求 DOM 行为与真实渲染一致。
 */
config.global.plugins = [ElementPlus]

/**
 * happy-dom 没有实现 matchMedia。
 *
 * <p>Element Plus 的若干组件（以及本项目的主题逻辑）在挂载时会查询
 * `prefers-color-scheme`，缺了这个 API 会直接抛错，而错误信息指向组件内部，
 * 很难联想到是环境缺 API。
 */
if (typeof window !== 'undefined' && typeof window.matchMedia !== 'function') {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as unknown as MediaQueryList
}
