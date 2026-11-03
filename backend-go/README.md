# Personal AI OS：Go 迁移起点

本目录位于 `go` 分支，是 Java 后端的增量迁移实现。**当前完成基础契约、配置安全子集、SQL 事务基础、限流子集和本地诊断入口，不能替代现有 Java 后端。**

全量范围、逐文件来源、迁移状态与验收要求见 [迁移文档](../specs/033-go-rewrite/README.md)。现有 Java 源码、前端、SQL 与运行实例继续保留。

## 已有代码

- `internal/platform/api`：响应信封、19 个 Java 错误码及 HTTP 映射、两类分页与 Java offset 溢出边界。
- `internal/platform/config`：五种角色/端口、显式开启的 .env 解析/父目录查找、系统环境/profile/文件优先级、Java 生产安全校验与未完成安全能力的启动拒绝。
- `internal/platform/database`：显式 pgx 连接器、连接池与事务生命周期、获取超时/取消、UTC 类型与错误脱敏；已通过一次性 PostgreSQL 真实集成测试，尚未接入应用。边界见 [说明](internal/platform/database/README.md)。
- `internal/platform/migration`：原 SQL 目录的数字排序/完整性校验，全部 42 个原迁移已通过空库/合成数据隔离重放；生产执行器/Flyway 历史与恢复待完成，见 [说明](internal/platform/migration/README.md)。
- `internal/platform/coordination`：数据库时钟租约、续租、fencing 与事务完成后释放；已通过真实 PostgreSQL 竞争/过期/实际定时续租，应用尚未装配，见 [说明](internal/platform/coordination/README.md)。
- `internal/platform/modelgate`：模型本地/数据库共享并发与 Worker 的 API 预留容量；并发 HTTP 中间件持有整段 SSE 许可，真实应用尚未接线，见 [说明](internal/platform/modelgate/README.md)。
- `internal/platform/ratelimit`：本地令牌桶、PostgreSQL 原子共享桶、后端选择/故障策略和指标回调；数据库跨池/跨进程竞争已验，Redis 与应用接线待完成，见 [边界说明](internal/platform/ratelimit/README.md)。
- `internal/platform/auth`：单用户 BCrypt 凭据、Java 空白规则与失败锁定，74 项实际 Java 编码器对照；会话/CSRF/应用接线待完成，见 [边界说明](internal/platform/auth/README.md)。
- `internal/platform/http`：请求 ID 校验/回显/context、模型请求路径匹配、限流拒绝 JSON 与日志脱敏；限流尚未接入诊断应用。
- `internal/app`：迁移诊断端点、未知 API 明确返回 404、Worker 业务入口隔离。
- `cmd/personalos`：回环监听、启动提示、信号处理和超时退出。

错误码测试使用从固定 Java 提交提取的 [快照](internal/platform/api/testdata/java-error-codes.json)，不从 Go 实现生成期望值。模型路径另有实际运行 Java 得到的 216 项对照快照；其他测试覆盖分页边界、角色矩阵、请求 ID 并发隔离、限流边界以及诊断的未就绪行为。

应用尚未装配数据库连接/迁移、认证/CSRF/登录、模型、任务、聊天、Agent、知识库、日历、记忆、MCP 或后台调度。请求 ID 的后台执行/日志传播也尚未接入。第三方运行时依赖为固定版本的 pgx、x/crypto 及其间接依赖，见 [许可证声明](THIRD_PARTY_NOTICES.md)；首次构建需 `go mod download`，已有完整缓存后可离线验证。

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

支持 `SPRING_PROFILES_ACTIVE`/`SPRING_PROFILES_DEFAULT` 中现有运行角色 profile 的顺序覆盖，以及 `APP_RUNTIME_ROLE` 系统环境覆盖。这只是配置子集，尚无任意 YAML、命令行属性、动态 MCP 绑定和完整 145 个环境变量的装配。

`prod`、`APP_SECURITY_PRODUCTION_MODE=true`、`AUTH_ENABLED=true` 或 `APP_SECURITY_CSRF_ENABLED=true` 会在监听前被拒绝。Java 的生产密码/开关校验已移植，但认证、会话与 CSRF 实现尚未完成，不能因校验通过而允许生产启动。

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

完整依赖/Worker/生产退出仍未验收，详见 [验证记录](../specs/033-go-rewrite/validation.md)。

## 接下来实施

继续 [P1 任务](../specs/033-go-rewrite/tasks.md#p1-基础设施与数据兼容)：先完成真实 PostgreSQL/pgvector、42 个历史迁移、认证/CSRF、完整中间件与运行协调，再进入 P2 任务模块。每阶段填写源码/测试/差分证据并独立提交。
