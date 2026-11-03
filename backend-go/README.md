# Personal AI OS：Go 迁移起点

本目录位于 `go` 分支，是 Java 后端的增量迁移实现。**当前仅完成基础契约与本地诊断启动代码，不能替代现有 Java 后端。**

全量范围、逐文件来源、迁移状态与验收要求见 [迁移文档](../specs/033-go-rewrite/README.md)。现有 Java 源码、前端、SQL 与运行实例继续保留。

## 已有代码

- `internal/platform/api`：响应信封、19 个 Java 错误码及 HTTP 映射、两类分页与 Java offset 溢出边界。
- `internal/platform/config`：combined/api/index/review/delivery 角色规则、严格端口与角色解析。
- `internal/platform/http`：请求 ID 校验、Java trim 兼容、响应回显及 context 传递。
- `internal/app`：迁移诊断端点、未知 API 明确返回 404、Worker 业务入口隔离。
- `cmd/personalos`：回环监听、启动提示、信号处理和超时退出。

错误码测试使用从固定 Java 提交提取的 [快照](internal/platform/api/testdata/java-error-codes.json)，不从 Go 实现生成期望值。其他测试覆盖分页边界、角色矩阵、请求 ID 并发隔离以及诊断的未就绪行为。

尚无数据库连接/迁移、认证/CSRF/登录、模型、任务、聊天、Agent、知识库、日历、记忆、MCP 或后台调度。请求 ID 的后台执行/日志传播也尚未接入。没有第三方 Go 运行时依赖。

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

本进程默认只监听 `127.0.0.1:8081`，不读取现有 `.env`、不连接数据库、不应用 SQL、不调用模型、不启动提醒/索引 Worker。显式设置 SERVER_PORT 会覆盖端口，但监听地址仍固定回环。

端点 | 行为
--- | ---
`GET /livez` | HTTP 200，表示进程可响应；`phase=bootstrap`、`ready=false`
`GET /readyz` | HTTP 503，明确业务迁移未完成
`HEAD /livez`、`HEAD /readyz` | 相同状态，无正文
诊断端点的其他方法 | HTTP 405
现有 `/api/v1/*` 等未迁移端点 | HTTP 404，不提供成功占位

两个诊断端点是本阶段新增能力，JSON 为 `{process,phase,ready,role}`；它们不是现有 Java `/api/v1/health` 或 Actuator 的替代实现。Worker 在此阶段也不执行任务，仅可用来验证角色与隔离配置。

实际进程绑定/退出的验证状态见 [验证记录](../specs/033-go-rewrite/validation.md)，不能以 httptest 的处理器测试替代真实启动验收。

## 接下来实施

继续 [P1 任务](../specs/033-go-rewrite/tasks.md#p1-基础设施与数据兼容)：先完成真实 PostgreSQL/pgvector、42 个历史迁移、认证/CSRF、完整中间件与运行协调，再进入 P2 任务模块。每阶段填写源码/测试/差分证据并独立提交。
