---

description: "Task list for 阶段 2 — 任务管理与 Agent 工具调用"
---

# Tasks: 阶段 2 — 任务管理与 Agent 工具调用

**Input**: Design documents from `/specs/003-task-agent/`

---

## Phase 1: Foundational（迁移与公共设施）

- [x] T001 新增 `V3__create_task_and_agent_tables.sql`（`task` / `task_confirmation` / `operation_record` + 索引 + CHECK）
- [x] T002 [P] `ErrorCode` 增加 `IDEMPOTENCY_KEY_CONFLICT(40001)` / `CONFIRMATION_NOT_ACTIVE(40002)`
- [x] T003 [P] 新增 `common/json/JsonCodec`（统一 Jackson 3；classpath 上同时存在 Jackson 2）
- [x] T004 [P] 新增 `common/ai/ToolContributor` SPI + `ToolContextKeys`
- [x] T005 新增 `common/idempotency/*`（实体/仓储/枚举/请求/结果/服务）

**Checkpoint**：`./mvnw -B compile` 通过（71 源文件）✔

---

## Phase 2: 任务领域与 REST

- [x] T006 [P] `task/entity/{TaskEntity, TaskStatus, TaskPriority}`
- [x] T007 [P] `task/repository/TaskRepository`（`JpaSpecificationExecutor`，类型安全条件拼装）
- [x] T008 `task/service/TaskService`（含 `completedAt` 不变量、`overdue` 计算、批量上限、显示排序）
- [x] T009 [P] `task/dto/*`（`TaskDto` / `CreateTaskRequest` / `UpdateTaskRequest` / `TaskQuery` / `DeletionAck`）
- [x] T010 `task/controller/TaskController`（REST + `Idempotency-Key`）
- [x] T011 集成测试 18 例（CRUD / 筛选 / 幂等三态 / 批量上限 / 排序 / 不变量）

---

## Phase 3: 两层幂等

- [x] T012 `IdempotencyService`：占位插入 → 执行 → 回填；同键不同摘要 → 40001
- [x] T013 工具层键 = `hash(会话 + 轮次 + 工具 + 参数摘要)`；HTTP 层 = `Idempotency-Key`
- [x] T014 **刻意不加 `@Transactional`**（占位冲突会污染事务，见类注释）
- [x] T015 集成测试：同键重放只写 1 行、data 一致；同键不同内容 → 409；键格式非法 → 400

---

## Phase 4: 两阶段人工确认

- [x] T016 [P] `agent/confirmation/{ConfirmationAction, ConfirmationStatus, TaskConfirmationEntity}`
- [x] T017 [P] `TaskConfirmationRepository`（含 `compareAndSetStatus` 原子状态迁移）
- [x] T018 `ConfirmationService`（创建/确认/取消；令牌、TTL、单次性；服务端生成 summary）
- [x] T019 `ConfirmationController`（**只面向人类**）
- [x] T020 集成测试 9 例（令牌不匹配 / 单次性 / 过期 / 取消后不可确认 / 目标已不存在 / 并发）

---

## Phase 5: Agent 工具

- [x] T021 [P] `ToolExecutionResult`（OK / REQUIRES_CONFIRMATION / DUPLICATE / REJECTED / ERROR）
- [x] T022 `ToolExecutor`（工具执行的公共外壳：幂等 + 审计 + 异常归一 + 稳定参数摘要）
- [x] T023 `TaskTools`：8 个 `@Tool` + `@ToolParam` 描述 + 入参校验
- [x] T024 高风险工具返回 `REQUIRES_CONFIRMATION`，**不返回令牌**
- [x] T025 `TaskToolContributor` 实现 `ToolContributor`
- [x] T026 `AiChatClientProvider` 收集工具并**显式装配 `ToolCallingAdvisor`**（关键，见 R-10）
- [x] T027 `ChatCompletionService` 通过 `toolContext` 传入 `conversationId` / `turnId`
- [x] T028 集成测试 12 例（真实改库 / 确认前零写入 / 同轮去重 / 跨轮不去重 / 非法入参零写入 / 工具集合无确认能力 / 审计）

---

## Phase 6: 审计接口

- [x] T029 `ToolInvocationController`：`GET /api/v1/agent/tool-invocations`

---

## Phase 7: 前端

- [x] T030 [P] `api/tasks.ts`、`api/confirmations.ts`、`types/api.ts` 扩展
- [x] T031 [P] `stores/task.ts`
- [x] T032 `views/TasksView.vue`（新建 / 筛选 / 完成 / 删除）
- [x] T033 对话页待确认卡片（确认 / 取消 + 删除类二次确认）
- [x] T034 路由 `/tasks` + 导航；`type-check` 退出码 0、`build` 成功

