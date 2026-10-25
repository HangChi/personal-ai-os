/**
 * 主题解析纯函数（阶段 18，contracts C-02）。
 *
 * 与 store 的分界：本文件只做「偏好 + 系统明暗 → 解析值」的纯计算，
 * 不碰 DOM / localStorage（那些在 stores/ui.ts）。纯函数才能被 .mjs 单测覆盖。
 */

/** 用户偏好三态；system 表示跟随操作系统 */
export type ThemePreference = 'light' | 'dark' | 'system'

/** 解析后的实际主题（二值） */
export type ResolvedTheme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'os.theme'

/**
 * 解析偏好为实际主题。
 *
 * @param preference 用户偏好（含未初始化时的缺省）
 * @param systemDark 操作系统是否为深色（matchMedia 结果）
 */
export function resolveTheme(preference: ThemePreference, systemDark: boolean): ResolvedTheme {
  if (preference === 'system') {
    return systemDark ? 'dark' : 'light'
  }
  return preference
}

/** localStorage 值的校验与归一：非法值（含历史脏数据）回落 system */
export function normalizePreference(raw: string | null): ThemePreference {
  return raw === 'light' || raw === 'dark' || raw === 'system' ? raw : 'system'
}
