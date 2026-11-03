# P4 收口与验收（2026-11-04）

固定 Java 基线：`cb5d694d62fa93742b5789b97273e76d931ce335`。T400–T409 已完成，T400a 保留为历史子阶段。代码在原仓库 `go` 分支；Foundation 与显式 CLI 已装配对话模块。P5–P8、全量逐项 verified 和五个发布门禁仍开放，回环监听、prod 拒绝与 readyz503 保留。

## 已交付职责

任务 | 实现与验收证据
--- | ---
T400 会话与历史 | 原三表、并发序号、标题/消息搜索、分页/排序/改名/模型、引用快照、删除/rewind 与 Markdown。`service.go`、`postgres.go`、`http.go`、`markdown.go`；206 个原 Java 差分和真实库用例。运行中删除/回退由同一会话租约阻止。
T401 上下文 | 按模型的原 BPE、UTF-16 字符口径、窗口偏好持久化、系统/摘要/时间/计划/工具约束/附件/知识贡献顺序、输出/思考/图片/schema 预留、历史副本裁剪、摘要快照与 rewind 竞态防护。`context/`、`history.go`、`prompt.go`、`summary.go`。
T402 工具 | 显式 16 工具注册、原 Spring 生成的 schema 与描述、参数类型/规范化、活动事件、持久审计、同轮幂等/跨轮独立、模型调用上限、连续失败/同失败参数停止。`agent/tools.go`、`agent/task.go`、`agent/audit.go`；8 个任务工具均真实执行或创建确认。
T403 计划 | 7 个计划工具、步骤顺序与重试、证据要求、业务工具绑定/失败标记、修订/终结/提示限制；未完成计划会纠正模型，仍未结束则让出而非伪装完成。日历成功声明需本轮成功写回执，否则修复或诚实提示未创建。`agent/plan.go`、`stream.go`。
T404 原 POST SSE | 绑定/前置校验、start/citations/delta/activity/done/error/yield、心跳、超时/取消/中断消息、空输出与 ACTUAL/ESTIMATED、附件及引用在 done 前可读。`stream.go`、`module_http.go`、`usage.go`。
T405 持久运行 | 原状态机、请求重放/冲突、会话互斥、模型许可、租约与最终写 fencing、事件批次/游标/合并/Hub/交付、停止/继续/确认完成。`runs.go`；405 事件跨 200 条分页重放；跨实例显式停止可取消尚无 chunk 的模型。
T406 恢复 | 启动扫描和周期恢复、同会话租约隔离、未知工具结果暂停、成功回执复用、读者断开不取消后台工作、关闭时保留可恢复状态。`runs.go`、`database.WithWriteGuard`、`execution_integration_test.go`。
T407 用户交互 | askUser/WAITING_USER、一次回答、QUEUE/STEER 的安全边界、队列编辑/取消/转引导、顺序消费、停止后队列保留。`interaction.go`、`runs.go`；数据库副作用与原前端客户端均有断言。
T408 附件与语音入口 | 大小/空文件/UUID/路径/符号链接检查、真实大小校验、文本/DOCX/PDF/图片上下文、本地与 S3、所有权标记、旧本地文件优先、引用保留与有界孤儿清理、绑定/清理跨实例互斥。`attachments.go`、`pdf.go`、`s3.go`；P3 音频接口的 HTTP 转写正文接入 P4 对话链。
T409 装配与观测 | Foundation/CLI、32 条原 P4 路由（其中两个音频路由沿用 P3）、最小表/序列权限、Worker 隔离、用量/首正文延迟/时长/活跃流/桶/Actuator/Prometheus、真实前端客户端联调。`foundation_p4_integration_test.go`、`chat_metrics.go`、`scripts/migration/frontend/`。

本表中的无前缀文件位于 `backend-go/internal/business/chat/`；`agent/` 与 `context/` 为同级业务包。逐 Java 测试声明对应关系保存在 `progress.json`，按原类汇总见 [p4-test-mapping.md](p4-test-mapping.md)。

## 契约与安全边界

- 估算按用户原文决定中文/英文口径，再对整份提示 UTF-16 字符数折算；包含系统提示/历史/schema，不重复加系统提示。有真实非全零 usage 时取最后一个有效快照；0 prompt/正 completion 保留真实来源，全零尾块不能覆盖已有真实值；未知模型名保留 null。
- 用量保持原 Java 的完成后有界尽力记账。统计写入失败、执行器已关闭/队列满不影响回答；已有消息不会被统计失败回滚。记录捕获完成时刻，只复制请求 ID，限时、无自动重试；统计可能缺点，失败记脱敏警告。查询允许短暂最终一致性。
- 心跳由 `app.chat.heartbeat-interval` 同时驱动原POST与runs GET流，默认15秒，非正/不足1毫秒在启动时拒绝；已用5毫秒配置实际验证。附件清理开关与cron已实际消费：默认本地04:30，禁用保留旧孤儿；支持六字段数字/名称/列表/范围/步长/?、标准宏和`-`，Spring扩展L/W/#明确拒绝而非静默忽略。
- 引用在 done 前尝试挂载，失败保留回答并记警告；检索端口普通失败退回无引用对话，取消/超时仍中断。引用持久化与批量写自身保持事务回滚，不留下半批快照。知识检索端口测试使用合成证据；完整 RAG/冲突证据强制回答属 P6。
- 未配置知识、记忆或日历业务模块时，不注册其伪工具。P4 的贡献/检索/轮末接口已提供；记忆读写和自动沉淀、真正的日历创建、知识摄入/检索、MCP 等分别按 P5/P6/P7 接入。日历防伪成功守卫并不代表日历模块已迁移。
- 运行恢复以数据库事实与租约为准。TOOL 的 IN_PROGRESS/FAILED 结果不明项拒绝盲续；成功回执按同一 turn/规范化参数重放。未使用自动网络重试掩盖不确定写。数据库集成中的“崩溃”通过持久化中断状态/新执行组件接管模拟，未宣称本阶段执行了操作系统强杀恢复演练。
- 附件 GC 与绑定共享数据库 advisory 锁；只删除未引用且超过保留时间的本地普通文件/本 namespace 所有对象。S3 使用官方 AWS Go SDK、一次请求尝试、独立命名空间及 owner 元数据；不删除外来/无主标记对象。只启用 S3 时才使用显式凭据或 SDK 默认凭据链。
- PDF 使用独立 Poppler `pdftotext` 进程（5 秒、最多100页、64KB输出）；部署环境需要安装该命令。缺失/损坏/超时按不可读取附件提示处理，不伪装提取成功。实际 PDF 提取测试需 Poppler；知识摄入的完整解析/视觉回退仍属 P6。
- 明确的安全收紧：本地文件限制在 `os.Root` 内并核对打开前后身份；拒绝符号链接；工具/schema/输出与文档解析均有边界。当前无真实用户备份、真实模型/云 S3 凭据、生产切流或浏览器视觉验收。

