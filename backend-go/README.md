# Personal AI OS · Go 候选后端

Java 主后端的 Go 迁移候选，与 [共用前端](../frontend/README.md)及 [Java SQL](../backend/src/main/resources/db/migration)协作。已包含任务工作区、对话/持久 Agent Run、工具与计划、记忆、日程提醒、RAG/治理、MCP、导入导出、只读工作流和维护等代码；实现范围不等于完整质量或生产验收。

> [!IMPORTANT]
> **仍拒绝 prod / 生产模式，`/readyz` 保持 503。** 不要替换现有 Java 实例，也不要双端同时写现有业务库。发布门禁与残余失败见 [P8 记录](../specs/033-go-rewrite/p8-completion.md)。

## 构建与诊断

要求 Go 1.27.0+，race 需要 C 工具链，PDF 需要 Poppler `pdfinfo`/`pdftotext`。依赖及许可证见 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)。

```bash
cd backend-go
go mod download
go mod verify
go test -race -p 1 -count=1 -timeout=20m ./...
go vet -tags=integration ./...
go build ./...
SERVER_PORT=8081 APP_RUNTIME_ROLE=api go run ./cmd/personalos
```

最后一条默认仅离线诊断：不读取现有 `.env`、不连接数据库/模型、不启动业务 Worker。`GET /livez` 返回进程存活，`GET /readyz` 返回 503；它们不是业务健康或发布批准。

## 业务候选

需要显式 `--foundation`，完整功能用 `--p8`（包含 p7/p6/p5）。必须先准备**独立新数据库**、TLS、全部连接参数及由 Java/独立 Flyway 执行的迁移；Go 仅校验历史，不执行 SQL。

```bash
# 完成开发指南中的隔离数据库/TLS与环境准备后，在 backend-go 执行
SERVER_PORT=8081 APP_RUNTIME_ROLE=combined \
go run ./cmd/personalos --foundation --p8 \
  --migrations /absolute/path/personal-ai-os/backend/src/main/resources/db/migration
```

这不是可跳过前置条件的单命令安装。步骤见[开发指南](docs/development.md)，共享算法见 [Agent](docs/agent.md)与 [RAG](docs/rag.md)，候选容器与五角色见[部署](deploy/README.md)。生产拒绝不会因这些业务开关解除。

## 文档导航

- [Go 文档中心](docs/README.md)、[整体架构](../docs/architecture.md)、[双版本对应/差异](../docs/backend-parity.md)。
- [Agent 工具调用全链路](../docs/agent-runtime.md)、[23 个标准工具与可选扩展](../docs/agent-tools.md)、[上下文/记忆](../docs/memory-and-context.md)。
- [RAG 全流程](../docs/rag.md)、[解析/切块](../docs/rag-ingestion.md)、[检索/重排/证据](../docs/rag-retrieval.md)。
- [安全与切换](docs/security.md)、[评测方法](../docs/evaluation.md)、[分阶段验证原记录](docs/validation-history.md)。

当前迁移目录为 V1–V49；固定 Java 提交 `cb5d694d62fa93742b5789b97273e76d931ce335` 的 V1–V42 对照仍保留为不可变历史基线。请按[同步规则](../docs/backend-parity.md)维护两版协议与文档。
