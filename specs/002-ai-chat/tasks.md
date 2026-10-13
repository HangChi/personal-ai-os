---

description: "Task list for 阶段 1 — AI 对话与聊天历史"
---

# Tasks: 阶段 1 — AI 对话与聊天历史

**Input**: Design documents from `/specs/002-ai-chat/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/chat-api.md

**Tests**: 本特性**要求**测试（spec SC-008），测试任务为必做。

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup（AI 依赖与配置）

- [x] T001 在 `backend/pom.xml` 增加 `spring-ai-starter-model-openai`、`spring-ai-starter-model-ollama`（版本由 `spring-ai-bom:2.0.1` 管理）
- [x] T002 [P] 在 `application.yml` 增加 `spring.ai.model.*` 开关（chat 可切换、其余显式 none）与 `spring.ai.ollama.*` / `spring.ai.openai.*`（**扁平属性**，非 `chat.options.*`）
- [x] T003 [P] 在 `.env.example` 增加 `AI_CHAT_PROVIDER` / `OLLAMA_*` / `OPENAI_*` / `AI_MEMORY_MAX_MESSAGES`
- [x] T004 编译冒烟 + 启动确认「无密钥可启动」（FR-011）：实测启动成功、日志 `AI 聊天能力已启用：model=OpenAiChatModel`

## Phase 2: Foundational（数据库与公共设施）

- [x] T005 新增 `V2__create_chat_tables.sql`（`chat_session` / `chat_message` + 唯一约束 + 级联外键 + CHECK）
- [x] T006 [P] `common/api/ErrorCode` 增加 `AI_UPSTREAM_ERROR(50200)` / `AI_NOT_CONFIGURED(50300)`
- [x] T007 [P] 新增 `common/ai/AiProperties.java`（`app.ai.memory.max-messages`、`app.ai.system-prompt`，含默认值兜底）
- [x] T008 新增 `common/ai/AiChatClientProvider.java`：`ObjectProvider<ChatModel>` 装配 `ChatClient`，取不到时降级为「未配置」而非启动失败
- [x] T009 [P] 新增 `chat/entity/*`（`ChatSessionEntity` / `ChatMessageEntity` / `MessageRole`）
- [x] T010 [P] 新增 `chat/repository/*`（含一次分组统计避免 N+1、倒序分页取最近 N 条）
- [x] T011 新增 `chat/memory/JpaChatMemory.java`：实现 `ChatMemory`（追加式 add / 窗口式 get / 级联 clear）

**Checkpoint**：`./mvnw -B compile` 通过（36 个源文件）✔

---

## Phase 3: US1 流式对话 (P1) 🎯 MVP

- [x] T012 [US1] 新增 `chat/service/ChatCompletionService.java`：`Flux<String>` → `SseEmitter`，输出 `start`/`delta`/`done`/`error`
- [x] T013 [US1] 新增 `chat/dto/StreamChatRequest.java`（`@NotBlank` + 4000 字上限）
- [x] T014 [US1] 新增 `chat/dto/ChatStreamEvents.java`（类型化事件负载，替代裸 Map）
- [x] T015 [US1] `ChatSessionController` 暴露 `POST /sessions/{id}/stream`
- [x] T016 [US1] 上游异常归类为 `50200`、未配置归类为 `50300`；**错误响应不含堆栈/类名**
- [x] T017 [US1] 参数非法/会话不存在时**在建立流之前**返回普通 JSON 错误（不产生半截 SSE）
- [x] T018 [US1] 集成测试：断言 `start`→`delta`×2→`done` 序列、`done.content` == 拼接、库中恰好 2 条消息（SC-002）

---

## Phase 4: US2 多轮上下文 (P1)

- [x] T019 [US2] 通过 `MessageChatMemoryAdvisor` + `ChatMemory.CONVERSATION_ID` 装载上下文
- [x] T020 [US2] `JpaChatMemory.get()` 按 `max-messages` 取最近 N 条并反转成正序
- [x] T021 [US2] 集成测试：第二轮上下文包含第一轮内容与第一轮助手回复（FR-009）
- [x] T022 [US2] 集成测试：5 轮后库中 10 条历史一条未少；送入模型的历史 ≤ max-messages（SC-004 / FR-007）

---

## Phase 5: US3 会话与历史管理 (P2)

- [x] T023 [P] [US3] `chat/service/ChatSessionService.java`：创建 / 列表（`updatedAt` 倒序）/ 删除 / 标题派生
- [x] T024 [P] [US3] `chat/service/ChatMessageService.java`：按序查询
- [x] T025 [US3] `chat/dto/*`：`CreateSessionRequest` / `ChatSessionDto` / `ChatMessageDto`
- [x] T026 [US3] `ChatSessionController` 暴露 `POST /sessions`、`GET /sessions`、`GET /sessions/{id}/messages`、`DELETE /sessions/{id}`
- [x] T027 [US3] 会话不存在 → 404/40004（FR-016）；空列表返回 `[]` 而非 null
- [x] T028 [US3] 首条用户消息自动生成标题（FR-017 / FR-019）
- [x] T029 [US3] 集成/单元测试：建会话 → 列表可见 → 消息正序 → 删除后级联消失

---

## Phase 6: US4 会话隔离 (P1)

- [x] T030 [US4] 集成测试：A 会话写入暗号，B 会话提问，断言送给 B 的指令**不含**暗号（SC-003，泄漏 0 条）
- [x] T031 [US4] 集成测试：删除 A 后 B 的消息与计数不受影响

---

## Phase 7: US5 模型配置与错误处理 (P2)

- [x] T032 [P] [US5] 单元测试：`JpaChatMemory` 追加语义、窗口取值、角色映射、`clear`
- [x] T033 [P] [US5] 单元测试：`ChatSessionService` 标题派生、校验、404
- [x] T034 [US5] 收紧 `spring.ai.retry`（默认重试会让错误迟迟不出现，与 SC-006 冲突）并实测：**0 秒内**推送 error
- [x] T035 [US5] 实机验证：模型不可达 → 库中只多一条用户消息，**无半截助手消息**

---

## Phase 8: 前端接入

- [x] T036 [P] `frontend/src/api/chat.ts`：会话 CRUD + `fetch` 手写 SSE 帧解析（含非法帧容错）
- [x] T037 [P] `frontend/src/stores/chat.ts`：会话列表、当前消息、流式增量、停止生成
- [x] T038 `frontend/src/views/ChatView.vue`：会话侧栏 + 消息区 + 输入框，含加载态/错误态/流式光标
- [x] T039 路由挂载 `/chat`；顶部导航（总览 / 对话）；`npm run type-check` 退出码 0 + `npm run build` 成功

---

## Phase 9: 验收与交付

- [x] T040 实际执行 `./mvnw -B verify`：**Tests run: 37, Failures: 0, Errors: 0**
- [x] T041 实际执行 SSE 冒烟（真机 + OpenAI 兼容 mock）：`start`→`delta`×4→`done`，`done.content` == 拼接
- [x] T042 新增 `scripts/dev/mock-openai-server.py`（开发用，仅标准库）并写入 `docs/development.md`
- [x] T043 回填 `quickstart.md` 验证记录表；更新 README / architecture 的阶段状态
- [x] T044 更新 `.workbuddy/memory/` 并提交

---

## 实现偏差记录

| 编号 | 偏差 | 原因 | 已同步文档 |
|---|---|---|---|
| P1-D1 | 实现 `ChatMemory` 而非计划中的 `ChatMemoryRepository` | `ChatMemoryRepository` 需配合 `MessageWindowChatMemory`，而后者会用窗口**覆盖**历史 → 旧消息被静默删除，与 FR-007 冲突。直接实现 `ChatMemory` 把「持久化历史」与「模型上下文窗口」彻底解耦 | research R-02、data-model |
| P2-D2 | 模型配置属性改为**扁平**形式（`spring.ai.ollama.chat.model`） | 实测 `CONFIG_PREFIX="spring.ai.ollama.chat"` 且属性类只有 `getModel()`，没有嵌套 `options`。写 `chat.options.model` **不报错但静默不生效** | research R-01、application.yml 注释 |
| P3-D3 | 新增 `spring.ai.retry` 收紧配置 | 默认重试 + 指数退避会让失败迟迟不出现，与 SC-006（10 秒内推 error）冲突。实测收紧后 0 秒内推送 | research R-13 |
| P4-D4 | `MessageRole` 只含 `USER/ASSISTANT/SYSTEM` | 阶段 1 不产生 `TOOL`；数据库 CHECK 已为 `TOOL` 预留取值，阶段 2 无需新增迁移 | data-model |
| P5-D5 | 新增 `scripts/dev/mock-openai-server.py` | 让「前端流式渲染 + SSE 契约」在无模型、无密钥时可复现验证。仅标准库、非产品代码、不参与构建 | docs/development 第五节 |
| P6-D6 | 会话标识校验在 Service 层**显式实现**，未用 `@Pattern` 注解 | 避免 `HandlerMethodValidationException` 与 `ConstraintViolationException` 在不同 Spring 版本下的行为差异；显式校验可单元测试且 100% 确定 | research R-08 |
| P7-D7 | SSE 端点**不声明** `produces` | 若写死 `text/event-stream`，前置校验失败时的 JSON 错误也会被标成事件流类型，客户端难以处理。Spring 会依据 `SseEmitter` 返回类型自动设置 | controller 注释 |
| P8-D8 | 上下文窗口断言改为「只约束历史部分」 | 实测 `MessageChatMemoryAdvisor` 先用 `get()` 取窗口历史，再追加本轮用户消息与 system 提示，总条数 = 历史(≤N) + 1 + 1。原断言按总条数写会误报 | research R-03 实测小节 |
| P9-D9 | 集成测试中的失败构造改为「运行时异常包裹 IOException」 | `ChatModel.call` 不能抛受检异常；而真实场景 Spring AI 正是把上游错误包在运行时异常里，这样才真实地验证了 `classify()` 的 cause 链遍历 | 测试注释 |

## 推荐提交

```
feat(chat): 阶段 1 AI 对话与聊天历史（T001-T044）

- Spring AI 2.0.1：OpenAI 兼容 + Ollama 双 starter，spring.ai.model.chat 切换提供商
- 自建 chat_session / chat_message（Flyway V2）；自定义 ChatMemory 追加式持久化，
  历史永不被上下文窗口裁剪；MessageChatMemoryAdvisor 装载多轮上下文
- SSE 流式对话（Flux<String> → SseEmitter），事件契约 start/delta/done/error
- 会话 CRUD + 消息查询 API，conversationId 严格隔离（实测跨会话泄漏 0）
- 无密钥可启动；未配置模型 → 50300；上游失败 → 50200 且 0 秒内推送
- 前端 /chat：会话侧栏 + 逐字流式渲染 + 停止生成
- 37 个测试全绿（含 12 个无网络无密钥的聊天集成测试）
```
