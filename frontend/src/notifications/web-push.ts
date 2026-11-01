import { ref } from 'vue'
import { requestData } from '@/api/http'

interface PushConfiguration {
  enabled: boolean
  publicKey: string | null
}

export const pushEnabled = ref(false)
export const pushSubscribed = ref(false)
export const pushBusy = ref(false)
export const pushError = ref<string | null>(null)
let publicKey: string | null = null

export function supportsPush(): boolean {
  return (
    typeof window !== 'undefined' &&
    window.isSecureContext &&
    'serviceWorker' in navigator &&
    'PushManager' in window &&
    'Notification' in window
  )
}

function applicationKey(value: string): Uint8Array<ArrayBuffer> {
  const raw = atob(value.replace(/-/g, '+').replace(/_/g, '/'))
  return Uint8Array.from(raw, (character) => character.charCodeAt(0))
}

function matchesKey(subscription: PushSubscription, key: string): boolean {
  const existing = subscription.options.applicationServerKey
  if (!existing) return false
  const expected = applicationKey(key)
  const actual = new Uint8Array(existing)
  return actual.length === expected.length && actual.every((value, index) => value === expected[index])
}

async function registration(): Promise<ServiceWorkerRegistration> {
  // 不等待 navigator.serviceWorker.ready：安装失败时它可能永不 resolve。
  const worker = await navigator.serviceWorker.register('/sw.js', { scope: '/', updateViaCache: 'none' })
  if (worker.active) return worker
  const installing = worker.installing ?? worker.waiting
  if (!installing) throw new Error('后台提醒组件安装失败，请刷新后重试')
  await new Promise<void>((resolve, reject) => {
    const timeout = setTimeout(() => finish(new Error('后台提醒组件安装超时，请刷新后重试')), 10_000)
    function finish(error?: Error): void {
      clearTimeout(timeout)
      installing?.removeEventListener('statechange', changed)
      if (error) reject(error)
      else resolve()
    }
    function changed(): void {
      if (installing?.state === 'activated') finish()
      else if (installing?.state === 'redundant') finish(new Error('后台提醒组件安装失败'))
    }
    installing.addEventListener('statechange', changed)
    changed()
  })
  return worker
}

async function saveSubscription(subscription: PushSubscription): Promise<void> {
  await requestData({ url: '/reminders/push/subscriptions', method: 'post', data: subscription.toJSON() })
}

/** 登录后探测；不会请求权限，也不会自动创建新订阅。 */
export async function refreshPushState(): Promise<void> {
  pushError.value = null
  try {
    const config = await requestData<PushConfiguration>({ url: '/reminders/push', method: 'get' })
    pushEnabled.value = config.enabled
    publicKey = config.publicKey
    pushSubscribed.value = false
    if (!supportsPush()) return
    const existing = await navigator.serviceWorker.getRegistration('/')
    const subscription = await existing?.pushManager.getSubscription()
    if (subscription && config.enabled) {
      if (!publicKey || !matchesKey(subscription, publicKey)) {
        pushError.value = '后台推送密钥已变更，请重新订阅'
        return
      }
      // 恢复备份/清理订阅后，自愈当前浏览器已有的订阅；不改变首次订阅时间。
      await saveSubscription(subscription)
      pushSubscribed.value = true
    }
  } catch {
    pushSubscribed.value = false
    pushError.value = '后台推送状态暂不可用，可稍后重试'
  }
}

/** 仅从用户点击中调用，先请求权限，避免失去浏览器用户手势。 */
export async function subscribePush(): Promise<void> {
  if (pushBusy.value) return
  pushBusy.value = true
  pushError.value = null
  let created: PushSubscription | null = null
  try {
    if (!supportsPush()) throw new Error('当前环境不支持后台推送，请使用 localhost 或 HTTPS')
    if (!pushEnabled.value || !publicKey) throw new Error('服务端尚未启用后台推送')
    const permission = await Notification.requestPermission()
    if (permission !== 'granted') throw new Error('请在浏览器站点设置中允许通知')
    const worker = await registration()
    let subscription = await worker.pushManager.getSubscription()
    if (subscription && !matchesKey(subscription, publicKey)) {
      await requestData({
        url: '/reminders/push/subscriptions',
        method: 'delete',
        data: { endpoint: subscription.endpoint },
      })
      await subscription.unsubscribe()
      subscription = null
    }
    if (!subscription) {
      subscription = await worker.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: applicationKey(publicKey),
      })
      created = subscription
    }
    await saveSubscription(subscription)
    pushSubscribed.value = true
  } catch (error) {
    // 仅回滚本次刚创建的订阅，已有订阅保留以便重试。
    if (created) await created.unsubscribe().catch(() => false)
    pushError.value = error instanceof Error ? error.message : '订阅后台推送失败'
  } finally {
    pushBusy.value = false
  }
}

export async function unsubscribePush(): Promise<void> {
  if (pushBusy.value) return
  pushBusy.value = true
  pushError.value = null
  try {
    const worker = await navigator.serviceWorker.getRegistration('/')
    const subscription = await worker?.pushManager.getSubscription()
    if (subscription) {
      await requestData({
        url: '/reminders/push/subscriptions',
        method: 'delete',
        data: { endpoint: subscription.endpoint },
      })
      await subscription.unsubscribe()
    }
    pushSubscribed.value = false
  } catch {
    pushError.value = '取消订阅失败，请重试'
  } finally {
    pushBusy.value = false
  }
}
