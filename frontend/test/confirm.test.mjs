import { check, eq } from './assert.mjs'

import { buildDangerConfirmOptions, CONFIRM_CUSTOM_CLASS } from '@/lib/confirm'

/**
 * 危险操作确认的默认值契约。
 *
 * <p>这些断言存在的理由：本模块的三条默认值（危险色键、焦点不落确认键、
 * 点遮罩不关闭）**退化成 EP 默认值不会报任何错**，只会静默地变回
 * 「回车即删除」。所以要有测试把它们钉住 —— 这是唯一能拦住「顺手删掉
 * 一行默认值」的手段，因为界面上看不出来。
 */

const BASE = { title: '删除任务', message: '确定删除？' }

check('危险确认：确认键用 danger 色，而非默认主色', buildDangerConfirmOptions(BASE).confirmButtonType === 'danger')
check('危险确认：焦点不落在确认键上（防回车误删）', buildDangerConfirmOptions(BASE).autofocus === false)
check('危险确认：点遮罩不关闭弹窗（必须显式表态）', buildDangerConfirmOptions(BASE).closeOnClickModal === false)
check('危险确认：区分取消与关闭', buildDangerConfirmOptions(BASE).distinguishCancelAndClose === true)
check('危险确认：type 为 warning（图标语义）', buildDangerConfirmOptions(BASE).type === 'warning')

// type 决定弹窗左上角图标，confirmButtonType 决定确认键配色 —— 两者独立。
// 以前只给了 type 就以为「已经警告过了」，实际确认键仍是主色实底。
check('危险确认：确认键不走主色（与 type 分工不同）', buildDangerConfirmOptions(BASE).confirmButtonType !== 'primary')

eq('危险确认：customClass 挂上换行所需类名', buildDangerConfirmOptions(BASE).customClass, CONFIRM_CUSTOM_CLASS)
eq('危险确认：customClass 字面值', CONFIRM_CUSTOM_CLASS, 'os-confirm')

const MULTILINE = '确认删除这条记忆？\n\n它不再参与任何对话。'
const longForm = buildDangerConfirmOptions({ title: '删除记忆', message: MULTILINE })
// 正文原样透传：换行由 CSS 的 white-space 承担，不能在 JS 侧提前折叠或截断
eq('危险确认：标题原样透传', longForm.title, '删除记忆')
eq('危险确认：正文原样透传（保留 \\n）', longForm.message, MULTILINE)

eq('危险确认：默认确认按钮文案', buildDangerConfirmOptions(BASE).confirmButtonText, '删除')
eq('危险确认：默认取消按钮文案', buildDangerConfirmOptions(BASE).cancelButtonText, '取消')

// 「替换」「导入」这类动作不是删除，但同样需要二次确认，文案要能覆盖
const custom = buildDangerConfirmOptions({
  title: '替换文档内容',
  message: 'm',
  confirmText: '开始替换',
  cancelText: '再想想',
})
eq('危险确认：确认按钮文案可覆盖', custom.confirmButtonText, '开始替换')
eq('危险确认：取消按钮文案可覆盖', custom.cancelButtonText, '再想想')

// 空串也是合法覆盖值吗？不是 —— 空串会让按钮变成无文字的色块。
// 这里确认「未传」与「传空串」的差异被如实保留（由调用方负责不传空串），
// 即 ?? 而非 || 的语义没有被误改成「空串回落默认值」。
eq(
  '危险确认：确认文案用 ?? 判定，空串不回落默认值',
  buildDangerConfirmOptions({ ...BASE, confirmText: '' }).confirmButtonText,
  '',
)
