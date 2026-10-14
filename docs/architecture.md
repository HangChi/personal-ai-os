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
| `knowledge` | 文档摄入（解析、切分、嵌入）与基于 pgvector 的 RAG 检索问答 | 阶段 3 ✅ |
| `calendar` | 日程（重复规则）与提醒（物化 + 轮询送达） | 阶段 4 ✅ |
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
| 2 | 任务 CRUD 与 Tool Calling（含两阶段人工确认与两层幂等） | ✅ 完成 |
| 3 | 个人知识库与 RAG（pgvector + 引用溯源） | ✅ 完成 |
| 4 | 日程与定时提醒（重复规则 + 提醒物化） | ✅ 完成 |
| 5 | Docker 部署、测试补全、文档、GitHub 开源 | 待开始 |

**阶段严格串行**：不提前实现未进入当前阶段的模块。

## 11. 任务 Agent 链路（阶段 2）

```
自然语言「把'写周报'删掉」
   ▼
ChatClient → 模型决定调用工具
   ▼
ToolCallingAdvisor（必须显式装配，见 research R-10）
   ▼
TaskTools（@Tool 适配器）→ ToolExecutor（幂等 + 审计 + 异常归一）
   ▼
TaskService（唯一的写路径）→ task 表
```

### 三条必须记住的约束

1. **模型只能「申请」高风险操作。** `deleteTask` / `scheduleTask` / `setTaskPriority`
   不执行变更，只创建一条待确认记录；执行它们的是面向人类的确认接口。
   工具集合中**不存在**任何确认类能力 —— 防线是结构性的，不依赖令牌保密（SC-003）。

2. **确认范围是有意划定的**：删除 / 批量 / 关键字段（截止时间、优先级）需确认；
   创建、查询、改标题描述、标记完成**不需要** —— 后者可逆且是最高频动作，
   每次都弹确认会与「用自然语言操作任务」的目标直接冲突（见 research R-07）。

3. **两层幂等，范围不同**：
   - 工具层按「会话 + 轮次 + 工具 + 参数摘要」去重 → 防模型在同一轮里重复调用；
     刻意**不**跨轮次去重，因为用户在同一分钟内真诚地建两个同名任务是合法的。
   - HTTP 层按客户端提供的 `Idempotency-Key` 去重 → 防网络重试。
   两者共用 `operation_record` 一张表，它同时就是工具执行审计日志。

## 12. 知识库 RAG 链路（阶段 3）

```
上传 .md/.txt
   ▼ 校验（类型/大小/UTF-8）
KnowledgeDocumentReader（实现 DocumentReader，内部用 TextReader）
   ▼ 附加来源元数据：document_id / document_title / filename
TokenTextSplitter 分块（并重新编号 chunk_index 为 0..n-1）
   ▼
EmbeddingModel → PgVectorStore.add()  →  vector_store 表（vector(1024)）
   ▲
   └── 与 knowledge_document 在同一事务 → 文档记录与向量要么一起成功、要么一起回滚

检索：SearchRequest(query, topK, similarityThreshold) → similaritySearch
问答：命中片段编号注入提示词 → 生成 → **服务端依据片段元数据**组装引用
```

### 三条必须记住的约束

1. **维度在三个地方各写了一份**（迁移的 `vector(1024)`、配置的 `embedding-dimensions`、
   模型的真实输出维度）。因此启动期校验「表 ↔ 配置」、写入期翻译「模型 ↔ 表」的错误。
   `EmbeddingModel.dimensions()` 不能用做启动校验 —— 它会真的发起一次嵌入调用，
   破坏「无模型也能启动」。

2. **向量表由 Flyway 拥有，不用框架自动建表**。列名/类型必须与 `PgVectorStore` 内部 SQL 完全一致
   （框架的 `schema-validation` 默认关闭，不会替我们发现写错）。
   索引 opclass 必须与距离类型成对：`COSINE_DISTANCE ↔ vector_cosine_ops`；
   二者不匹配**不报错**，只会让索引失效、查询退化为全表扫描。

3. **删除必须「先删向量、再删文档记录」**。反过来则一旦失败，
   残留向量将永远无法按 `document_id` 定位清理；当前顺序下最坏情况是可重试的空文档。

## 13. 日程与提醒链路（阶段 4）

```
创建日程（含重复规则）
   ▼
RecurrenceCalculator（纯函数：规则 + 区间 → 发生时刻；本地时区加减，跨夏令时不漂移）
   ▼ 按需展开，不落库
区间查询 ────────► 发生实例（重复已展开，前端无需理解规则）

@Scheduled(每 60s) ─► ReminderScanner.scan(now)
                       ⓪ 清理孤儿提醒（来源已不存在）
                       ① 日程：窗口内的每次发生
                       ② 任务：窗口内未完成的截止时间
                       ▼ ON CONFLICT DO NOTHING（靠唯一约束幂等）
                     reminder 表
                       ▼ 前端每 30s 轮询 remind_at <= now 且未读
                     「到点提醒」卡片
```

### 三条必须记住的约束

1. **幂等靠数据库唯一约束，不靠「先查再插」。**
   唯一键是 `(source_type, source_id, occurrence_at)`，插入用 `ON CONFLICT DO NOTHING`。
   这样「手动触发扫描 + 定时扫描」并发也安全，**系统时间回拨**也安全 ——
   判断依据是「这个时刻有没有提醒」，而不是「现在几点」。
   连续扫描 5 次与新实例扫描行数不变，都有测试断言。

2. **调度必须是 DB 状态驱动，不能是内存定时任务。**
   为每条提醒注册一个内存任务，重启即全部丢失 —— 用户会静默地收不到提醒，
   没有任何报错。状态在库里，重启后下一轮扫描自然补齐（真机重启验证过）。
   代价是提醒精度受扫描间隔限制（默认 60 秒）。

3. **时间必须可注入。**
   `scan(Instant now)` 显式收时间、`Clock` 做成 Bean、重复计算的时区做成 Bean。
   一个直接调 `Instant.now()` 的调度器，测试只能 `Thread.sleep` 等到点 ——
   结果是没人愿意跑它。目前全部时间相关测试**零 sleep**，毫秒级完成。

### 提醒的「删除」与「自愈」

- **删除日程**时先删它的提醒、再删日程（反过来失败会留下无法定位的孤儿提醒）。
- **删除任务**时不经过提醒模块（`task` 不知道提醒的存在，也不该知道），
  因此**扫描每次都会清理「来源已不存在」的提醒**。
  这是刻意的自愈设计：任何删除路径、甚至直接改数据库，都会在下一轮被纠正，
  而不是在每条删除路径上挂清理钩子（漏掉一条就是静默的幽灵提醒）。

### 使用确认机制的第二个模块

日程删除需要人工确认。阶段 4 之前为此做了一次**前置重构**：
把确认机制从 `agent/confirmation` 上移到 `common/confirmation`，
并引入 `ConfirmationActionHandler` SPI —— 通用服务只负责机制（令牌/有效期/单次性/原子占位），
「确认之后干什么」由各业务模块自己的处理器实现。

结果是：日程接入确认**没有改动通用机制的任何一行代码**，只新增了一个处理器。
`ConfirmationRegistry` 会在启动期校验「每个动作都有处理器且无重复注册」——
若等到用户点「确认」才发现没人处理，那一刻决定已经不可逆。

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
