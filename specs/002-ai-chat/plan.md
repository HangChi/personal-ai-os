# Implementation Plan: 阶段 1 — AI 对话与聊天历史

**Branch**: `002-ai-chat` | **Date**: 2026-10-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-ai-chat/spec.md`

## Summary

在既有骨架上接入 Spring AI：引入 OpenAI 兼容 + Ollama 两套 starter，用 `spring.ai.model.chat` 开关切换提供商；自建 `chat_session` / `chat_message` 两张表并用 Flyway 管理；**自行实现 Spring AI 的 `ChatMemory`**（而非用 `MessageWindowChatMemory`），以避免窗口裁剪导致库中历史被删除；通过 `MessageChatMemoryAdvisor` 自动装载上下文；流式输出用 `ChatClient.stream().content()` 返回的 `Flux<String>` 桥接到 Spring MVC 的 `SseEmitter`。测试用桩 `ChatModel`（`spring.ai.model.chat=none` + 自定义 bean），做到无网络、无密钥、可重复。

## Technical Context

**Language/Version**: Java 21；TypeScript 5.9.3

**Primary Dependencies**:
- `spring-ai-starter-model-openai`、`spring-ai-starter-model-ollama`（均取 BOM `2.0.1`）
- Spring Web MVC（`SseEmitter`）、Spring Data JPA、Flyway、Validation
- 前端：Vue 3.5 + Pinia + Element Plus（沿用阶段 0）

**Storage**: PostgreSQL 17 + pgvector；新增表 `chat_session`、`chat_message`

**Testing**: JUnit 5 + Mockito（单元）+ `@SpringBootTest` + Testcontainers（集成，含桩 ChatModel）

**Target Platform**: 本地单用户自托管

**Project Type**: Web application（Monorepo：backend + frontend）

**Performance Goals**: 首个流式片段 < 1s（桩模型下）；会话列表查询 < 200ms（本地，数据量 < 1 万条）

**Constraints**: 无密钥可启动；历史不得被上下文窗口裁剪；不得向前端泄漏 JPA Entity；结构变更走 Flyway

**Scale/Scope**: 单用户；阶段 1 新增约 30 个文件

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先，拒绝过度设计 | ✅ 通过 | 不引入消息队列/事件总线；会话与消息两张表；流式用 MVC 的 `SseEmitter` 而非改用 WebFlux 全栈 |
| II. 边界即安全 | ✅ 通过 | 本阶段**不引入任何工具调用**，模型无法产生副作用；密钥只经环境变量；不加任何自定义 SQL 拼接 |
| III. 分层与写路径收敛 | ✅ 通过 | 新增 `chat` 模块内 controller/service/repository/entity/dto；写操作全部经 `ChatSessionService` / `ChatMessageService`；对外只暴露 DTO |
| IV. 数据变更可追溯 | ✅ 通过 | `V2__create_chat_tables.sql` 单一迁移；`ddl-auto=validate` |
| V. 可验证的交付 | ✅ 通过 | 桩模型集成测试 + 真实 SSE curl 冒烟；交付说明区分「已执行/未执行」 |

**结论**：无违例。

## Project Structure

### Documentation (this feature)

```text
specs/002-ai-chat/
├── spec.md            ├── plan.md           ├── research.md
├── data-model.md      ├── quickstart.md     ├── tasks.md
├── contracts/chat-api.md
└── checklists/requirements.md
```

### Source Code (repository root)

```text
backend/src/main/java/com/personalos/
├── common/
│   ├── ai/AiProperties.java                    # app.ai.* 类型化配置（system prompt、上下文上限）
│   ├── ai/AiChatClientProvider.java            # ChatClient 装配（可空：模型未启用时为 absent）
│   └── api/ErrorCode.java                      # 新增 AI_NOT_CONFIGURED / AI_UPSTREAM_ERROR
└── chat/
    ├── controller/ChatSessionController.java   # 会话 CRUD + 消息查询 + SSE
    ├── service/ChatSessionService.java         # 会话写路径（创建/删除/标题）
    ├── service/ChatMessageService.java         # 消息查询与持久化
    ├── service/ChatCompletionService.java      # 流式对话编排（Flux → SSE）
    ├── repository/ChatSessionRepository.java
    ├── repository/ChatMessageRepository.java
    ├── entity/ChatSessionEntity.java
    ├── entity/ChatMessageEntity.java
    ├── memory/JpaChatMemory.java               # 自定义 ChatMemory（追加式，不裁剪）
    ├── dto/ChatSessionDto.java
    ├── dto/ChatMessageDto.java
    ├── dto/CreateSessionRequest.java
    ├── dto/StreamChatRequest.java
    └── dto/package-info.java

backend/src/main/resources/db/migration/V2__create_chat_tables.sql
backend/src/test/java/com/personalos/chat/…      # 单元 + 集成（桩 ChatModel）

frontend/src/
├── api/chat.ts              # 会话/消息/流式（fetch + SSE 解析）
├── stores/chat.ts           # 会话列表与当前消息状态
└── views/ChatView.vue       # 会话侧栏 + 消息区 + 输入框
```

**Structure Decision**：沿用阶段 0 的「单 Maven 模块 + 按包分模块」。`chat` 模块自持 controller/service/repository/entity/dto；`ChatClient` 的装配放在 `common/ai`（属横切基础设施，供后续 `agent`/`knowledge` 复用），但**业务编排**留在 `chat`。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| 自行实现 `ChatMemory`（而非直接用 Spring AI 的 `MessageWindowChatMemory` + `ChatMemoryRepository`） | `MessageWindowChatMemory` 会按窗口裁剪后**整体覆盖**仓库内容，导致库中历史被静默删除 | 直接用 `MessageWindowChatMemory`：窗口滑出的旧消息会从数据库消失，与 FR-007「历史不得被裁剪」直接冲突 |
| 引入两套模型 starter | 用户明确要求可在本地/云端之间切换 | 只引一套：无法满足 FR-010；切换需改代码与依赖 |
| 桩 `ChatModel` 用于集成测试 | 需要无密钥、无网络的确定性测试 | 直接连真实模型：CI 不可复现、有成本、失败原因不可区分 |
