import { defineStore } from 'pinia'
import { ref } from 'vue'
import { executeDirectTool, listDirectTools, type DirectTool, type DirectToolExecution } from '@/api/chat-tools'
import { commandSignature } from '@/lib/chat-commands'

export interface CommandEntry {
  id: string
  input: string
  busy: boolean
  execution?: DirectToolExecution
  error?: string
}

/** Store only argument hashes and request IDs, never raw tool arguments. */
export const useChatCommandsStore = defineStore('chat-commands', () => {
  const catalog = ref<DirectTool[]>([])
  const catalogLoading = ref(false)
  const catalogError = ref('')
  const busy = ref(false)
  const entries = ref<Record<string, CommandEntry[]>>({})

  async function loadCatalog(): Promise<void> {
    if (catalogLoading.value) return
    catalogLoading.value = true
    catalogError.value = ''
    try {
      catalog.value = await listDirectTools()
    } catch (error) {
      catalogError.value = error instanceof Error ? error.message : '工具清单加载失败'
    } finally {
      catalogLoading.value = false
    }
  }

  async function execute(
    conversation: string,
    input: string,
    tool: string,
    args: Record<string, unknown>,
  ): Promise<DirectToolExecution | null> {
    if (busy.value) return null
    busy.value = true
    const entry: CommandEntry = { id: crypto.randomUUID(), input, busy: true }
    entries.value[conversation] = [...(entries.value[conversation] ?? []).slice(-9), entry]
    const current = entries.value[conversation]!.at(-1)!
    let sent = false
    try {
      const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(commandSignature(tool, args)))
      const signature = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
      const storageKey = 'os:chat-command-attempt:' + conversation
      const readAttempts = (): Record<string, string> => {
        const value: unknown = JSON.parse(sessionStorage.getItem(storageKey) ?? '{}')
        if (
          !value ||
          typeof value !== 'object' ||
          Array.isArray(value) ||
          Object.entries(value).some(
            ([key, id]) =>
              !/^[a-f0-9]{64}$/.test(key) ||
              typeof id !== 'string' ||
              !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(id),
          )
        )
          throw new Error('快捷操作标识损坏，请先核对活动记录')
        return value as Record<string, string>
      }
      const attempts = readAttempts()
      const key = attempts[signature] ?? crypto.randomUUID()
      attempts[signature] = key
      // Persist before sending: a lost response / page refresh must reuse the same ID.
      sessionStorage.setItem(storageKey, JSON.stringify(attempts))
      sent = true
      const execution = await executeDirectTool(conversation, tool, args, key)
      current.execution = execution
      if (['OK', 'DUPLICATE', 'REQUIRES_CONFIRMATION', 'REJECTED'].includes(execution.result.status)) {
        // Failure to clear keeps the old identity and is safe to replay.
        try {
          const latest = readAttempts()
          if (latest[signature] === key) delete latest[signature]
          sessionStorage.setItem(storageKey, JSON.stringify(latest))
        } catch {
          /* retain receipt identity */
        }
      }
      return execution
    } catch (error) {
      const message = error instanceof Error ? error.message : '请求失败'
      current.error = sent
        ? message + '。结果未确认，请先核对目标状态；再次提交相同操作会沿用原标识，系统不会自动重试。'
        : message + '。无法保存操作标识，本次未发送。'
      return null
    } finally {
      current.busy = false
      busy.value = false
    }
  }

  return { catalog, catalogLoading, catalogError, entries, busy, loadCatalog, execute }
})