## 实际执行的验证

- 原 Java 差分：206 个存储/绑定/Markdown/快照/标题场景；P4 150 轮预算/4500 个动作、504 个请求绑定、21 个工具参数、16 个真实工具定义、56 个用量快照、100 个估算；原 JTokkit 1325 个 token 场景及四词表哈希复验。夹具默认比较，只有显式 `--write` 才重生成。
- Go 全量 `go test -race -count=1 ./...` 22 包通过；integration 标签 vet/build 通过，`go mod verify` 通过。上下文网络测试覆盖超限只在无输出时重试、401/半流/取消不重试、不可压缩输入零模型调用、重试/模型预算有界和成功证据隔离。
- TLS PostgreSQL：chat/app/command 全包回归，原42条 Flyway 迁移与校验；最后契约及调度配置调整后再次运行 P4 chat/app（9.088秒/9.571秒），测试均启用race。覆盖双池并发启动/写入、租约栅栏、重放/未知结果、队列/问答/引导、跨实例停止、回退/删除排斥、附件 GC/重新绑定和405事件批次。
- TLS PostgreSQL + TLS Redis + 最小 ACL + 合成备份恢复：app 全包106.308秒通过、command 全包1.667秒通过；真实 race 二进制五角色、双进程/双监听、共享会话/预算、SIGTERM、端口/连接释放、失败启动清理通过。全部资源由 runner 新建并 finally 清理。
- 临时 MinIO：真实认证上传/读取、大小限制、旧本地读取、owner/namespace 与引用保护、孤儿清理全部通过；只清理本轮容器。普通 PostgreSQL runner 未提供 S3 时，该单项会跳过，另由独立 S3 runner 实际执行。
- 原前端 API 直接连接 Go Foundation：22 组断言覆盖 JSON/文件、原 POST 与 runs GET SSE、游标/幂等、刷新、问答、编辑/取消/自动消费队列和停止后保留。唯一加载适配是 Vite API base URL；原 Axios/CSRF/SSE/业务 API 实现未改。前端类型/生产构建通过，逻辑1151/1151、组件195/195通过；不是实际浏览器视觉测试。
- 原 Java 最小环境、禁 dotenv 的离线 `verify`：1137 测试，0 failures/errors/skipped，BUILD SUCCESS；JaCoCo 行85.798%、分支69.866%，保留原83%/65%门禁。较早一次运行失败，未仅凭日志尾部推断失败原因；随后以最小环境完整重跑通过，未改原测试/门禁。
- 16 项 Python 索引/配置台账测试、1093文件/104路由/27工具/95钩子/1119声明的基线检查通过。全量 `check-complete` 仍按预期拒绝；本阶段证据使用 implemented/in_progress，未批量伪造 verified 或放开发布门禁。

本机日志 `/private/tmp/personal-ai-os-p4-*` 仅辅助诊断：`full-pg.log`、`chat-usage-final.log`、`final-contracts.log`、`redis-app-command-final.log`、`s3-final.log`、`race-final-verified.log`、`oracle-final.log`、`guard-final.log`、`java-safe-verify.log`、`frontend-final.log`、`frontend-components.log`、`config-contracts.log`、`final-all-race.log`、`frontend-relocated.log`。长期可复验资产为入库 runner、夹具、断言及本报告。

## 复验入口

```bash
python3 scripts/migration/java_chat_storage_fixture.py
python3 scripts/migration/java_p4_fixture.py
python3 scripts/migration/java_context_token_fixture.py
python3 scripts/migration/go_token_assets.py
go -C backend-go test -race -count=1 ./...
go -C backend-go vet -tags=integration ./...
go -C backend-go build -tags=integration ./...
go -C backend-go mod verify
python3 scripts/migration/run_go_postgres_tests.py --tls --package chat --package app --package command
python3 scripts/migration/run_go_redis_tests.py --tls --backup --package app --package command
python3 scripts/migration/run_go_s3_tests.py
python3 -m unittest discover -s scripts/migration -p 'test_*.py'
python3 scripts/migration/go_inventory.py check
```

这些命令要求本地有 Go/Java/Node/Docker、原 Maven/npm/Go 缓存与 Poppler。runner 使用一次性数据，绝不读取已有 `.env` 或连接用户数据库。不推送、不部署、不合并 main。下一阶段为 P5。
