import axios from 'axios'

import { check, eq } from './assert.mjs'
import { ApiBusinessError, interpretAxiosError, toBusinessError } from '@/api/envelope'

/**
 * 统一信封的错误分类（d0df5de 修掉的 bug 的守护测试）。
 *
 * 曾经的症状：4xx/5xx 的失败响应同样带着统一信封，但旧实现一律报
 * 「无法连接后端服务」——「任务不存在」被说成「连不上后端」，
 * 用户排错方向被彻底带偏。这段逻辑抽成纯函数（envelope.ts）正是为了在此锁住。
 */

// ---- toBusinessError：信封解读 ----

eq(
  '信封 code 非 0 → 业务错误（保留服务端精确原因）',
  toBusinessError({ code: 40004, message: '任务不存在：abc' })?.message,
  '任务不存在：abc',
)

eq('业务错误带上 code', toBusinessError({ code: 40004, message: 'x' })?.code, 40004)

check('信封 code=0 → 不是错误', toBusinessError({ code: 0, message: 'OK' }) === null)

check('code 非 0 但 message 不是字符串 → 不是错误（信封不完整，不猜）', toBusinessError({ code: 40004 }) === null)

check(
  'body 为 null/undefined/标量 → 不是错误',
  toBusinessError(null) === null && toBusinessError(undefined) === null && toBusinessError('oops') === null,
)

// ---- interpretAxiosError：失败分类（判定顺序即契约）----

const notFound = new axios.AxiosError('Request failed with status code 404', 'ERR_BAD_REQUEST', undefined, undefined, {
  status: 404,
  data: { code: 40004, message: '任务不存在：abc' },
})

const classified = interpretAxiosError(notFound)
check('4xx 带信封 → ApiBusinessError（服务端精确原因，不是「连不上后端」）', classified instanceof ApiBusinessError)
eq('  原因文本来自服务端', classified.message, '任务不存在：abc')
eq('  code 来自服务端', classified.code, 40004)

const networkDown = new axios.AxiosError('connect ECONNREFUSED 127.0.0.1:8080', 'ECONNREFUSED')
const networkClassified = interpretAxiosError(networkDown)
check(
  'axios 错误但无信封 → 连接层错误',
  networkClassified instanceof Error && !(networkClassified instanceof ApiBusinessError),
)
check('  提示包含「无法连接后端服务」', networkClassified.message.includes('无法连接后端服务'))
check('  提示保留原始错误信息', networkClassified.message.includes('ECONNREFUSED'))

const unknown = interpretAxiosError(new TypeError('完全不是 axios 的错误'))
check('非 axios 错误 → 未知错误兜底', unknown.message === '无法连接后端服务：未知错误')
