# 034 实现与验证记录

验证日期：2026-11-06（Asia/Shanghai）。分支沿用 `go`。

结论：今日工作台、项目、一级子任务和周期任务首版已完成代码实现及本地验证。Java 稳定后端与 Go 候选后端同时支持，共享 Vue 前端。尚未部署、未对现有运行数据库执行 V43、未重启用户服务。按用户后续要求，分为项目任务功能、今日工作台、联合验证脚本与文档三笔提交。

## 交付内容

- 首页新增今日任务、往日逾期、今日日程、待确认操作、未结束执行五个区块。独立加载、错误重试、总数和有界列表；可直接完成任务、定位任务编辑或返回对应会话。
- 按浏览器本地自然日划分今日和往日，跨日和窗口重新获得焦点时刷新；任务的「查看全部」携带日期筛选，清除筛选会同步清除 URL 参数。
- 任务页支持项目新建、名称/描述编辑、归档/恢复和项目筛选；表格与看板显示项目、父任务和重复规则。
- 一级子任务必须与父任务同项目。未完成子任务阻止父任务完成；仍有关联子任务时阻止父任务删除或移动项目。恢复子任务前须先恢复已完成的父任务。批量完成/删除先处理所选子任务，再处理所选父任务。
- 周期规则包括每天、工作日、每周、每月。要求截止时间和有效时区；首版与父子层级互斥。工作日指周一至周五，不包含节假日历。
- 完成本期与生成下一期在同一数据库事务中进行；并发完成和重新打开再完成不重复生成。按原到期日推进一期；保留时区本地时间和每月日期锚点，短月取月末。
- 导入导出包含项目、归档状态、父子关联、周期规则和生成标记；兼容缺少新增字段的历史备份。

关键入口：`frontend/src/components/home/TodayWorkspace.vue`、`frontend/src/views/TasksView.vue`；API 契约见 `contracts/api.md`。

## 自动检查

所有命令在本项目各自目录执行。详细日志保存在被忽略的 `.cache/` 下。

| 检查 | 命令或入口 | 结果 |
| --- | --- | --- |
| Java 全量测试、覆盖率门禁、打包 | `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./mvnw -B --no-transfer-progress -Dapp.dotenv.enabled=false verify` | 1182 项测试通过，0 失败、0 错误、0 跳过；覆盖率门禁通过；BUILD SUCCESS |
| Go 全量竞态测试 | `go test -race -p 2 -count=1 -timeout=10m ./...` | 所有包通过 |
| Go 静态检查 | `go vet -tags=integration ./...` | 通过 |
| Go 构建 | `go build ./...` | 通过 |
| Go 迁移目录最终复核 | `go test ./internal/platform/migration` | 通过，要求总计 43 个迁移且原 42 个哈希保持一致 |
| 前端逻辑测试 | `npm run test:logic` | 1174 项通过，0 失败 |
| 前端组件测试 | `npm run test:component` | 38 个文件、259 项测试通过 |
| 前端静态检查 | `npm run lint`、`npm run format:check`、`npm run type-check` | 全部通过 |
| 前端生产构建 | `npm run build` | 通过 |
| 独立数据库与真实浏览器 | `python3 scripts/workspace/run_checks.py --ui` | 通过，报告 PASS，浏览器脚本错误为 0 |
| 改动空白检查 | `git diff --check` | 通过 |

Java 全量检查包含 4 项新增 `TaskWorkspaceIntegrationTest` 集成测试。既有请求幂等摘要/重放测试仍通过；新增可选请求字段为空时不进入历史摘要。

主要日志：

- `.cache/workspace-java-verify.log`
- `.cache/workspace-go-all-final.log`、`.cache/workspace-go-vet.log`
- `.cache/workspace-frontend-{logic,components,lint,format,types,build}.log`
- `.cache/workspace-ui-check.log`、`.cache/workspace-ui/report.json`

## 数据库边界验证

`scripts/workspace/run_checks.py` 创建自己的随机端口、无持久卷 PostgreSQL/pgvector 临时容器，不读取项目 `.env`，不连接现有 Compose 数据库。Java Flyway 从空库执行全部 43 个迁移，随后 validate 及再次 migrate（新增迁移为 0）通过。退出时已清理本次容器和临时服务进程。

`backend-go/internal/business/task/workspace_integration_test.go` 在该临时库上运行 6 项顶层测试，包含 4 个额外日期边界子场景：

- 项目名称/归档、父子层级、同项目、完成/恢复/删除约束。
- 10 个并发完成请求、重新打开再完成，只生成一条下一期；同事务复制更新后的标签，回滚不产生部分结果。
- 1 月 31 日至 2 月末再至 3 月 31 日、闰年 2 月、周末跳转、夏令时下本地时间保持。
- 新增字段备份往返、归档项目、备份中子任务先于父任务时的恢复；历史备份兼容。
- 截止时间包含下界、排除上界；今日与往日查询边界。
- 项目及工作台 HTTP 接口、可读领域错误、每个会话仅保留最近执行且排除已完成执行。

## 浏览器与视觉检查

使用编译后的 Vue 页面、临时 Java 后端和上述独立数据库；未使用模型服务。Playwright 在桌面和 390px 手机宽度执行：

1. 新建项目，检查成功反馈提示的样式完整。
2. 创建父任务和子任务，提前完成父任务被拒绝；先完成子任务，再完成父任务成功。
3. 从工作台任务标题定位到正确任务编辑弹窗。
4. 使用周期任务表单创建每日任务，从工作台完成，API 验证只有本期和唯一下一期。
5. 工作台「查看全部」进入今日筛选，清除后恢复全日期且 URL 同步更新。
6. 手机端表格/看板切换、完整页面无横向溢出；无浏览器脚本错误。

截图在 `.cache/workspace-ui/`：`tasks-desktop.png`、`today-desktop.png`、`tasks-mobile.png`、`today-mobile.png`、`board-mobile.png`。已查看桌面工作台及手机看板截图，确认卡片、操作按钮、换行与提示布局；浏览器运行中发现的消息组件 CSS 缺失已补齐，并加入计算样式断言。看板自动化操作定位改为点击可见单选标签，避免点击 Element Plus 隐藏原生 input。

待确认和未结束执行的会话定位、分区独立失败/重试、列表截断提示由组件测试验证；本轮未连接外部模型或执行真实待确认业务操作。

## 迁移与范围边界

- 新增迁移仅 `backend/src/main/resources/db/migration/V43__task_workspace.sql`；V1–V42 原文件及冻结哈希保持不变。V43 包含项目/任务字段、索引和跨写入口的数据库约束/原子生成触发器。
- 上线时须按项目原有流程备份并应用 V43，发布相应后端和前端，然后重启服务。当前运行环境尚未执行该步骤。
- 保留开始本任务前已有的 033 迁移验收文件及脚本脏改动；本次不更新 033 冻结证据、不标记其 P8 验收完成。本地 V43 扩展目录测试通过也不构成 033 的整体验收结论。
- 首版不包含无限层级、复杂依赖图、节假日历、未来实例批量铺排、自动 AI 晨报或新增 Agent 项目工具。项目使用可恢复归档，不提供物理删除。
