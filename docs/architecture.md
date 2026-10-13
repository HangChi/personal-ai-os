# Personal AI OS 架构说明

> 本文件描述项目的整体结构与关键设计取舍。约束性内容以
> [`.specify/memory/constitution.md`](../.specify/memory/constitution.md) 为准。

## 1. 项目定位

Personal AI OS 是一个开源、自托管、可扩展的**个人智能操作系统**。目标能力：

1. 用自然语言与个人 AI 助手交互
2. 通过 AI 工具调用管理个人任务
3. 构建个人知识库，支持 RAG 检索问答
4. 管理个人日程与定时提醒
5. 多轮对话与可持久化的会话记忆
6. 通过 MCP 扩展外部工具
7. 未来扩展 Coding Agent、资讯 Agent 等独立模块

**范围边界**：第一版仅**本地单用户**，不含多租户、支付与复杂权限。若需对公网开放，必须先完成身份认证设计。

## 2. 总体结构

采用 **Monorepo + 模块化单体**：

```
personal-ai-os/
├── backend/     Spring Boot 应用（唯一的可部署单元）
├── frontend/    Vue 3 单页应用
├── docs/        架构与开发文档
├── specs/       Spec Kit 特性规格（spec / plan / research / tasks）
├── compose.yml  本地基础设施编排
└── .specify/    Spec Kit 配置与项目宪法
```

### 为什么是模块化单体，而不是微服务

当前只有一个部署单元、一个使用者。拆分微服务会立刻引入服务发现、分布式追踪、跨服务事务与部署编排的复杂度，而收益为零。宪法原则 I 明确禁止在第一版引入微服务。

### 为什么后端不拆 Maven 多模块

模块边界落在 **Java 包**上而非 Maven module 上。理由：多模块聚合会带来 POM 继承与依赖管理的维护成本，而对「单部署单元」没有任何可观测收益。待模块数量增长或需要独立发布时，可按包无损迁移为多模块。

## 3. 后端模块划分

根包 `com.personalos`，按**业务模块**分包（package-by-feature），而非按技术层分包：

| 模块 | 职责 | 落地阶段 |
|---|---|---|
| `common` | 统一响应、错误码、全局异常、健康检查、公共配置、**ChatClient 装配** | 阶段 0 ✅ / 阶段 1 ✅ |
| `ai` | 模型接入、`ChatClient` 装配、Advisor 链、Embedding 模型选择 | 阶段 1 ✅（装配落在 `common/ai`，业务编排在 `chat`） |
| `chat` | 会话管理、消息历史、SSE 流式输出 | 阶段 1 ✅ |
| `memory` | 会话记忆的持久化与检索策略 | 阶段 1 |
| `agent` | Agent 编排与工具（Tool Calling）注册 | 阶段 2 |
| `task` | 个人任务 CRUD 与状态流转 | 阶段 2 |
| `knowledge` | 文档摄入与 pgvector RAG 检索 | 阶段 3 |
| `calendar` | 日程事件与定时提醒 | 阶段 4 |

每个业务模块内部按 `controller / service / repository / entity / dto` 分层。

### 分层与依赖规则

```
controller  →  service  →  repository  →  database
     ↓            ↓
    dto         entity（仅模块内部流转）
```

- **写操作必须经过 Service 层**；Controller 不得直接调用 Repository。
- **不得向前端暴露 JPA Entity**；对外一律 DTO，并做参数校验。
- 统一使用**构造器注入**，禁止字段注入。
- `common` 不依赖任何业务模块，可被所有模块引用。

## 4. 安全边界（对 AI 能力的硬约束）

这是本项目与普通 CRUD 应用最大的不同：

1. **模型不得直接访问数据库、文件系统或执行任意代码。** 所有副作用必须经由 `agent` 模块暴露的、参数类型明确的 Java 接口。
2. **破坏性操作必须有人工确认环节。** 例如删除任务、覆盖知识文档，应由前端确认后再执行，而不是模型「说到就做到」。
3. **密钥只经环境变量注入**，仓库中只保留 `.env.example`。
4. 对公网开放之前，必须先设计身份认证。

## 5. 数据与迁移

