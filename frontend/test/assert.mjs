/**
 * 极简断言：一个 check() 累计通过/失败，不引断言库。
 *
 * 约定：每个测试文件顶层 import 本模块并直接跑断言（异步用顶层 await），
 * 由 run.mjs 统一收尾输出与退出码。
 */
export const results = { passed: 0, failed: 0, failures: [] }

export function check(name, condition) {
  if (condition) {
    results.passed += 1
  } else {
    results.failed += 1
    results.failures.push(name)
    console.error(`  ✗ ${name}`)
  }
}

export function eq(name, actual, expected) {
  check(name, actual === expected)
  if (actual !== expected) {
    console.error(`    期望: ${JSON.stringify(expected)}`)
    console.error(`    实际: ${JSON.stringify(actual)}`)
  }
}
