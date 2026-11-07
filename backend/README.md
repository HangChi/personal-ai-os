# Java 主后端

Java 21 + Spring Boot 4.1.1 + Spring AI 2.0.1，是 Personal AI OS 的主后端。与 [Go 候选](../backend-go/README.md)共用前端和 SQL 来源；[根 README](../README.md)提供完整快速开始。

## 运行

仓库根目录先复制 `.env.example` 为 `.env` 并启动 `docker compose up -d`，等待 PostgreSQL 健康。另开终端：

```bash
cd backend
./mvnw spring-boot:run
```

默认业务端口 8080；数据库宿主端口 55432。Java 自动查找 `.env`，系统环境优先。模型默认 Ollama，聊天和嵌入独立配置。完整项见[配置](../docs/configuration.md)，无真实模型联调见[开发指南](../docs/development.md)。

```bash
./mvnw -B test
./mvnw -B -DskipTests package
```

测试需要 Docker，使用 Testcontainers；`test` 包含 JaCoCo 门禁。打包跳过测试只证明编译/打包完成，不是回归通过。

## 源码入口

源码根为 `src/main/java/com/personalos/`：

| 目录 | 职责 |
| --- | --- |
| `common/ai` | 模型配置、ChatClient、上下文、工具执行/预算 |
| `chat` | 会话、流式对话、持久 Run、附件和用量 |
| `agent` | 任务工具、计划、澄清、工具审计 |
| `knowledge` | 解析切块、向量召回、多阶段 RAG、索引与来源治理 |
| `memory`、`calendar`、`task` | 记忆、日程提醒、任务工作区 |
| `mcp`、`workflow`、`maintenance` | 双向工具协议、只读报告、维护 |
| `common` | API 信封、认证、确认、幂等与协调等基础能力 |

SQL 唯一来源为 [src/main/resources/db/migration](src/main/resources/db/migration)，当前 V1–V49。Flyway 负责执行/校验，JPA `ddl-auto=validate`；禁止通过 JPA 自动更新或修改旧迁移绕过历史检查。

## 进一步阅读

- [整体架构](../docs/architecture.md)、[Java/Go 对应与差异](../docs/backend-parity.md)。
- [Agent 运行](../docs/agent-runtime.md)、[全部内置工具](../docs/agent-tools.md)、[记忆/上下文](../docs/memory-and-context.md)。
- [RAG 全流程](../docs/rag.md)、[切块](../docs/rag-ingestion.md)、[检索与证据](../docs/rag-retrieval.md)。
- [API 与 SSE](../docs/api.md)、[Controller 路由参考](../docs/reference/http-routes.md)。
- [独立 Worker](../docs/distributed-workers.md)、[运维](../docs/operations.md)、[安全](../SECURITY.md)。
