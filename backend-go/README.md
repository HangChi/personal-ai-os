# Personal AI OS：Go 迁移起点

本目录位于 `go` 分支，是 Java 后端的增量迁移实现。**P1基础设施、P2任务、P3模型协议与P4对话执行已完成，P5–P8尚未完成，不能替代现有Java后端。**

全量范围、逐文件来源、迁移状态与验收要求见 [迁移文档](../specs/033-go-rewrite/README.md)。现有 Java 源码、前端业务源码、SQL 与运行实例继续保留。

## 已有代码

- `internal/platform/api`：响应信封、19 个 Java 错误码及 HTTP 映射、两类分页与 Java offset 溢出边界。
- `internal/platform/config`：五种角色/端口、显式开启的 .env 解析/父目录查找、系统环境/profile/文件优先级、Java 生产安全校验与未完成安全能力的启动拒绝。
- `internal/platform/database`：显式pgx连接器、池与事务、超时/取消/UTC/脱敏；已接Foundation并通过真实TLS/最小权限/不确定提交验证。边界见[说明](internal/platform/database/README.md)。
- `internal/platform/migration`：42原SQL、真实Flyway只读历史、锁竞争、七类DDL、默认V1基线与合成备份恢复启动验证；迁移执行权仍归独立Java Flyway。见[说明](internal/platform/migration/README.md)。
- `internal/platform/coordination`：数据库时钟租约、续租、fencing与事务后释放；已接Foundation并验证断网/恢复/退出。见[说明](internal/platform/coordination/README.md)。
- `internal/platform/modelgate`：本地/共享并发与Worker的API预留容量；基础HTTP持有整段SSE许可，模型协议已在P3接入，对话/Agent链路已在P4接入。见[说明](internal/platform/modelgate/README.md)。
- `internal/platform/ratelimit`：本地/PostgreSQL/Redis共享桶、显式后端与指标；已装配并验证跨实例/OS进程预算和故障拒绝。见[说明](internal/platform/ratelimit/README.md)。
- `internal/platform/auth`：单用户BCrypt/失败锁定、本地/Redis会话与登录/轮换/注销/me/CSRF，已接显式Foundation；真实Servlet/TLS/ACL/跨进程与网络故障验证，见[边界说明](internal/platform/auth/README.md)。
- `internal/platform/redis`：显式专用 Redis 客户端、TLS 配置、受限超时/连接池/生命周期与脱敏错误，见 [说明](internal/platform/redis/README.md)。
- `internal/platform/health`：真实SELECT 1与模型状态报告，已挂显式Foundation；真实Servlet方法/内容协商/探测次数与数据库断连/恢复验证，见[说明](internal/platform/health/README.md)。
- `internal/platform/taskexec`：原策略有界轮末队列、请求ID捕获和排空/停止；已接Foundation且验证HTTP后台传播，摘要已在P4接入，记忆业务待P5，见 [说明](internal/platform/taskexec/README.md)。
- `internal/platform/http`：请求ID/模型路径/共享限流与并发/异常边界、JSON协商/多编码，以及显式有界multipart解析。保护链已接Foundation，默认离线诊断保持独立。
- `internal/app`：默认迁移诊断入口，以及显式P1基础组合（池/Flyway只读校验、认证/CSRF、共享保护、健康与执行器/关闭）；两实例/五角色已通过临时TLS PostgreSQL集成，见 [装配边界](internal/app/README.md)。
- `cmd/personalos`：回环监听、启动提示、信号处理和超时退出。
- `internal/platform/observability`：root日志级别、Go文本/ECS格式、HTTP及后台请求ID；HTTP完成timer/直方图与Go运行指标在app组合。
- `internal/business/idempotency`、`confirmation`、`task`：P2幂等所有权/回放、人工确认、任务9路由与标签/提醒同步；已在真实迁移库和跨连接竞争下验证。

