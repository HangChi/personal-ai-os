<img src="frontend/public/favicon.svg" alt="Personal AI OS" width="64" height="64">

# Personal AI OS

**把对话、任务、知识库、日程和长期记忆连接起来的开源个人 AI 工作台。**

自托管，面向单用户。你可以让助手查资料、创建任务、安排日程，也可以查看它调用了什么工具、依据哪些资料作答，以及哪些操作正在等待你的确认。

[快速开始](#快速开始) · [文档中心](docs/README.md) · [Agent 实现](docs/agent-runtime.md) · [RAG 全流程](docs/rag.md) · [贡献指南](CONTRIBUTING.md)

## 功能

| 能力 | 项目如何实现 |
| --- | --- |
| 对话与执行 | 流式回答、会话模型选择、持久化 Agent Run、停止/继续、澄清问题、执行中引导和排队 |
| 工具调用 | 任务、日程、提醒、记忆、知识检索和计划工具；统一参数校验、预算、幂等、审计和高风险操作确认 |
| 个人知识库 | Markdown、文本、PDF、DOCX、图片摄入；结构切块、向量/关键词混合召回、重排、证据精审和引用溯源 |
| 资料治理 | 知识空间、来源版本/适用范围/有效期、冲突审查、异步索引、公开网页采集 |
| 任务与日程 | 任务项目/子任务/标签/看板、重复日程、日历交换、页面提醒和可选 Web Push/Webhook |
| 长期记忆 | 显式记忆、置顶、相关性排序、近重复取代和默认关闭的自动沉淀 |
| 扩展与维护 | MCP 双向接入、可选联网搜索、只读工作流、全局搜索、业务导入导出、备份与运行指标 |

本项目不提供多租户隔离、团队角色权限或任意代码执行沙箱。模型使用本地 Ollama 或配置的 OpenAI 兼容端点；连接外部模型时，提交的对话和检索片段会发送给该提供商。

## 两个后端版本

两版共用 `frontend/`、业务协议和 V1–V49 SQL 迁移来源，但启动方式和发布状态不同。

| | Java：`backend/` | Go：`backend-go/` |
| --- | --- | --- |
| 技术 | Java 21、Spring Boot 4.1.1、Spring AI 2.0.1 | Go 1.27.0+、显式 HTTP/业务模块装配 |
| 当前定位 | 主后端，根目录 Compose 默认使用它 | 迁移候选，保留生产切换门禁 |
| 默认启动 | 业务应用，默认端口 8080 | 离线诊断；业务模式必须显式开启 |
| 数据迁移 | Flyway 执行，JPA 校验结构 | Java/独立 Flyway 先迁移，Go 只读校验历史 |
| 文档入口 | [Java 指南](backend/README.md) | [Go 指南](backend-go/README.md) |

> [!IMPORTANT]
> Go 候选仍拒绝生产 profile，`/readyz` 保持 503；已有功能或隔离测试通过不等于获准替换 Java 实例。完整差异见[双版本说明](docs/backend-parity.md)。不要让两版同时写入现有业务库。

## 快速开始

以下启动 **Java 后端 + 共用前端**。命令分别在仓库根目录或标明的子目录执行。

前置要求：JDK 21、Docker Compose、Node.js 22.18+（覆盖前端逻辑测试要求）。Maven 使用仓库 Wrapper，无需单独安装。

```bash
git clone https://github.com/HangChi/personal-ai-os.git
cd personal-ai-os
cp .env.example .env
docker compose up -d
docker compose ps
```

数据库默认绑定 `127.0.0.1:55432`。等待 `postgres` 健康后，在另一个终端启动后端：

```bash
cd backend
./mvnw spring-boot:run
```

再打开一个终端，从仓库根目录启动前端：

```bash
cd frontend
npm ci
npm run dev -- --host 127.0.0.1
```

访问 <http://127.0.0.1:5173>。健康检查：

```bash
curl http://127.0.0.1:8080/api/v1/health
```

默认模型配置为 Ollama：聊天 `qwen2.5:7b`，嵌入 `bge-m3`（1024 维）。需要使用 AI 时，先确保对应模型服务和模型可用，或按[配置指南](docs/configuration.md)配置自己的兼容端点。没有可用模型服务时，任务等本地业务仍可使用，AI 请求会报告配置或上游错误。

想把 Java 前后端也放入容器，可从根目录执行：

```bash
docker compose --profile app up -d --build
```

访问 <http://127.0.0.1:8088>；容器中的模型地址必须可从后端容器访问，不能直接沿用指向容器自身的 `localhost`。更多步骤见[开发指南](docs/development.md)和[运维手册](docs/operations.md)。

> [!WARNING]
> 本地默认 `AUTH_ENABLED=false`，仅适合受信本机访问。Java 公网部署使用 `compose.production.yml`，配置认证、CSRF、Secure Cookie 和 HTTPS，详见[安全策略](SECURITY.md)。Go 的候选部署另见其独立部署文档。

## 项目结构

```text
backend/       Java 主后端、Flyway SQL、单元与集成测试
backend-go/    Go 候选后端、独立部署与迁移对照测试
frontend/      两版共用的 Vue 3 + TypeScript + Vite 工作台
docs/          当前架构、Agent/RAG 实现、开发与运维文档
scripts/       本地运行、备份、评测、隔离回归与双端对照工具
specs/         特性规格、设计决策和有日期的验证证据
deploy/        Java 反向代理与监控配置
```

数据使用 PostgreSQL 17 + pgvector。Redis、RabbitMQ、OpenSearch、S3 兼容存储均为可选扩展，各自承担缓存/会话、唤醒、搜索投影和附件存储职责。

## 开发与验证

在仓库根目录执行：

```bash
# Java：需要 Docker，包含覆盖率门禁
(cd backend && ./mvnw -B test)

# Go：需 Go 1.27.0+；race 需要支持的平台及 C 工具链
(cd backend-go && go test -race -p 1 -count=1 -timeout=20m ./...)
(cd backend-go && go vet -tags=integration ./... && go build ./...)

# 前端：先 npm ci
(cd frontend && npm run lint && npm run format:check && npm run type-check \
  && npm run test:logic && npm run test:component && npm run build)

# 文档链接、工具目录与双端迁移来源检查；不调用模型
python3 scripts/docs/check_docs.py
```

真实模型质量评测会产生外部请求和费用，独立于上述工程回归，见[评测指南](docs/evaluation.md)。

## 从哪里继续

- 想使用工作台：[使用指南](docs/user-guide.md)、[对话快捷指令](docs/chat-commands.md)。
- 想理解助手实现：[Agent 执行链路](docs/agent-runtime.md)、[工具目录与扩展](docs/agent-tools.md)、[记忆与上下文](docs/memory-and-context.md)。
- 想理解知识问答：[RAG 总览](docs/rag.md)、[摄入与切块](docs/rag-ingestion.md)、[检索与证据](docs/rag-retrieval.md)。
- 想部署或贡献：[开发指南](docs/development.md)、[运维手册](docs/operations.md)、[贡献指南](CONTRIBUTING.md)、[Apache-2.0](LICENSE)。

## 贡献与许可证

欢迎提交缺陷、文档改进与 Pull Request；流程和双版本同步要求见[贡献指南](CONTRIBUTING.md)。安全问题通过[私有报告渠道](SECURITY.md)提交。

本项目采用 [Apache License 2.0](LICENSE)。Go 依赖与系统 PDF 组件的许可另见[第三方声明](backend-go/THIRD_PARTY_NOTICES.md)。
