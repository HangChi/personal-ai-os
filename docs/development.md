# 开发指南

本指南覆盖 Java 主后端、Go 候选后端与共用前端。首次启动见[根 README](../README.md)，行为差异见[双版本说明](backend-parity.md)。以下命令未注明时均从仓库根目录执行；长驻进程各用一个终端。

## 环境要求

| 组件 | 要求 | 用途 |
| --- | --- | --- |
| JDK | 21 | Java 应用、Flyway oracle、Maven 测试 |
| Docker Compose | 支持当前 Compose 文件 | PostgreSQL/pgvector、Testcontainers、隔离回归 |
| Node.js | 推荐 22.18+ | Vite 构建与原生 TypeScript 逻辑测试 |
| Go | 1.27.0+ | Go 候选；race 还需要平台支持及 C 工具链 |
| Poppler | `pdfinfo`、`pdftotext` | Go PDF 解析；Java 使用 PDFBox |
| Python | 3，具体评测依赖见脚本 | 文档检查、迁移与评测工具 |

前端 package engines 为 `^20.19.0 || >=22.12.0`，但 `test:logic` 使用默认开启的类型剥离，需要 Node 22.18+。Maven 使用 `backend/mvnw`，无需另装 Maven。

## Java + 前端

```bash
cp .env.example .env
docker compose up -d
docker compose ps
```

Compose 默认仅启动 PostgreSQL，宿主绑定 `127.0.0.1:55432`。在后端终端执行：

```bash
cd backend
./mvnw spring-boot:run
```

在前端终端执行：

```bash
cd frontend
npm ci
npm run dev -- --host 127.0.0.1
```

打开 <http://127.0.0.1:5173>，检查 `GET http://127.0.0.1:8080/api/v1/health`。若端口不符，检查已导出的 `SERVER_PORT`；以启动日志为准。Java 会查找根目录/父目录 `.env`，系统环境优先。不要把含真实密钥的配置或展开后的 Compose 输出提交到日志。

停止数据库用 `docker compose down`，保留卷；`down -v` 会删除数据，只能用于确认可丢弃的开发环境。生产启动使用专用 Compose 与 `.env.production`，见[运维手册](operations.md)。

## Go 候选 + 同一前端

Go 默认运行离线诊断，不会因为启动成功就开放业务。完整隔离业务启动、TLS 与迁移前置条件见 [Go 开发指南](../backend-go/docs/development.md)。切换前端代理：

```bash
cd frontend
VITE_PROXY_TARGET=http://127.0.0.1:8081 npm run dev -- --host 127.0.0.1
```

不要同时让 Java/Go 写入已有业务库。Go 使用独立新库，先由 Java/独立 Flyway 执行共享 SQL，再只读校验；不提供 Go migrate/repair。Go 生产拒绝与 `/readyz=503` 仍是发布保护。

## 无真实模型的联调

启动本地确定性 mock（不访问模型提供商）：

```bash
python3 scripts/dev/mock-openai-server.py --port 9099
```

另一个终端启动 Java：

```bash
cd backend
AI_CHAT_PROVIDER=openai AI_EMBEDDING_PROVIDER=openai \
OPENAI_BASE_URL=http://127.0.0.1:9099 OPENAI_API_KEY=mock-key \
OPENAI_CHAT_MODEL=mock-model OPENAI_EMBEDDING_MODEL=mock-embedding \
./mvnw spring-boot:run
```

mock 支持流式/非流式聊天、嵌入及部分任务工具：`创建任务：写周报`、`查看待办`、`删除任务：<UUID>`。删除仍需人工确认。它验证 HTTP/SSE、工具和持久化接线，不代表语义质量。其词袋哈希向量与真实嵌入分布不同；需要时仅在隔离测试进程降低 `AI_KNOWLEDGE_SIMILARITY_THRESHOLD`，不要据 mock 调整生产阈值。

## 工程检查

```bash
(cd backend && ./mvnw -B test)
(cd backend-go && go test -race -p 1 -count=1 -timeout=20m ./...)
(cd backend-go && go vet -tags=integration ./... && go build ./...)
(cd frontend && npm run lint && npm run format:check && npm run type-check \
  && npm run test:logic && npm run test:component && npm run build)
python3 scripts/docs/check_docs.py
python3 scripts/docs/generate_routes.py --check
```

Java 测试使用 Testcontainers，Docker 不可用会失败；`test` 包含 JaCoCo 行 83%/分支 65%门禁，报告在 `backend/target/site/jacoco/`。Go 设施验证使用创建临时容器的 runner，详见其指南。前端 `test:logic` 覆盖纯函数/store，`test:component` 使用 Vitest、happy-dom 与 Vue Test Utils 覆盖渲染和事件。编译成功不能代替交互验收。

[Java/前端 CI](../.github/workflows/ci.yml)与 [Go CI](../.github/workflows/go.yml)分开运行。文档检查命令为本地维护检查，不因写在这里就成为远端 CI 已执行的证据。真实模型、浏览器、性能和发布验证另见[评测指南](evaluation.md)。

## 开发约定与结构变更

- Java Controller 保持 DTO/参数与协议职责，业务放 Service，数据库访问放 Repository；模型/解析/网络调用放在短事务之外。
- Go 显式传递 context、数据库和生命周期依赖；保留错误信封、确认、幂等、取消与未知写保护。
- 前端共用 API/store，新增字段同步两版 DTO；工具 Schema 需要独立更新，HTTP 新字段不会自动变成 Agent 参数。
- SQL 只在 `backend/src/main/resources/db/migration/` 追加 `V<n>__description.sql`，不改已发布脚本；当前 V1–V49。Go 固定 V1–V42 对照是历史基线，不应重写。
- 数据库结构先前向兼容，再改消费者；重放空库、升级旧库、恢复和 Go 历史校验分别验证。回滚通常是前向修复或受控备份恢复，不手工删除 Flyway 历史。
- 更新接口后执行 `python3 scripts/docs/generate_routes.py`，同步[双版本说明](backend-parity.md)、Agent/RAG 文档及测试。

贡献流程见[贡献指南](../CONTRIBUTING.md)。

## 常见问题

| 症状 | 排查 |
| --- | --- |
| 数据库连接失败 | 容器健康、宿主映射端口、系统环境覆盖；Go 另查 CA/SAN/TLS |
| Flyway/JPA 校验失败 | SQL 完整性、历史 checksum、实际 DDL；不要用 `ddl-auto=update` 绕过 |
| Vite `/api` 返回 502 | 后端监听地址/端口及 `VITE_PROXY_TARGET`，代理变更后重启 Vite |
| Go 只有 livez/readyz | 默认诊断符合设计；业务需 `--foundation` 与模块开关 |
| 知识上传失败 | 文件文本层/长度、嵌入模型、1024 维、提供商批次限制、Go Poppler |
| 429 或 50203 | 分别查全局准入与每轮 Agent 预算，见[可靠性](agent-reliability.md) |
| 前端测试不识别 TS | 使用 Node 22.18+，不要只按 Vite 最低版本判断 |

开发脚本可能写入业务数据。`seed-demo-data.py`、`rebuild-corpus.py`、浏览器验收脚本不能未经检查就对现有实例运行；优先使用隔离夹具。