错误码测试使用从固定 Java 提交提取的 [快照](internal/platform/api/testdata/java-error-codes.json)，不从 Go 实现生成期望值。模型路径另有实际运行 Java 得到的 216 项对照快照；其他测试覆盖分页边界、角色矩阵、请求 ID 并发隔离、限流边界以及诊断的未就绪行为。

默认命令仍仅装配离线诊断；显式`--foundation`已接P1基础能力、P2任务/确认、P3模型与P4对话路由。844个基础HTTP场景、160个原观测标签场景和68个直方图边界通过真实框架对照。完整P1边界见[P1契约](../specs/033-go-rewrite/p1-foundation-contract.md)，78处配置调用方见[台账](../specs/033-go-rewrite/config-disposition.md)。知识库、日历、记忆、MCP及其业务后台调度仍待P5–P7迁移；P4仅运行本阶段的Run恢复与附件清理。固定依赖及许可证见[声明](THIRD_PARTY_NOTICES.md)；首次构建需`go mod download`，完整缓存后可离线验证。

- `internal/business/model`：配置/档案/密钥、运行时按名继承、OpenAI/Ollama协议、探测/音频、本地与Redis嵌入缓存，见[说明](internal/business/model/README.md)。

- `internal/business/context`：原BPE离线词表、模型窗口与持久预算、输出/图片/思考/schema预留、历史副本裁剪和有界超限重试。
- `internal/business/agent`：16工具定义/参数/幂等/审计、任务确认、计划状态/证据与调用预算。
- `internal/business/chat`：会话/摘要、新旧SSE与持久Run、恢复/停止/问答/排队引导、附件及用量。显式Foundation只在API/单体角色启用执行；Worker不开放聊天路由。S3按原开关配置且关闭时不建客户端。PDF需外部Poppler `pdftotext`，缺失/损坏/超时返回不可读提示。

附件清理消费 `app.chat.attachment-janitor-enabled` 和 `app.chat.attachment-janitor-cron`，默认本地时区每天04:30；支持六字段数字/名称/范围/列表/步长/?、标准宏和`-`禁用。Spring扩展L/W/#在启动时明确拒绝。`app.chat.heartbeat-interval`驱动两种SSE，默认15s，必须至少1ms。S3真实验证用 `python3 scripts/migration/run_go_s3_tests.py` 创建一次性MinIO，不读取现有凭据。

## 构建与验证

本机已使用 Go 1.27.1 验证；模块最低工具链声明为 Go 1.27.0。竞态检测需要平台支持的 C 工具链。

```bash
cd backend-go
go test -race -count=1 ./...
go vet ./...
go build ./...
```

受限环境可指定可写缓存目录，并禁止自动下载依赖/工具链：

```bash
GOCACHE=/private/tmp/personal-ai-os-go-build \
GOPATH=/private/tmp/personal-ai-os-go \
GOPROXY=off GOTOOLCHAIN=local go test -race -count=1 ./...
```

在允许本地监听端口的终端运行：

```bash
SERVER_PORT=8081 APP_RUNTIME_ROLE=api go run ./cmd/personalos
```

本进程默认只监听 `127.0.0.1:8081`，默认不读取现有 `.env`，不连接数据库、不应用 SQL、不调用模型、不启动提醒/索引 Worker。显式设置 SERVER_PORT 会覆盖端口，但监听地址仍固定回环。

只有在系统环境显式设置 `GO_DOTENV_ENABLED=true` 后才查找 .env（当前目录和最多三层父目录），`APP_DOTENV_ENABLED=false` 仍可关闭。系统环境优先于 profile 静态值，静态值优先于文件；占位变量仍可从文件回落。解析保持 Java 的引号、空值、重复键顺序、Unicode strip 与 `$`/`#` 字面量规则，不执行 shell。测试只使用临时目录里的虚构数据。

诊断配置支持现有角色profile和APP_RUNTIME_ROLE覆盖。Foundation另外使用固定7文件/17文档的Properties，保留原profile顺序/占位符/来源规则；12类record Binder及MCP动态配置已有独立对照，业务调用方按78处台账分阶段接线。任意外部YAML位置/import、profile分组和未支持框架扩展明确拒绝。

