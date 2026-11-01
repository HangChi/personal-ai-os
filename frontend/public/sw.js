/* global self, clients, URL */
// 只处理提醒，不缓存网页、接口或登录数据。
self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (event) => event.waitUntil(clients.claim()))

self.addEventListener('push', (event) => {
  let payload = { title: '到点提醒', body: '打开日程查看提醒', tag: 'reminder', url: '/calendar' }
  try {
    if (event.data) payload = { ...payload, ...event.data.json() }
  } catch {
    // 无法解析负载也显示一条可打开的通知，避免静默丢失。
  }
  event.waitUntil(
    self.registration.showNotification(payload.title, {
      body: payload.body,
      tag: payload.tag,
      data: { url: '/calendar' },
    }),
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  event.waitUntil(
    (async () => {
      const destination = new URL('/calendar', self.location.origin).href
      const windows = await clients.matchAll({ type: 'window', includeUncontrolled: true })
      const existing = windows.find((client) => new URL(client.url).origin === self.location.origin)
      if (existing) {
        await existing.navigate(destination)
        await existing.focus()
      } else {
        await clients.openWindow(destination)
      }
    })(),
  )
})