---

## Phase 8: 验收与交付

- [x] T035 `./mvnw -B verify` → **Tests run: 76, Failures: 0, Errors: 0**
- [x] T036 真机 Agent 端到端（mock 支持 tool_calls）：自然语言建任务 → 库里真有；删除 → 待确认 → 确认后 404
- [x] T037 回填 `quickstart.md`；更新 README / architecture
- [x] T038 更新 `.workbuddy/memory/` 并提交

---

## 实现偏差记录

| 编号 | 偏差 | 原因 | 已同步文档 |
|---|---|---|---|
| S1-D1 | 新增 `ToolExecutor`（任务清单未列） | 幂等 + 审计 + 异常归一对所有工具都一样，写进每个工具方法会淹没业务逻辑 | 本文件 |
| S2-D2 | **显式装配 `ToolCallingAdvisor`**，不再复用自动配置的 Builder | 实测：静态工厂不会自动装配它，缺失时工具**静默不执行** | research R-10 |
| S3-D3 | `UpdateTaskRequest` 增加 `clearDueAt` | record 的 `null` 无法同时表达「字段未提供」与「显式置空」；否则只改标题会误清截止时间 | contracts/task-api.md |
| S4-D4 | 任务列表排序在**内存**中完成，未用 JPQL `CASE` | 避免「未完成优先」的 CASE 与 NULL 排序在方言上的差异；个人任务规模下成本可忽略 | TaskService 类注释 |
| S5-D5 | 会话标识校验仍在 Service 层显式实现 | 与阶段 1 保持一致，避免框架校验异常类型差异 | research R-08（阶段 1） |
| S6-D6 | `deleteAll` 返回**实际**影响行数；确认时 0 行视为「目标已不存在」 | 区分「执行成功但没影响任何行」与「已无可执行目标」——这是排查时最重要的信息差异 | ConfirmationService |
| S7-D7 | `ConfirmationService.confirm` 使用 `@Transactional(noRollbackFor = BusinessException.class)` | 过期分支需要先标记 EXPIRED 再抛错；若异常回滚事务，标记会丢失 | 类注释 |
| S8-D8 | 工具层幂等**刻意不跨轮次** | 用户在同一分钟内真诚地建两个同名任务是合法的；按时间窗去重会造成误判 | research R-04 |
| S9-D9 | 读工具只审计、**不做幂等**（用随机键） | 否则模型在同一轮里「创建 → 再查一次」会拿到过期结果 | ToolExecutor 注释 |
| S10-D10 | mock 服务器扩展支持 `tool_calls` | 让 Agent 链路在无真实模型时也能端到端可复现 | docs/development 第五节 |
| S11-D11 | 新增测试支撑类 `HttpTestClient` / `ScriptedToolCallingChatModel` | 避免用会随框架版本变化的测试切片 API；SSE 逐帧读取本来就是流式响应的原生形态 | 本文件 |
| S12-D12 | 桩模型重写 `getOptions()` 而**不是** `getDefaultOptions()` | 实测二者是反向委托（`getDefaultOptions` → `getOptions`），写错方向不会报错，只会导致工具静默不执行 | research R-10 |

### 实现过程中发现并修复的自身缺陷

- **批量删除 0 行时未走「目标已不存在」分支**：原本返回 `executed=true, affectedCount=0`，
  与 spec 的 Edge Case 要求不符（用户会以为「执行成功但什么也没发生」）。
  由集成测试发现后修正为抛「目标已不存在」并置记录为已取消。

## 推荐提交

```
feat(agent): 阶段 2 任务管理与 Agent 工具调用（T001-T038）

- task 模块：任务 CRUD + 完成/取消完成 + REST（Flyway V3）
- agent 模块：8 个 @Tool 经 ToolContributor SPI 挂入对话，自然语言即可操作任务
- 服务端两阶段确认：删除/批量/关键字段需人工确认，令牌只下发人类；
  工具集合中不存在确认类能力，模型在能力层面无法完成删除
- 两层幂等：工具层按「轮次+工具+参数」去重、HTTP 层按 Idempotency-Key 去重，
  共用 operation_record 表（同时充当工具执行审计日志）
- 显式装配 ToolCallingAdvisor —— 静态工厂不会自动装配它，缺失时工具静默不执行
- 测试：76 例全绿，断言口径为数据库受影响行数（接口返回值会撒谎，行数不会）
```