默认诊断模式在监听前拒绝prod、生产安全模式、认证或CSRF开关。Foundation允许已验收的本地/共享会话与安全链，但仍拒绝prod/生产模式，并保持回环监听与readyz=503；这些P8发布守卫不会因P1验收而解除。

端点 | 行为
--- | ---
`GET /livez` | HTTP 200，表示进程可响应；`phase=bootstrap`、`ready=false`
`GET /readyz` | HTTP 503，明确业务迁移未完成
`HEAD /livez`、`HEAD /readyz` | 相同状态，无正文
诊断端点的其他方法 | HTTP 405
现有 `/api/v1/*` 等未迁移端点 | HTTP 404，不提供成功占位

两个诊断端点是本阶段新增能力，JSON 为 `{process,phase,ready,role}`；它们不是现有 Java `/api/v1/health` 或 Actuator 的替代实现。Worker 在此阶段也不执行任务，仅可用来验证角色与隔离配置。

五种角色已通过真实进程 HTTP/HEAD/405/404、恒定未就绪、SIGTERM 与端口释放冒烟。可从仓库根目录复验（脚本不读取 .env，不沿用数据库/模型环境）：

```bash
python3 scripts/migration/smoke_go_process.py --binary /absolute/path/to/personalos
```

真实数据库集成仅连接脚本创建的独立临时容器（需 Docker 和固定镜像/模块缓存），从仓库根目录运行：

```bash
python3 scripts/migration/run_go_postgres_tests.py
```

基础依赖与五角色进程生命周期已验收；业务Worker与生产切换在后续阶段，详见[验证记录](../specs/033-go-rewrite/validation.md)。

## 接下来实施

下一阶段为[P5记忆、日历与提醒投递](../specs/033-go-rewrite/tasks.md#p5-记忆日历与提醒投递)。P4收口见[验收报告](../specs/033-go-rewrite/p4-completion.md)。每阶段填写源码/测试/差分证据并独立提交。

## 显式 P1 基础模式（尚未验收发布）

使用 `personalos --foundation --migrations /absolute/backend/src/main/resources/db/migration` 启动。
必须在专用环境显式设置 `SERVER_PORT`、`POSTGRES_HOST`、`POSTGRES_PORT`、`POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD`，不能使用 YAML 连接默认值。不自动执行、baseline 或 repair SQL；需要先由独立 Java Flyway 完成原迁移。已有真实默认 V1 基线历史时才加 `--allow-baseline-v1`。

数据库实际读取解析后的 `spring.datasource.*`，支持单主机 JDBC TCP 地址和显式端口，不接受查询参数或多主机。默认 TLS `verify-full`；`GO_POSTGRES_ROOT_CERT` 指向测试 CA，回环明文测试需显式 `GO_POSTGRES_SSL_MODE=disable`。绝不为测试关闭非回环 TLS 校验。

基线 Hikari 最大连接数/毫秒等待已接入；`worker-index`、`worker-review`、`worker-delivery` profile 上限4，其余默认10。仅设运行角色不会自动加载 profile 文件。`monitoring` profile 启动独立回环管理端口（默认9091，可设 `MANAGEMENT_SERVER_PORT`），限流和会话 Redis 仍由各自原开关/URI/namespace 配置；三份 CA 分别使用 `GO_REDIS_SESSION_ROOT_CERT`、`GO_REDIS_RATE_LIMIT_ROOT_CERT`、`GO_REDIS_EMBEDDING_ROOT_CERT` 的绝对路径。P3共享嵌入缓存使用独立的`REDIS_ENABLED/REDIS_URI/REDIS_NAMESPACE/REDIS_TIMEOUT_MS`，不能复用会话/限流客户端。

基础模式允许已实现的认证/CSRF；`prod`继续拒绝。默认诊断规则不变。基础模式不执行业务Worker，`readyz`保持未就绪；P2任务/确认、P3模型及P4对话路由已接，其他未实现业务404。P1–P4验收通过不表示P8发布验收，禁止替换Java实例。
