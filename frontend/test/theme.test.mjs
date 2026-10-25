import { eq, check } from './assert.mjs'

/**
 * 主题解析（阶段 18，contracts C-02）。
 *
 * 断言口径与 spec User Story 1 一致：三态偏好 × 系统明暗的全组合，
 * 外加 normalizePreference 对脏数据的回落（localStorage 可能被手改坏）。
 */

const { resolveTheme, normalizePreference } = await import('@/lib/theme')

// ---- resolveTheme：全组合 ----

eq('system + 系统浅色 → light', resolveTheme('system', false), 'light')
eq('system + 系统深色 → dark', resolveTheme('system', true), 'dark')
eq('显式 light 不被系统深色覆盖', resolveTheme('light', true), 'light')
eq('显式 dark 不被系统浅色覆盖', resolveTheme('dark', false), 'dark')

// ---- normalizePreference：脏数据回落 system ----

eq('合法值 light 原样', normalizePreference('light'), 'light')
eq('合法值 dark 原样', normalizePreference('dark'), 'dark')
eq('合法值 system 原样', normalizePreference('system'), 'system')
eq('null 回落 system', normalizePreference(null), 'system')
eq('历史脏值回落 system', normalizePreference('DARK'), 'system')
eq('空串回落 system', normalizePreference(''), 'system')
eq('注入值回落 system', normalizePreference('light"><script>'), 'system')

// ---- 不变量：解析结果只能是二值（供 dataset.theme / class 使用）----

function alwaysBinary() {
  for (const pref of ['light', 'dark', 'system']) {
    for (const systemDark of [false, true]) {
      const resolved = resolveTheme(pref, systemDark)
      if (resolved !== 'light' && resolved !== 'dark') {
        return false
      }
    }
  }
  return true
}

check('任意组合解析值都是 light|dark', alwaysBinary())
