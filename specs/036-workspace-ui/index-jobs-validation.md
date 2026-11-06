# 后台索引任务折叠验证

2026-11-06，接手提交 `3a6fc01`。本次只处理用户要求的后台索引任务收起功能。

## 实现

- 文档页默认收起任务明细，保留总数、进行中、失败和完成数量摘要。
- 标题按钮可展开/收起，具备 aria-expanded、aria-controls 和现有键盘焦点样式。
- 刷新按钮独立，刷新与状态更新不重置展开状态；原后台轮询和卸载清理保持不变。
- 查询失败在折叠区域外显示；失败数量保持可见，文件、重试次数和错误原因在展开后查看。
- 无任务且无查询错误时隐藏整个面板；收起后内容区高度为零。手机上刷新与标题并排，摘要另行。
- 配色、颜色令牌、API、上传/替换/文档列表和依赖保持不变，尊重 reduced-motion。

## 实际验证

- 前端 lint 通过：0 错误，原 workflow-editor 测试仍有 5 个既有警告；本次两个 Vue/TS 文件单独 lint 无警告。
- format:check、type-check、生产构建通过。最终手机排版调整后再次检查格式并重新构建。
- 逻辑测试 1174 项通过；组件测试 44 个文件、283 项通过，含新增 5 项折叠面板回归。
  覆盖初始折叠、摘要、展开和错误明细、刷新/状态变化保留选择、空任务、轮询与卸载清理。
- 隔离 Chromium 检查通过：1440/390px × 深浅主题，12 张折叠、展开及错误截图；无运行错误、
  漏配 API 或页面横向溢出。检查 Enter 展开、Space 收起、独立刷新、错误可见和空任务隐藏。
- 人工查看最终手机折叠/错误和桌面展开截图；收起时没有遗留空白内容区。
- 后端关闭本地 dotenv 后执行全量 verify：1221 项，0 失败、0 错误、0 跳过，JaCoCo 门禁通过，BUILD SUCCESS。
  仅使用原测试隔离环境；没有修改或连接现有业务服务。
- 候选源码登记后的迁移完整性检查结果另记于 `specs/033-go-rewrite/post-baseline-scope.md`。

组件测试初次使用脱离 document 的挂载，Happy DOM 的后代可见性判断不可靠；
改为挂载到 document.body 后 5 项通过，真实浏览器另验证了任务列表的隐藏和显示。
首次迁移脚本测试在源码哈希尚未登记时按预期拒绝候选漂移；该次失败不算通过。

机器报告：`index-jobs-browser-report.json`。截图位于本机 `/tmp/personalos-index-jobs`。
复验：

```sh
npm --prefix frontend run build
node scripts/ui/browser-checks.mjs /tmp/personalos-index-jobs --index-jobs-only
```

沿用空 envDir、configFile:false、本机临时预览、模拟 API 和外部请求阻断；没有连接真实后端、模型或业务数据。
本次检查不是完整 Java/Go 端到端验收，不升级任何迁移发布门禁。
