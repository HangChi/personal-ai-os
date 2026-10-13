/// <reference types="vite/client" />

/**
 * 让 TypeScript 认识 .vue 单文件组件的类型。
 */
declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

interface ImportMetaEnv {
  /** 后端 API 基路径，默认 /api/v1（开发期由 Vite 代理转发） */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
