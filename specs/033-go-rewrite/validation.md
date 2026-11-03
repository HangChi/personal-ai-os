# 验证记录

日期：2026-11-03。分支：`go`。固定 Java 基线：`cb5d694d62fa93742b5789b97273e76d931ce335`。

## 已执行：P0 基线工具

```bash
python3 -m unittest discover -s scripts/migration -p 'test_*.py' -v
python3 scripts/migration/go_inventory.py check
```

结果：4 项提取器边界测试通过；基线检查通过。覆盖注释/字符串/文本块不产生假注解、无参数/命名/多路径映射、动态错误路径、紧凑 Controller 与嵌套 record。

实际索引：1,093 个基线文件；Java 主源码 391、测试文件 154、SQL 迁移 42；注解 HTTP 路由 104、内部工具 23、MCP 工具 4、运行/配置钩子 95（含调度 13）、测试声明 1,119；显式环境变量名 145。

实际执行 `check-complete`：按预期返回非零，报告 2,438 个清单项尚未 verified、五项发布门禁未验证，确认未完成的迁移不能被误报为完成。

清单生成读取的是固定 Git blob，不读取本机 .env、备份、缓存或日志。检查会对照当前文件哈希、新增文件、生成文档和进度证据。

## 已执行：P1 基础代码

工具链：`go version go1.27.1 darwin/arm64`。所有 Go 命令使用可写临时缓存，`GOPROXY=off GOTOOLCHAIN=local`，没有下载第三方依赖。

在 backend-go 目录执行：

```bash
go test -race -count=1 ./...
go vet ./...
go build -o /private/tmp/personal-ai-os-go-cb5d694 ./cmd/personalos
```

结果：四个有测试的 Go 包全部通过竞态测试；命令入口编译通过；vet 通过；构建通过。该结果只覆盖已实现的基础契约，不代表 Java 业务完成。

覆盖内容：固定 Java 19 个错误码/文案/HTTP 状态快照、四字段信封/UTC/空 data、序列化失败脱敏、内存分页/SQL 页/空数组/大页码/Java offset 边界、五种角色及非法配置、Java trim/ASCII 请求 ID/32 路并发 context 隔离、诊断 200/503/405/HEAD 和未迁移 API 404、Worker 空 404。

真实进程冒烟脚本尝试申请 `127.0.0.1` 临时端口时，沙箱返回 `PermissionError: [Errno 1] Operation not permitted`；错误发生在启动 Go 子进程之前，因此未启动遗留进程，也没有实际验证监听、HTTP 网络链路和 SIGTERM 退出。处理器行为由 httptest 验证，不能替代进程验收。

## Git 提交状态

- 已提交：`f255fec chore(go): 固定 Java 迁移基线及防遗漏检查`。
- 用户已在本地完成：`b125f7e docs(go): 制定全量兼容迁移规格与分阶段验收计划`、`d59f85b feat(go): 添加基础响应契约与诊断启动入口`；本轮开始时工作区干净，分支仍为 go。
- 本轮已完成配置小阶段提交：`9614b84 feat(go): 移植环境配置来源与生产安全启动守卫`。
- 事务基础的自动提交曾被审批服务 503 阻断；用户随后已完成 `6cbfb19 feat(go): 添加显式连接池与可取消事务边界`。本轮开始确认分支为 go、工作区干净，该里程碑已进入提交历史。
- 此前本地令牌桶在竞态测试/vet/build/基线检查通过后立即申请独立提交，以及后续共享策略完成后的提交尝试，都被审批服务 503 阻断；这些失败的暂存/提交命令没有执行。
- 用户于 2026-11-03 将当前会话改为完全访问后，Git 普通命令已执行成功：`81ca139 feat(go): 移植本地模型令牌桶与并发边界`。共享限流策略、HTTP 中间件、Java 对照资产和迁移记录按另一笔增量提交保存。当前命令执行成功不证明 Aiport 自动审批服务本身已经恢复。
- 此前文档的后续暂存/提交尝试被自动审批服务连续返回 503 阻断，失败的操作未执行。这是审批基础设施故障，不是对修改安全性的否定；日志没有提供具体服务端原因。
- 后续仍按小阶段提交，并遵循当前会话权限执行；未完成的提交不算已保存到提交历史。没有推送或部署。

## 本轮：P1 配置来源与安全启动子集

已执行 `go test -race -count=1 ./...`、`go vet ./...` 和命令构建；测试通过。新增覆盖 Java .env 字面量解析、重复键顺序、CR/LF/CRLF、Unicode strip 差异、无效 UTF-8、最近文件/三层父目录边界、空值与系统环境覆盖、profile 静态值高于文件、运行角色顺序、生产守卫顺序/中文错误/16–72 UTF-8 字节边界、日志格式化脱敏。入口测试证明 prod 在监听之前被拒绝；这不验证真实监听或认证实现。

前端已实际执行 `npm run type-check` 和 `npm run build`，均通过，前端源码未修改。尚未跑全量 lint/组件/E2E。

Java 已实际执行 `./mvnw -o -B -Dapp.dotenv.enabled=false verify`，失败：汇总 859 tests、0 failures、494 errors；报告包含 Docker 环境不可访问、HTTP 模拟器 Socket Operation not permitted 等沙箱限制。未跳过失败项来声称全绿；真实环境 Java 全量质量门禁仍待恢复环境后重跑。

另执行 `./mvnw -o -B -Dapp.dotenv.enabled=false -Dtest=DotEnvParserTest test`：11 个解析测试全部通过，但 Maven 整体仍失败，原因是单独执行测试后的全项目 JaCoCo 覆盖率检查（0.80 < 0.83，并有已有执行数据与 class 不匹配警告）。未关闭覆盖率门禁；该结果只用于核对原 Java 解析用例，不能视为 Java 质量门禁通过或 Go 行为差分。

