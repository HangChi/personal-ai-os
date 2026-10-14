import { readdirSync } from 'node:fs'
import { results } from './assert.mjs'

/** 顺序执行所有 *.test.mjs，任一文件崩溃也继续跑完，最后统一汇报 */
const files = readdirSync(new URL('.', import.meta.url))
  .filter((file) => file.endsWith('.test.mjs'))
  .sort()

for (const file of files) {
  console.log(`\n▸ ${file}`)
  try {
    await import(`./${file}`)
  } catch (cause) {
    results.failed += 1
    results.failures.push(`${file} 崩溃`)
    console.error(`  ✗ ${file} 崩溃:`, cause)
  }
}

console.log(`\n${'='.repeat(46)}`)
console.log(`通过 ${results.passed} / 失败 ${results.failed}`)
if (results.failed > 0) {
  console.log('失败项：')
  for (const failure of results.failures) {
    console.log(`  - ${failure}`)
  }
  process.exit(1)
}
console.log('全部通过')
