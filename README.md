# Personal AI OS

开源、自托管、可扩展的**个人智能操作系统**。

用自然语言和个人 AI 助手交互，让它帮你管理任务、检索自己的知识库、安排日程与提醒——全部跑在你自己的机器上。

> **当前状态**：阶段 4（日程与定时提醒）已完成。
> 四个模块可用：对话、任务、知识库、日程与提醒。助手共可调用 14 个工具。

## 这个项目要做什么

1. 用自然语言与个人 AI 助手交互
2. 通过 AI 工具调用管理个人任务
3. 构建个人知识库，支持 RAG 检索问答
4. 管理个人日程与定时提醒
5. 多轮对话与可持久化的会话记忆
6. 通过 MCP 扩展外部工具
7. 未来扩展 Coding Agent、资讯 Agent 等独立模块

**明确不做**（第一版）：多租户 SaaS、支付系统、复杂权限管理。
第一版只支持**本地单用户**；若需对公网开放，必须先完成身份认证设计。

## 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| 后端 | Java / Spring Boot / Spring AI (BOM) | 21 / 4.1.1 / 2.0.1 |
| Web | Spring Web MVC + SSE | — |
| 持久化 | Spring Data JPA + Flyway | Flyway 12.4.0 |
| 数据库 | PostgreSQL + pgvector | 17（镜像 `pgvector/pgvector:pg17`） |
| 构建 | Maven（含 Wrapper） | 3.9+ |
| 前端 | Vue 3 / TypeScript / Vite | 3.5 / — / 8 |
| 前端生态 | Vue Router / Pinia / Element Plus / Axios | 5 / 4 / 2 / 1 |
| 测试 | JUnit 5 + Mockito + Testcontainers | 由 Boot BOM 管理 |

版本选择依据与兼容性论证见 [`specs/001-project-skeleton/research.md`](specs/001-project-skeleton/research.md)。

## 目录结构

```
personal-ai-os/
├── backend/                    Spring Boot 应用（根包 com.personalos）
│   ├── mvnw / mvnw.cmd         Maven Wrapper（无需预装 Maven）
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/personalos/
│       │   ├── PersonalOsApplication.java
│       │   ├── common/         统一响应 / 错误码 / 全局异常 / 健康检查 / 公共配置
│       │   ├── ai/             阶段 1：模型接入与 ChatClient
│       │   ├── chat/           阶段 1：会话与消息
│       │   ├── memory/         阶段 1：会话记忆
│       │   ├── agent/          阶段 2：Agent 编排与工具调用
│       │   ├── task/           阶段 2：任务
│       │   ├── knowledge/      阶段 3：知识库与 RAG
│       │   └── calendar/       阶段 4：日程与提醒
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/   Flyway 迁移脚本
│       └── test/java/com/personalos/
│           ├── PersonalOsApplicationTests.java      （Testcontainers 集成测试）
│           └── common/                              （单元测试）
├── frontend/                   Vue 3 + TypeScript + Vite
│   └── src/{api,router,stores,types,views,styles}
├── docs/                       架构与开发文档
├── specs/                      Spec Kit 特性规格
├── .specify/                   Spec Kit 配置与项目宪法
├── compose.yml                 PostgreSQL + pgvector
├── .env.example                环境变量模板
└── LICENSE                     Apache-2.0
```

## 快速开始

前置：JDK 21、Docker（含 `docker compose`）、Node.js `^20.19.0 || >=22.12.0`。
**无需预装 Maven** —— 使用仓库自带的 `backend/mvnw`。

### 1. 启动基础设施

```bash
cp .env.example .env
docker compose up -d
docker compose ps      # 等待 postgres 变为 healthy
```

### 2. 启动后端

```bash
cd backend
./mvnw spring-boot:run
```

自检：

```bash
curl http://localhost:8080/api/v1/health
curl http://localhost:8080/actuator/health
```

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev            # http://localhost:5173
```

首页会实时展示后端健康状态。更详细的步骤、配置项与排错见 [`docs/development.md`](docs/development.md)。

## 构建与测试

```bash
# 后端：单元测试 + Testcontainers 集成测试（需 Docker 运行）
cd backend && ./mvnw -B verify

