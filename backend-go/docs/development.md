# Go 开发指南

要求 Go 1.27.0+；race 需要 C 工具链。PDF 需要 Poppler `pdfinfo`/`pdftotext`。Java/Flyway 对照需要 JDK 21、Maven 依赖与 Docker。通用开发及前端见[根开发指南](../../docs/development.md)。

## 本地检查与离线诊断

```bash
cd backend-go
go mod download
go mod verify
go test -race -p 1 -count=1 -timeout=20m ./...
go vet -tags=integration ./...
go build ./...
SERVER_PORT=8081 go run ./cmd/personalos
```

默认固定回环监听，不加载 `.env`，不连接业务设施。`/livez` 200、`/readyz` 503；未知业务接口为 404，没有成功占位。首次下载与离线缓存是两种条件：若用 `GOPROXY=off GOTOOLCHAIN=local`，须先准备完整依赖与正确工具链。

## 隔离业务运行

1. 建立专用 PostgreSQL/pgvector 新库及账号、可信 CA/SAN；不能指向已有 Java 业务库。
2. 使用 Java/独立 Flyway 执行 `backend/src/main/resources/db/migration/` 当前 V1–V49，完成 validate。Go 不 migrate/repair；固定基线测试仍核验原 V1–V42。
3. 系统环境显式提供 `POSTGRES_HOST`、`POSTGRES_PORT`、`POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD`、`SERVER_PORT`；TLS 默认 verify-full，`GO_POSTGRES_ROOT_CERT` 指向专用 CA。`GO_POSTGRES_SSL_MODE` 不应被用来规避生产 TLS 校验。
4. 按需要配置隔离模型端点、认证/CSRF和独立密钥。默认不读 `.env`，需要时只显式 opt-in `GO_DOTENV_ENABLED=true`；`APP_DOTENV_ENABLED=false` 强制禁用。解析不执行 shell。
5. 在 `backend-go` 启动：

```bash
go run ./cmd/personalos --foundation --p8 \
  --migrations /absolute/path/personal-ai-os/backend/src/main/resources/db/migration
```

`--foundation` 提供基础、任务/确认、模型、对话/Agent；`--p5` 加记忆/日历/提醒，`--p6` 包含 p5 并加知识，`--p7` 包含 p6 并加 MCP/搜索/导入导出，`--p8` 包含 p7 并加维护。容器模式另要求显式 `--container` 和认证/CSRF，见[部署](../deploy/README.md)。prod 拒绝及 readyz 503 始终保留。

共用前端用 `VITE_PROXY_TARGET=http://127.0.0.1:8081` 启动；切换后重新登录，不复用 Spring 会话。角色/profile 与监听规则见[架构](architecture.md)。

## 自动创建资源的隔离回归

以下从仓库根目录运行；runner 创建自身随机容器/回环端口/合成凭据并清理，不得将既有服务或真实备份替换进夹具。

```bash
python3 -m unittest discover -s scripts/migration -p 'test_*.py'
python3 scripts/migration/go_inventory.py check
python3 scripts/migration/go_config_disposition.py
python3 scripts/migration/run_go_postgres_tests.py --tls
python3 scripts/migration/run_go_redis_tests.py --tls --backup
python3 scripts/migration/run_go_s3_tests.py
python3 scripts/migration/run_go_demo_seed_tests.py
python3 scripts/migration/run_go_eval_tests.py --fixture
```

Linux/CI 的设施 runner 需要显式绝对路径 `GO_TEST_GOCACHE`、`GO_TEST_GOPATH`，先准备依赖；子进程使用 `GOPROXY=off GOTOOLCHAIN=local`。Java oracle 需预先构建，运行条件见[原验证配方](validation-history.md)。不得把合成词袋模型的 fixture PASS 当作真实模型质量。

## 质量验证与贡献

[评测指南](../../docs/evaluation.md)区分工程、检索、多阶段、Agent、RAGAS 与真实生产证据。真实模型/公开语料采集/容量测试需明确目标与调用预算；本指南不自动执行。既有失败、子集和完整范围均保留在 [validation-history](validation-history.md)和 [P8](../../specs/033-go-rewrite/p8-completion.md)。

遵循[贡献指南](../../CONTRIBUTING.md)及[双端同步规则](../../docs/backend-parity.md)：保持协议和 SQL 单一来源，增加两版测试；不要修改固定基线/标注来消除差异，不把单项修复写成全量认证。敏感信息与未知外部写处理见[安全](security.md)。
