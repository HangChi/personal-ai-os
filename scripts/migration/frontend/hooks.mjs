import { readFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { resolve as pathResolve } from 'node:path'
import { createRequire } from 'node:module'
const ts = createRequire(new URL('../../../frontend/package.json', import.meta.url))('typescript')

const root = fileURLToPath(new URL('../../../frontend/src/', import.meta.url))
export async function resolve(specifier, context, next) {
  if (specifier.startsWith('@/')) {
    return { url: pathToFileURL(pathResolve(root, `${specifier.slice(2)}.ts`)).href, shortCircuit: true }
  }
  if (specifier.startsWith('.') && context.parentURL) {
    const url = new URL(specifier, context.parentURL)
    if (!existsSync(fileURLToPath(url)) && existsSync(`${fileURLToPath(url)}.ts`)) {
      return { url: `${url.href}.ts`, shortCircuit: true }
    }
  }
  return next(specifier, context)
}
export async function load(url, context, next) {
  if (!url.endsWith('.ts')) return next(url, context)
  let source = await readFile(new URL(url), 'utf8')
  // 唯一测试适配是 Vite 构建变量；API、Axios、CSRF、SSE 解析均用原实现。
  source = source.replaceAll('import.meta.env.VITE_API_BASE_URL', 'process.env.GO_P4_BASE_URL')
  return { format: 'module', source: ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText, shortCircuit: true }
}
