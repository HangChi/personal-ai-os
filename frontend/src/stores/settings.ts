import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

import {
  mergeSettings,
  PREFERENCE_KEY_PREFIX,
  SETTINGS_KEYS,
  type SettingsState,
} from '@/lib/settings'

/**
 * 设置偏好状态（阶段 19，User Story 3）。
 *
 * 全部 localStorage（`os.settings.*`）——本地单用户应用，设置是「这台设备上
 * 我的习惯」，没有多端同步需求（spec Assumptions）。后端运行时配置
 * （模型 / 阈值）明确不在本阶段范围。
 *
 * 持久化同样走 watch 而不是 setter：设置页控件大量使用 v-model，
 * setter 会被绕过（阶段 18 的 navCollapsed 已踩过同一处）。
 */
export const useSettingsStore = defineStore('settings', () => {
  const settings = ref<SettingsState>(
    mergeSettings({
      knowledgeDefaultOn: readStored(SETTINGS_KEYS.knowledgeDefaultOn),
      knowledgeTopK: readStored(SETTINGS_KEYS.knowledgeTopK),
      enterSend: readStored(SETTINGS_KEYS.enterSend),
      reminderLead: readStored(SETTINGS_KEYS.reminderLead),
    }),
  )

  watch(
    settings,
    (next) => {
      localStorage.setItem(SETTINGS_KEYS.knowledgeDefaultOn, String(next.knowledgeDefaultOn))
      localStorage.setItem(SETTINGS_KEYS.knowledgeTopK, String(next.knowledgeTopK))
      localStorage.setItem(SETTINGS_KEYS.enterSend, String(next.enterSend))
      localStorage.setItem(SETTINGS_KEYS.reminderLead, String(next.reminderLead))
    },
    { deep: true },
  )

  /**
   * 重置全部本地偏好：清除 `os.` 前缀的所有键（主题 / 侧栏 / 各页视图 /
   * 会话级开关一并归零），然后刷新页面让所有 store 以默认值重启。
   *
   * 调用方负责二次确认（破坏性操作，FR-013）。
   */
  function resetAllPreferences(): void {
    const keys: string[] = []
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (key && key.startsWith(PREFERENCE_KEY_PREFIX)) {
        keys.push(key)
      }
    }
    keys.forEach((key) => localStorage.removeItem(key))
    location.reload()
  }

  return { settings, resetAllPreferences }
})

/** 读原始值；解析失败由 mergeSettings 收敛，这里只负责取。 */
function readStored(key: string): unknown {
  const raw = localStorage.getItem(key)
  if (raw === null) {
    return undefined
  }
  if (raw === 'true') {
    return true
  }
  if (raw === 'false') {
    return false
  }
  const asNumber = Number(raw)
  return Number.isNaN(asNumber) ? raw : asNumber
}
