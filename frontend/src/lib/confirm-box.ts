/**
 * 危险操作确认的**执行侧**（与 `lib/confirm.ts` 的纯选项分开）。
 *
 * <p>分成两个文件是为了让纯函数能被 `test/*.test.mjs` 直接 import：
 * 测试用 Node 的类型剥离跑源码，不经过 Vite —— 本文件 import 了 element-plus，
 * 混在一起会让每条断言都拖着整个组件库一起加载。
 */
import { ElMessageBox } from 'element-plus'

import { buildDangerConfirmOptions, type DangerConfirmOptions } from '@/lib/confirm'

/**
 * 弹出危险操作确认；用户确认返回 `true`，取消/关闭/Esc 返回 `false`。
 *
 * <p>返回值而不是抛出：调用方几乎总是「if (!ok) return」，用布尔让主流程是直的，
 * 不必为「用户改主意」这种正常路径套一层 try/catch。
 * 改造前七处调用各写一遍 `try { await ... } catch { return }`，
 * 还都放在业务逻辑前面 —— 一段只为「取消」存在的异常分支占掉了函数的第一屏。
 */
export async function confirmDanger(options: DangerConfirmOptions): Promise<boolean> {
  try {
    await ElMessageBox.confirm(options.message, options.title, buildDangerConfirmOptions(options))
    return true
  } catch {
    // 'cancel'（点取消）与 'close'（×/Esc）都落到这里，语义都是「不执行」
    return false
  }
}

/**
 * 非破坏性操作确认（重置偏好、导入数据这类「有后果但可恢复」的动作）。
 *
 * <p>与 {@link confirmDanger} 的差别只有确认键的配色：这类动作不该用红色 ——
 * 满屏红色确认键会让真正的删除失去分量。其余默认值（焦点、换行、遮罩）一致。
 */
export async function confirmAction(options: DangerConfirmOptions): Promise<boolean> {
  try {
    await ElMessageBox.confirm(options.message, options.title, {
      ...buildDangerConfirmOptions(options),
      confirmButtonType: 'primary',
    })
    return true
  } catch {
    return false
  }
}