- 数据库：PostgreSQL + pgvector。
- **所有结构变更由 Flyway 迁移脚本管理**，`spring.jpa.hibernate.ddl-auto` 固定为 `validate`，禁止 ORM 自动建表。
- 命名约定：`V<n>__<snake_case>.sql`，位置 `backend/src/main/resources/db/migration/`。

## 6. 统一响应与错误处理

所有 HTTP 接口返回同一个信封：

```json
{ "code": 0, "message": "OK", "data": {}, "timestamp": "2026-10-14T00:20:00Z" }
```

- `code == 0` 表示成功，非 0 为业务错误码。
- 由 `GlobalExceptionHandler` 统一处理业务异常、参数校验失败、资源未找到与兜底异常。
- 由 `ApiErrorController` 接管 `/error`，确保绕过 Controller 的错误也是统一结构。
- **兜底响应绝不包含堆栈、类名或 SQL 片段**。

详见 [`specs/001-project-skeleton/contracts/http-api.md`](../specs/001-project-skeleton/contracts/http-api.md)。

## 7. 前后端协作

- 开发期：Vite 把 `/api` 代理到 `http://localhost:8080`，因此**无需 CORS 配置**，前端代码也不感知后端地址。
- 前端所有请求经 `src/api/http.ts` 的统一 Axios 实例；`code != 0` 会被转成 rejected Promise。
- 生产期：由反向代理把静态产物与 `/api` 收敛到同一来源。

## 8. 技术栈与版本

| 层 | 技术 | 版本 |
|---|---|---|
| 语言 | Java | 21 (LTS) |
| 框架 | Spring Boot | 4.1.1 |
| AI | Spring AI（BOM） | 2.0.1 |
| 迁移 | Flyway | 12.4.0（由 Boot 管理） |
| 数据库 | PostgreSQL + pgvector | 17（`pgvector/pgvector:pg17`） |
| 构建 | Maven | 3.9+（含 Wrapper） |
| 前端 | Vue / Vite / TypeScript | 3.5 / 8 / 见 package.json |
| 状态/路由 | Pinia / Vue Router | 4 / 5 |
| UI | Element Plus | 2.x |

版本选择依据（含实测踩坑）见 [`specs/001-project-skeleton/research.md`](../specs/001-project-skeleton/research.md)，其中 **R-11 记录了 Spring Boot 4 模块化导致的一系列「静默失效」陷阱**，改动依赖前建议先读。

## 9. 阶段路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 项目骨架：Monorepo、后端基座、数据库迁移、前端基座、文档 | ✅ 完成 |
| 1 | AI 对话与聊天历史（ChatClient + ChatMemory + SSE） | ✅ 完成 |
| 2 | 任务 CRUD 与 Tool Calling | 待开始 |
| 3 | 个人知识库与 RAG（pgvector + vector_store 表） | 待开始 |
| 4 | 日程和定时提醒（Spring Scheduling） | 待开始 |
| 5 | Docker 部署、测试补全、文档、GitHub 开源 | 待开始 |

**阶段严格串行**：不提前实现未进入当前阶段的模块。

## 10. 对话链路（阶段 1）

```
前端 ChatView.vue
   │  fetch + ReadableStream（POST，非 EventSource）
   ▼
ChatSessionController  ──POST /api/v1/chat/sessions/{id}/stream──▶  返回 SseEmitter
   │
   ▼
ChatCompletionService
   │  ① 先校验（会话存在 / 模型已配置 / 入参合法）——失败则返回普通 JSON 错误
   │  ② ChatClient.prompt().user(...).advisors(conversationId).stream().content()
   ▼
MessageChatMemoryAdvisor ── 读：ChatMemory.get() 取最近 N 条历史注入 prompt
   │                      └─ 写：用户消息（调用前）、助手消息（完成后）
   ▼
JpaChatMemory ──▶ chat_message 表（**追加式，永不裁剪**）
```

两个容易踩的点，都已固化在实现里：

1. **上下文窗口 ≠ 历史裁剪**。`MessageWindowChatMemory` 会把裁剪后的窗口整体写回仓库，
   等于用窗口覆盖历史。因此本项目自行实现 `ChatMemory`：`add` 只追加，`get` 才做窗口。
2. **失败轮次只落用户消息**。用户消息在模型调用前写入、助手消息在完成后写入，
   所以失败时不会产生「半截助手回复」；前端收到 `error` 后应重新拉取消息，而不是本地伪造。
