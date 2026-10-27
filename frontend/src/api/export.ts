import { http } from '@/api/http'

/**
 * 数据导出（阶段 12）。
 *
 * 与其它 api 模块不同，这里**不用** `requestData`：导出端点刻意不包 `{code,data}`
 * 信封（spec FR-001），它是文件下载而不是数据查询。
 */

/**
 * 从 `Content-Disposition` 解析文件名。
 *
 * 单独抽成纯函数是为了可测：真正触发下载要 DOM，而文件名解析的正确性
 * （引号、UTF-8 编码、缺失头）与 DOM 无关。
 */
export function resolveFilename(disposition: string | undefined, fallback: string): string {
  if (!disposition) {
    return fallback
  }
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition)
  return match ? decodeURIComponent(match[1].trim()) : fallback
}

/** 兜底文件名，UTC 时间戳（与后端生成规则一致） */
export function defaultFilename(now: Date = new Date()): string {
  const pad = (value: number): string => String(value).padStart(2, '0')
  const stamp =
    `${now.getUTCFullYear()}${pad(now.getUTCMonth() + 1)}${pad(now.getUTCDate())}` +
    `-${pad(now.getUTCHours())}${pad(now.getUTCMinutes())}${pad(now.getUTCSeconds())}`
  return `personal-ai-os-export-${stamp}.json`
}

/**
 * 导出请求的独立超时。
 *
 * <p>全局实例的 10 秒是给「交互式查询」定的：用户在等，慢就该报错。
 * 而导出是打包**全部**业务数据，耗时随数据量线性增长 —— 沿用 10 秒的结果是
 * 「数据多到正好该备份」的那一刻必然失败，而这恰恰是最不该失败的场景。
 *
 * <p>仍给上限而不是 0（永不超时）：请求真的挂死时，界面不该一直转圈。
 */
const EXPORT_TIMEOUT_MILLIS = 120_000

/**
 * 请求并触发浏览器下载。
 *
 * 顺序是刻意的：**先等请求成功，再创建 ObjectURL**。
 * 反过来（先建链接再填内容）会在失败时留下一个 0 字节文件，
 * 用户以为备份成功，实际上是空的 —— 比报错严重得多。
 *
 * @returns 实际使用的文件名
 */
export async function downloadExport(): Promise<string> {
  const response = await http.get('/export', {
    responseType: 'blob',
    timeout: EXPORT_TIMEOUT_MILLIS,
  })
  const filename = resolveFilename(response.headers['content-disposition'] as string | undefined, defaultFilename())

  const url = URL.createObjectURL(response.data as Blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    link.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
  return filename
}