# 前端：类型检查 + 生产构建
cd frontend && npm run type-check && npm run build
```

## 阶段路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 项目骨架：Monorepo、后端基座、数据库迁移、前端基座、文档 | ✅ |
| 1 | AI 对话与聊天历史（ChatClient + ChatMemory + SSE） | ✅ |
| 2 | 任务 CRUD 与 Tool Calling（含两阶段人工确认与两层幂等） | ✅ |
| 3 | 个人知识库与 RAG（pgvector 向量检索 + 引用溯源） | ✅ |
| 4 | 日程与定时提醒（重复规则 + 到点提醒） | ✅ |
| 5 | Docker 部署、测试、文档、开源发布 | ⏳ |

## 日程与提醒

支持「不重复 / 每天 / 每周 / 每月 / 工作日」四类重复规则，以及全天事件。
提醒同时覆盖**日程**与**任务截止时间**；前端每 30 秒轮询一次「已到点未读」的提醒。

```bash
AI_CALENDAR_SCAN_ENABLED=true        # 定时扫描开关
AI_CALENDAR_SCAN_INTERVAL_MS=60000   # 扫描间隔
AI_CALENDAR_DEFAULT_LEAD=15m         # 任务提醒的默认提前量（日程可逐个覆盖）
```

**几条刻意的设计选择**：

- **调度是「数据库状态驱动的周期扫描」**，不是为每条提醒注册内存定时任务 ——
  后者重启即丢，用户会静默地收不到提醒。重启后行为一致这一点有测试与真机验证。
- **幂等靠数据库唯一约束**（`来源类型 + 来源标识 + 发生时刻`）+ `ON CONFLICT DO NOTHING`，
  不靠「先查再插」。反复扫描、重启、甚至系统时间回拨都不会产生重复提醒。
- **删除日程会一并删除它的提醒**；扫描还会清理「来源已不存在」的孤儿提醒 ——
  后者兜住那些不经过清理的删除路径（典型是任务删除：`task` 模块并不知道提醒的存在）。

> **「到点提醒」的送达方式是页面轮询**，不是推送。本地单机没有推送通道，
> 因此提醒落库、由页面定时拉取；页面关闭时不会弹通知，但重启后仍能看到未读提醒。

## 知识库配置

上传 `.md` / `.markdown` / `.txt`（UTF-8，≤5 MiB）即自动解析、分块、向量化。
检索与问答走 pgvector 的余弦相似度，默认返回 4 条、相似度阈值 0.3。

```bash
AI_EMBEDDING_PROVIDER=ollama          # 默认，本地无需密钥
OLLAMA_EMBEDDING_MODEL=bge-m3         # 1024 维
AI_KNOWLEDGE_EMBEDDING_DIMENSIONS=1024  # 必须与迁移里的 vector(1024) 一致
```

> ⚠️ **换嵌入模型前先确认输出维度**：维度已写进 Flyway 迁移（`vector_store.embedding`）。
> 维度不一致时应用会**在启动阶段就失败**并给出修复指引，而不是等到写入时才报 SQL 错。

本地跑真实语义检索需要 `ollama pull bge-m3`（约 1.2GB）；
**自动化测试不需要任何模型** —— 它用确定性词袋嵌入替代，详见 `docs/development.md`。

## 配置模型提供商

默认走**本地 Ollama**（无需密钥）；也可以指向任意 OpenAI 兼容服务（DeepSeek / 通义 / Kimi / 智谱 / vLLM 等）：

```bash
# 本地 Ollama（默认）
AI_CHAT_PROVIDER=ollama OLLAMA_CHAT_MODEL=qwen2.5:7b

# 云端 OpenAI 兼容服务
AI_CHAT_PROVIDER=openai \
OPENAI_BASE_URL=https://api.deepseek.com \
OPENAI_API_KEY=sk-xxx \
OPENAI_CHAT_MODEL=deepseek-chat
```

**没有任何模型也能跑**：不配置模型时应用照常启动（健康检查可用），对话接口返回明确的 `50300 AI 模型未配置`。
用于本地联调的 OpenAI 兼容 mock 见 [`docs/development.md`](docs/development.md) 第五节。

## 开发约定

本项目使用 [Spec Kit](https://github.com/github/spec-kit) 做规格驱动开发，**项目宪法是全仓库最高约束**：

- 宪法：[`.specify/memory/constitution.md`](.specify/memory/constitution.md)
- 规格：`specs/NNN-<feature>/`（spec → plan → research → data-model → contracts → tasks）
- 阶段严格串行，不提前实现后续阶段

关键工程约定：

- 结构变更一律走 Flyway，`ddl-auto` 只能是 `validate`
- 所有写操作经过 Service 层；不向前端暴露 JPA Entity
- 统一响应结构；错误响应不得泄漏堆栈
- 统一构造器注入，禁止字段注入
- 仓库中不得出现任何密钥

## 文档

- [架构说明](docs/architecture.md)
- [本地开发指南](docs/development.md)
- [阶段 0 规格](specs/001-project-skeleton/spec.md) ｜ [实现计划](specs/001-project-skeleton/plan.md) ｜ [版本决策与踩坑](specs/001-project-skeleton/research.md)

## 许可证

[Apache License 2.0](LICENSE)
