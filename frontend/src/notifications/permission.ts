/**
 * 浏览器通知权限的读取与请求。
 *
 * <h2>为什么权限请求单独放一个模块</h2>
 * `requestPermission()` **必须在用户手势里调用**。把它封装在这里并在调用处只暴露
 * 「点按钮才调」这一条路径，比在每个组件里各自写一遍更不容易被误用 ——
 * 页面加载时自动请求会把权限一次性用掉，且多数浏览器会直接记成 `denied`。
 */

/** 权限状态。`unsupported` 是本项目加的：部分环境根本没有 Notification。 */
export type NotificationPermissionState = 'granted' | 'denied' | 'default' | 'unsupported'

/**
 * 读取当前权限。
 *
 * <p>不缓存：浏览器是唯一权威来源。本地存一份会出现「页面说已开启、浏览器说已阻止」的分歧，
 * 而用户只会相信前者 —— 那是最糟的失败方式。
 */
export function readPermission(): NotificationPermissionState {
  if (typeof window === 'undefined' || !('Notification' in window)) {
    return 'unsupported'
  }
  return Notification.permission as NotificationPermissionState
}

/**
 * 请求权限。**只能由用户手势触发。**
 *
 * <p>`denied` 时直接返回，不再调用浏览器 API —— 此时 `requestPermission()` 不会弹窗，
 * 只会立刻返回 `denied`。继续调用只会让调用方以为"还有机会"。
 *
 * @returns 请求后的权限状态；不支持时返回 `unsupported`
 */
export async function requestPermission(): Promise<NotificationPermissionState> {
  const current = readPermission()
  if (current === 'unsupported' || current === 'denied') {
    return current
  }

  try {
    return (await Notification.requestPermission()) as NotificationPermissionState
  } catch {
    // 某些环境（iframe / 权限策略）会抛异常，回落到读取真实状态
    return readPermission()
  }
}
