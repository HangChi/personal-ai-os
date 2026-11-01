import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'

const requestData = vi.hoisted(() => vi.fn())
vi.mock('@/api/http', () => ({ requestData }))

const key = btoa(String.fromCharCode(...new Uint8Array(65).fill(4)))

function setupBrowser(existing = false) {
  const subscription = {
    endpoint: 'https://fcm.googleapis.com/test',
    options: { applicationServerKey: new Uint8Array(65).fill(4).buffer },
    toJSON: () => ({ endpoint: 'https://fcm.googleapis.com/test', keys: { p256dh: 'key', auth: 'auth' } }),
    unsubscribe: vi.fn().mockResolvedValue(true),
  }
  const worker = {
    active: {},
    pushManager: {
      getSubscription: vi.fn().mockResolvedValue(existing ? subscription : null),
      subscribe: vi.fn().mockResolvedValue(subscription),
    },
  }
  const register = vi.fn().mockResolvedValue(worker)
  vi.stubGlobal('isSecureContext', true)
  vi.stubGlobal('PushManager', function () {})
  vi.stubGlobal('Notification', { permission: 'default', requestPermission: vi.fn().mockResolvedValue('granted') })
  Object.defineProperty(navigator, 'serviceWorker', {
    configurable: true,
    value: { register, getRegistration: vi.fn().mockResolvedValue(worker) },
  })
  return { worker, register, subscription }
}

beforeEach(() => {
  vi.resetModules()
  requestData.mockReset()
  requestData.mockImplementation(async (request) =>
    request.method === 'get' ? { enabled: true, publicKey: key } : 'subscription-id',
  )
})
afterEach(() => vi.unstubAllGlobals())

describe('后台推送显式订阅', () => {
  it('首次探测不会请求权限或创建订阅', async () => {
    const { register, worker } = setupBrowser()
    const push = await import('@/notifications/web-push')
    await push.refreshPushState()
    expect(push.pushEnabled.value).toBe(true)
    expect(Notification.requestPermission).not.toHaveBeenCalled()
    expect(register).not.toHaveBeenCalled()
    expect(worker.pushManager.subscribe).not.toHaveBeenCalled()
  })
  it('服务端默认关闭时不注册后台组件', async () => {
    const { register } = setupBrowser()
    requestData.mockResolvedValue({ enabled: false, publicKey: null })
    const push = await import('@/notifications/web-push')
    await push.refreshPushState()
    await push.subscribePush()
    expect(push.pushSubscribed.value).toBe(false)
    expect(register).not.toHaveBeenCalled()
    expect(push.pushError.value).toContain('尚未启用')
  })
  it('用户同意后保存加密订阅；接口失败回滚新订阅', async () => {
    const { subscription } = setupBrowser()
    const push = await import('@/notifications/web-push')
    await push.refreshPushState()
    requestData.mockRejectedValueOnce(new Error('保存失败'))
    await push.subscribePush()
    expect(push.pushSubscribed.value).toBe(false)
    expect(subscription.unsubscribe).toHaveBeenCalledOnce()
    expect(push.pushError.value).toBe('保存失败')
    expect(push.pushBusy.value).toBe(false)
  })
  it('拒绝通知权限时不会注册服务或保存订阅', async () => {
    const { register } = setupBrowser()
    const push = await import('@/notifications/web-push')
    await push.refreshPushState()
    vi.mocked(Notification.requestPermission).mockResolvedValue('denied')
    await push.subscribePush()
    expect(register).not.toHaveBeenCalled()
    expect(push.pushError.value).toContain('允许通知')
  })
  it('恢复已有订阅且可明确取消', async () => {
    const { subscription } = setupBrowser(true)
    const push = await import('@/notifications/web-push')
    await push.refreshPushState()
    expect(push.pushSubscribed.value).toBe(true)
    await push.unsubscribePush()
    expect(requestData).toHaveBeenLastCalledWith({
      url: '/reminders/push/subscriptions',
      method: 'delete',
      data: { endpoint: subscription.endpoint },
    })
    expect(subscription.unsubscribe).toHaveBeenCalledOnce()
    expect(push.pushSubscribed.value).toBe(false)
  })
  it('配置密钥改变后重建订阅，避免页面显示成功但平台拒绝推送', async () => {
    const { subscription, worker } = setupBrowser(true)
    subscription.options.applicationServerKey = new Uint8Array(65).fill(5).buffer
    const push = await import('@/notifications/web-push')
    await push.refreshPushState()
    expect(push.pushSubscribed.value).toBe(false)
    expect(push.pushError.value).toContain('密钥已变更')
    await push.subscribePush()
    expect(subscription.unsubscribe).toHaveBeenCalledOnce()
    expect(worker.pushManager.subscribe).toHaveBeenCalledOnce()
    expect(push.pushSubscribed.value).toBe(true)
  })
})
