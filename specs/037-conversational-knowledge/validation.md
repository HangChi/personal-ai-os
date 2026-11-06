# 对话来源与上下文修复验证（2026-11-06）

## 根因和修复范围

- 原 `ChatView` 将 `RagTrace` 放在消息列表与流式回复之间。生成时面板在回复上方，完成落库后又在回复下方，且没有消息归属。
- 原知识增强只检索当前输入，并可能在正常会话记忆和工具装配之前直接返回资料澄清。正常对话本身有历史，不能把这一缺陷解释为“所有聊天没有上下文”。
- 对话和知识问答现在复用 `ChatSources`：正文下方一个去重来源入口，抽屉内合并原文片段、安全外链和检索过程；普通空命中没有空来源卡，真实冲突、澄清和失败仍可查看。
- 来源按具体助手回复绑定，尾页刷新保留已绑定过程；恢复只使用最后一次 reset 后的完成事件并匹配最后助手正文。不为未知或未完成回复猜测归属。
- Java/Go 自动增强增加只读路由：最近6条、每条至多600字符，只用于理解指代，不能作为资料证据；路由最多4秒且计入检索总预算。任务操作跳过无关资料检索，资料追问补全问题后仍受真实证据保护。路由错误或无效 JSON 走原有 warning 降级路径；关闭高级 RAG 时保留原有基础检索。
- 没有新增日程工具、数据库字段、运行时依赖或调整整体配色；没有执行截图中的日程操作。

## 已实际执行

| 检查 | 结果 |
| --- | --- |
| 前端 lint | 0 errors；仅原 `workflow-editor.test.ts` 的5个既有 warnings |
| 前端 format:check、type-check、build | 通过 |
| 前端逻辑测试 | 1174通过，0失败 |
| 前端组件测试 | 46文件、292项通过，0失败 |
| Java verify（禁用 dotenv） | 1229项，0失败/错误/跳过，覆盖率门禁通过 |
| Go `go test -race ./...`、`go vet ./...`、`go build ./...` | 全部通过，race覆盖34个含测试包 |
| Go chat 整包独立 PostgreSQL/pgvector 集成（含 race） | 56个顶层用例通过；包含新增连续操作追问、历史传递、路由故障降级，以及既有持久化、取消、恢复和冲突保护 |
| 隔离模拟 API 浏览器 | 1440/390宽度、深浅主题，24张截图，0运行错误、0漏网API |

浏览器验证实际覆盖生成到完成来源位置、文档去重、抽屉 Enter 打开/Esc 关闭和焦点返回、片段与过程展开、正文引用预览、空结果隐藏、真实澄清说明、横向溢出。人工检查桌面深色完成态、手机深色完成态和手机浅色来源抽屉。报告见 `browser-report.json`；截图在 `/tmp/personalos-037-chat-ui-delivery/`，未提交图片二进制。

## 可复验命令

```sh
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run type-check
npm --prefix frontend run test:logic
npm --prefix frontend run test:component
npm --prefix frontend run build
node scripts/ui/browser-checks.mjs /tmp/personalos-037-chat-ui --chat-sources-only
# backend 目录
APP_DOTENV_ENABLED=false ./mvnw -B --no-transfer-progress -Dapp.dotenv.enabled=false verify
# backend-go 目录
go test -race ./...
go vet ./...
go build ./...
# 仓库根目录；只用已有依赖缓存，数据库仍为新建隔离容器
GO_TEST_GOPATH="$(go env GOPATH)" GO_TEST_GOCACHE="$(go env GOCACHE)" python3 scripts/migration/run_go_postgres_tests.py --package chat
```

## 验证边界和首次失败

模型路由单测与数据库多轮编排使用模拟模型。截图中的“双选会 → 八点半”已覆盖历史进入路由和正常模型流程、跳过无关资料保护，以及真实冲突仍保留；未验证真实模型是否正确选择并完成日程工具操作。浏览器使用全模拟 API，外部请求被阻断，不代表完整双端真实模型 E2E。

首次 Go 隔离运行因专用缓存未安装依赖且 `GOPROXY=off` 而未执行；显式指定已有开发依赖缓存后，专项与完整 chat 包均通过。浏览器首次断言用了不存在的旧预览选择器，改为真实 `.md-source` 后通过。组件关闭断言曾依赖测试环境禁用的 transition 结束钩子；关闭、Esc 和焦点恢复使用真实浏览器验证，组件测试覆盖来源内容、分组、链接安全和消息归属。

不读取真实 `.env`，不连接业务数据库或外部模型服务，不推送远端。新增候选继续 `in_progress`；冻结清单、全部既有SQL和五项发布门禁保持不变。迁移范围登记和完整性检查在最后的登记提交中完成，不能由本报告推导迁移发布批准。

## 提交与迁移完整性

- 后端修复：`1271f3c`；来源UI及回归：`4e81a81`；规格、计划、任务分别提交。
- 本轮新增16个独立归属候选，现有4组共享文件推进源码锚点；另1组因 `foundation.go` 双端责任字节更新而同步责任源码锚点，未修改该组原候选文件。合计11组、402文件，全部保持 `in_progress`。
- 首次派生检查发现旧清理组的责任哈希已更新、源码锚点仍旧；按不可变提交核对整组字节后同步锚点，再运行完整检查通过。没有绕过责任漂移校验。
- `go_p8_test_progress.py`、`go_p8_inventory_progress.py` 写入及只读复验通过；`go_inventory.py check`、配置处置检查通过；迁移脚本185项单元测试通过。
- 对照本轮编辑前快照，52个冻结文件（3个原清单及全部既有SQL）与五项发布门禁原样保留；没有取消历史限制或把候选标成 verified。
- `check-complete` 按预期返回1：仍有未验证迁移声明、11组未验收候选及5项开放发布门禁。普通完整性通过不等于迁移或发布完成。
