// 用真实浏览器抓掘金正文（绕过 argus-csp-token 的 JS 挑战页）。
//
// curl 会在抓了 ~8 篇后被 CDN 拦成 JS 挑战页（响应只有 2.5KB），
// 等待无效 —— 挑战需要执行 JS。Playwright 能过，因为它是真浏览器。
//
// 输出：把每篇的 .article-viewer 内部 HTML 落到 /tmp/juejin-raw-<id>.html，
// 后续由 to-markdown.py 转成 Markdown（HTML→MD 的规则与 curl 版共用一套）。

const { chromium } = require('playwright')
const fs = require('fs')

const SHELL = process.env.HOME +
  '/Library/Caches/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-mac-arm64/chrome-headless-shell'

const ARTICLES = [
  ['7681235828687175730', 'TypeScript 泛型与条件类型'],
  ['7650146543883239487', 'TypeScript 类型体操技巧'],
  ['7694101953675460648', '分布式一致性：CAP、Raft 与脑裂'],
  ['7607112994062630939', '系统性地理解分布式系统'],
  ['7591207451778875418', '动态规划五部曲实战'],
  ['7472359618326052901', '动态规划基础与核心思想'],
  ['7672711130193936424', 'Nginx 完整配置参考手册'],
  ['7677521340606300170', 'Nginx 生产级配置优化'],
]

;(async () => {
  const browser = await chromium.launch({ executablePath: SHELL })
  const page = await browser.newPage({
    viewport: { width: 1280, height: 900 },
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 ' +
      '(KHTML, like Gecko) Chrome/120.0 Safari/537.36',
  })

  let ok = 0
  const failed = []

  for (const [id, title] of ARTICLES) {
    const outPath = `/tmp/juejin-raw-${id}.html`
    if (fs.existsSync(outPath) && fs.statSync(outPath).size > 1000) {
      console.log(`SKIP  ${title}`)
      ok += 1
      continue
    }
    try {
      await page.goto(`https://juejin.cn/post/${id}`, {
        waitUntil: 'domcontentloaded',
        timeout: 45000,
      })
      // 等正文容器出现；被挑战页拦住时这里会超时
      await page.waitForSelector('.article-viewer', { timeout: 20000 })
      // 等图片/懒加载内容稍微稳定，正文本身是同步渲染的
      await page.waitForTimeout(800)
      const html = await page.$eval('.article-viewer', (el) => el.innerHTML)
      fs.writeFileSync(outPath, html, 'utf8')
      console.log(`OK   ${String(html.length).padStart(7)} 字节  ${title}`)
      ok += 1
    } catch (error) {
      console.log(`FAIL ${title}  ${error.message.split('\n')[0]}`)
      failed.push([id, title])
    }
    await page.waitForTimeout(1500)
  }

  await browser.close()
  console.log(`\n成功 ${ok}/${ARTICLES.length}`)
  if (failed.length) {
    console.log('失败：')
    for (const [id, title] of failed) console.log(`  ${id}  ${title}`)
    process.exit(1)
  }
})().catch((error) => {
  console.error('脚本失败:', error)
  process.exit(1)
})
