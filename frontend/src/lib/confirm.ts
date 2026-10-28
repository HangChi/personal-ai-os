/**
 * 危险操作的二次确认（阶段 27 收敛）。
 *
 * <p>改造前每个模块各写一遍 `ElMessageBox.confirm`，同一个动作在七个地方
 * 有七种写法，且**三处一致性的问题同时存在**：
 *
 * <ol>
 *   <li>确认按钮没有危险色。`type: 'warning'` 只换了标题左侧的图标，
 *       「删除」按钮仍是主色实底 —— 与旁边的「取消」同为蓝色，
 *       危险动作在读秒级的判断里并不突出。</li>
 *   <li>焦点默认落在确认按钮上。EP 的 MessageBox 内部 `state.autofocus` 默认
 *       为 true，打开时把焦点交给 confirm 按钮；此时按一下空格或回车就真的删了。
 *       「误触」在这里不是理论风险 —— 弹窗常因误点行内「删除」而出现，
 *       手指/键盘还停在原来的位置。</li>
 *   <li>`\n` 不换行。message 走的是 `textContent` + 默认 `white-space: normal`，
 *       写进去的换行符被折叠成空格。几处文案用 `\n\n` 分隔「结论」与「细节」，
 *       实际显示成一整段长句。</li>
 * </ol>
 *
 * <p>因此这里不是「再包一层省点打字」，而是把上面三条一次性固化成默认值：
 * 危险按钮、焦点不在确认键、换行真的生效。业务侧只剩「说什么」。
 */

/**
 * `ElMessageBox.confirm` 的选项子集。
 *
 * <p>刻意不直接从 element-plus 引 `ElMessageBoxOptions`：这个模块要被 Node 的
 * 纯函数测试直接 import（`test/*.test.mjs` 不经过 Vite），引类型虽然会被类型剥离线掉，
 * 但一旦有人顺手写成值导入，测试环境会连带把整个 EP 拉进来。
 */
export interface DangerConfirmOptions {
  /** 弹窗标题（动作名，如「删除任务」） */
  title: string
  /** 正文；`\n` 会被渲染成真正的换行 */
  message: string
  /** 确认按钮文案，默认「删除」 */
  confirmText?: string
  /** 取消按钮文案，默认「取消」 */
  cancelText?: string
}

/**
 * 正文换行必须靠 CSS，不能靠 `\n`。
 *
 * <p>见文件头第 3 条：EP 把 message 塞进 `<p>` 的 textContent，
 * 而 `.el-message-box__message p` 没有 `white-space: pre-wrap`，
 * 于是 `\n` 与连续空格都被折叠。类名挂在弹窗上（`customClass`），
 * 样式写在 theme.css —— 纯函数不便携带样式，这里只负责给出类名。
 */
export const CONFIRM_CUSTOM_CLASS = 'os-confirm'

/** 删除类弹窗的固定骨架：危险色确认键 + 焦点不落在确认键 + 保留换行 */
export interface ConfirmSkeleton {
  type: 'warning'
  confirmButtonType: 'danger'
  autofocus: false
  distinguishCancelAndClose: true
  closeOnClickModal: false
  customClass: string
  confirmButtonText: string
  cancelButtonText: string
}

/**
 * 生成删除/危险操作的 MessageBox 选项。
 *
 * <p>纯函数，便于在 `.mjs` 测试里断言「默认值有没有被改坏」——
 * 这几条默认值正是本模块存在的理由，退化成 EP 默认值不会有任何报错，
 * 只会静默地又变回「回车即删除」。
 *
 * @param options 业务侧只需给标题与正文
 */
export function buildDangerConfirmOptions(
  options: DangerConfirmOptions,
): ConfirmSkeleton & { message: string; title: string } {
  return {
    title: options.title,
    message: options.message,
    type: 'warning',
    // 危险色确认键：EP 官方支持的选项，比事后用 class 覆盖干净（不会与禁用态/暗色打架）
    confirmButtonType: 'danger',
    // 焦点不交给确认键 —— 打开后按回车不会触发删除
    autofocus: false,
    // 区分「取消」与「关闭（×/Esc）」：两者都不执行，但语义不同，
    // 调用方若需要区分可以做不同处理（当前都按「未执行」处理）
    distinguishCancelAndClose: true,
    // 点遮罩不关闭：删除类弹窗要求显式表态，误点遮罩关掉会让人以为「已经删了」
    closeOnClickModal: false,
    customClass: CONFIRM_CUSTOM_CLASS,
    confirmButtonText: options.confirmText ?? '删除',
    cancelButtonText: options.cancelText ?? '取消',
  }
}