查询 pgx 可用版本时 DNS 失败，联网审批返回 503，命令未执行；未下载或锁定未经验证的驱动。Docker socket 访问被拒绝，未启动容器、未连接现有数据库。T102/T103 仍未完成。

## 本轮：P1 标准库连接池与事务

先加入事务测试，确认因待实现 API 而编译失败，再实现 Pool。最终重新执行 `go test -race -count=1 ./...`、`go vet ./...`、`go build -o /private/tmp/personal-ai-os-go-p1 ./cmd/personalos`，六个有测试的包全部通过；没有新增外部依赖。

受控测试驱动覆盖：API/Worker 池上限与 5s 默认值、非法配置在连接前失败、探测失败清理、握手期限、同连接执行/提交、只读/隔离选项透传、业务失败/panic 回滚、原始错误可用 errors.Is 识别但普通格式化脱敏、回滚失败保留业务错误、连接池队列超时不取消父 context、独立嵌套事务、父取消不提交、幂等关闭与关闭后拒绝、32 路并发与 Worker 4 连接上限。提交失败不重试。

这些测试不验证 PostgreSQL SQL、数据原子性、锁、TLS、驱动类型、pgvector 或 Flyway 历史；Pool 尚未接入应用或生产退出流程。完整 T102、T103、T108 和 P1 验收仍待完成。驱动联网查询的第二次审批仍返回 503，未执行；后续需先恢复依赖下载与隔离数据库环境。

## 增量：P1 本地令牌桶

按固定 Java `RateLimitProperties`、`TokenBucketRateLimiter` 和四个本地桶测试移植。测试使用可控时钟而非 sleep，覆盖初始注满/耗尽、7 次每分钟的分数补充、容量上限、零间隔/时钟回拨、关闭开关仍校验参数、关闭时不消耗、Java int 范围及 1,000 路并发不透支。

本地桶包先通过定向竞态测试，再执行 `go test -race -count=1 ./...`（七个包通过）、`go vet ./...` 和命令构建，均通过；基线检查通过。无新依赖，无应用接线、数据库或 Redis 访问；共享后端和指标仍待迁移，T107 保持未完成。本轮 PostgreSQL 驱动查询又被审批 503 阻断，查询命令未执行、未锁定版本。

## 增量：P1 模型路径与共享限流策略

先加入共享后端/模型路径测试，确认待实现 API 编译失败，再实现共享选择和路径匹配；随后先加入 HTTP 测试，确认缺少中间件时失败，再实现中间件。两个包的定向竞态测试均通过。

已直接执行固定 Java 基线的 ModelRequestPaths，生成并再次核对 216 项结果。版本来自固定基线的 Spring Boot 4.1.1 BOM（Spring Framework 7.0.9、Tomcat 11.0.24），全部使用本机缓存 JAR；Java 21 离线 javac/java，无 Maven 下载、Spring 启动、数据库或监听。输入含全部十个 POST/一个 PUT 模式、编码/双编码/编码斜杠/非法转义、方法大小写、Unicode/无效 UTF-8、挂载前缀、重复/末尾斜杠和普通路由；Go 对照全部通过。直接 matcher 接受的路径不代表 Servlet/安全链/路由也接受，特别是点段/非法 UTF-8 等用例。

共享策略使用受控替身验证 PostgreSQL/Redis 选择、`model-rate`/容量/周期与原始 context、关闭时不访问、Redis 出错拒绝且不回退、PostgreSQL 出错向上传递、成功/拒绝/失败计时及 Redis 错误回调。没有实现 Redis Lua/PostgreSQL 原子消费适配器，也没有接入真实指标系统。

HTTP 测试覆盖原 Java 四项断言及编码、单次消费、请求 ID 先行/拒绝回显、42900/中文文案/null data/UTC 时间戳、日志不含 query/正文/Authorization、放行 SSE 透传及后端错误的脱敏 50000。中间件尚未接入诊断应用；Servlet 容器级异常映射的真实差分仍待 T106。

全部增量完成后再次执行 `go test -race -count=1 ./...`（七个包通过）、`go vet ./...` 和命令构建，均通过。Java 216 项快照重新执行核对通过；四项 Python 索引测试、1,093 文件基线检查、Markdown 本地链接与 git diff --check 通过。`check-complete` 仍按预期拒绝全部 2,438 个未 verified 项及发布门禁，没有预先升级验证状态。本轮未重复运行未变更的 Java 全量/前端门禁，其既有未通过事项继续保留。

## 未执行 / 尚未实现

- Java 全量 Maven 回归已尝试但未通过；前端类型与构建已通过，完整前端检查仍待完成；本次未修改原 Java/前端实现。
- 没有跑 Java/Go 业务差分、数据库或任何真实第三方基础设施集成。
- 没有迁移现有数据库、模型密钥、附件、Redis 会话、RabbitMQ/OpenSearch/S3 数据。
- 没有替换本地运行实例、推送分支、合并 main 或执行部署。
- 全部业务模块和路由迁移、完整配置/认证/安全/持久化/模型/后台任务均未完成。
- `check-complete` 当前必须失败；最终验收依赖 tasks.md 的 P1–P8 和五项发布门禁。

## 证据边界

源码哈希和词法索引能防止基线文件静默缺席或漂移，不能证明 Go 方法行为与 Java 完全一致。必须结合代码审查、逐项测试替代、真实数据升级/恢复、故障注入与前端验收，才能将条目标记为 verified。
