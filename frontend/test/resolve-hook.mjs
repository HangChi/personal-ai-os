import { existsSync, statSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, join } from 'node:path'

/**
 * ESM 解析钩子：让 Node 能直接 import 前端的 TS 源码（Vite 风格导入）。
 *
 * 三件事：
 * 1. `@/api/http` 整体别名到桩（stub-api.mjs）——它是唯一使用 import.meta.env 的模块，
 *    改源码或转译 import.meta.env 都更脆，整体换桩只保留被测模块真正用到的导出；
 * 2. `@/x` 映射到 src/x（补 .ts / index.ts 扩展名——Node ESM 不认 Vite 的省略写法）；
 * 3. 相对导入补扩展名（同上）。
 *
 * 裸标识符（vue / pinia / axios）放行，由 Node 从 node_modules 解析。
 */

const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = join(HERE, '..', 'src')
const STUB = pathToFileURL(join(HERE, 'stub-api.mjs')).href

function resolveFile(basePath) {
  const candidates = [basePath, `${basePath}.ts`, join(basePath, 'index.ts')]
  for (const candidate of candidates) {
    try {
      if (existsSync(candidate) && statSync(candidate).isFile()) {
        return pathToFileURL(candidate).href
      }
    } catch {
      // 候选不存在，继续下一个
    }
  }
  return null
}

export async function resolve(specifier, context, next) {
  if (specifier === '@/api/http') {
    // 注意：不显式给 format——显式指定会让 Node 跳过 .ts 的类型剥离，
    // 加载带类型注解的源文件会直接 SyntaxError。格式交给 Node 按扩展名判断
    // （frontend/package.json 是 "type": "module"，.ts 即 ESM + 类型剥离）。
    return { url: STUB, shortCircuit: true }
  }
  if (specifier.startsWith('@/')) {
    const url = resolveFile(join(SRC, specifier.slice(2)))
    if (url) {
      return { url, shortCircuit: true }
    }
  }
  try {
    return await next(specifier, context)
  } catch (err) {
    if (specifier.startsWith('.') && context.parentURL) {
      const url = resolveFile(fileURLToPath(new URL(specifier, context.parentURL)))
      if (url) {
        return { url, shortCircuit: true }
      }
    }
    throw err
  }
}
