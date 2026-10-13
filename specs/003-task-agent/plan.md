# Implementation Plan: 阶段 2 — 任务管理与 Agent 工具调用

**Branch**: `003-task-agent` | **Date**: 2026-10-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-task-agent/spec.md`

## Summary

新增 `task` 模块（任务领域 + REST）与 `agent` 模块（`@Tool` 适配器 + 两阶段确认 + 工具审计）。工具通过 `common/ai` 定义的 `ToolContributor` SPI 被 `ChatCompletionService` 收集并挂到 `ChatClient`，使对话具备操作任务的能力。幂等分两层：**工具层**按「轮次 + 工具 + 参数摘要」去重（阻断模型重复调用），**HTTP 层**按客户端提供的 `Idempotency-Key` 去重（阻断网络重试）。两者共用一张 `operation_record` 表：它既是工具执行审计日志，也是幂等判定的唯一依据。删除/批量/关键字段修改走**服务端两阶段确认** —— 模型只能创建待确认记录，确认途径只存在于面向人类的 REST 接口中。

## Technical Context

**Language/Version**: Java 21；TypeScript 5.9.3

**Primary Dependencies**:
- Spring AI 2.0.1：`@Tool` / `@ToolParam`（`org.springframework.ai.tool.annotation`）、`ChatClient...tools(Object...)`、`ToolContext`、`ToolCallingAdvisor`（已实现 `StreamAdvisor`，故流式路径支持工具调用）
- Spring Data JPA、Flyway、Validation（沿用）
- Jackson：**classpath 上同时存在 Jackson 3（`tools.jackson.core:jackson-databind:3.1.5`）与 Jackson 2（`com.fasterxml.jackson.core:jackson-databind:2.21.5`）**，本项目统一使用 Boot 4 自动配置的 Jackson 3 `ObjectMapper`，并封装在 `common/json/JsonCodec` 中，避免类型漂移

**Storage**: PostgreSQL 17 + pgvector；新增表 `task`、`task_confirmation`、`operation_record`

**Testing**: JUnit 5 + Mockito（单元）+ `@SpringBootTest` + Testcontainers + **脚本化桩 ChatModel（可发出 ToolCall）**（集成）

**Target Platform**: 本地单用户自托管

**Project Type**: Web application（Monorepo：backend + frontend）

**Performance Goals**: 任务列表查询 < 200ms（本地，<1 万条）；单次工具调用开销 < 50ms（不含模型）

**Constraints**: 无密钥可启动；需确认操作在确认前零写入；模型工具集合中不得存在确认类工具

**Scale/Scope**: 单用户；阶段 2 新增约 40 个文件

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先，拒绝过度设计 | ✅ 通过 | 「待确认」没有引入工作流引擎或状态机框架，就是一张表 + 两个接口；幂等用一个唯一键实现，不引入 Redis 或分布式锁 |
| II. 边界即安全 | ✅ 通过 | 模型只能调用 8 个显式工具，**工具集合中不含任何确认类工具**（SC-003）；破坏性操作必须先落待确认记录；令牌不下发模型 |
| III. 分层与写路径收敛 | ✅ 通过 | 新增 `task`（entity/repository/service/dto/controller）与 `agent`（tool/confirmation/audit）；工具是**适配器**，内部仍调用 `TaskService`，不直连数据库；对外只暴露 DTO |
| IV. 数据变更可追溯 | ✅ 通过 | `V3__create_task_and_agent_tables.sql` 单一迁移；`ddl-auto=validate` |
| V. 可验证的交付 | ✅ 通过 | SC-001/002/004/006 全部以「数据库受影响行数」为断言口径，而非接口返回值 —— 返回值可能撒谎，行数不会 |

**结论**：无违例。

## Project Structure

### Documentation (this feature)

```text
specs/003-task-agent/
├── spec.md  ├── plan.md  ├── research.md  ├── data-model.md
├── quickstart.md  ├── tasks.md
├── contracts/{task-api.md, agent-tools.md}
└── checklists/requirements.md
```

### Source Code (repository root)

```text
backend/src/main/java/com/personalos/
├── common/
│   ├── ai/ToolContributor.java                 # SPI：业务模块向对话贡献工具
│   ├── idempotency/
│   │   ├── IdempotencyService.java             # 两层幂等的统一实现
│   │   ├── OperationRecordEntity.java
│   │   ├── OperationRecordRepository.java
│   │   ├── IdempotencyOutcome.java             # 枚举
│   │   └── IdempotentResult.java               # 执行结果 + 是否命中缓存
│   ├── json/JsonCodec.java                     # 统一 Jackson 3 序列化入口
│   └── api/ErrorCode.java                      # 新增 40901 / 40001 / 40002
├── task/
│   ├── entity/{TaskEntity, TaskStatus, TaskPriority}.java
│   ├── repository/TaskRepository.java
│   ├── service/TaskService.java
│   ├── dto/{TaskDto, CreateTaskRequest, UpdateTaskRequest, TaskQuery, BulkIdsRequest}.java
│   └── controller/TaskController.java          # REST + Idempotency-Key
└── agent/
    ├── tool/TaskTools.java                     # 8 个 @Tool
    ├── tool/TaskToolContributor.java           # 实现 ToolContributor
    ├── tool/ToolExecutionResult.java           # 工具统一返回信封
    ├── confirmation/{TaskConfirmationEntity, ConfirmationStatus, ConfirmationAction,
    │                 TaskConfirmationRepository, ConfirmationService, ConfirmationController,
    │                 ConfirmationDto}.java
    └── audit/ToolInvocationRecorder.java       # 工具执行日志（复用 operation_record）

backend/src/main/resources/db/migration/V3__create_task_and_agent_tables.sql
backend/src/test/java/com/personalos/{task/…, agent/…}

frontend/src/
├── api/tasks.ts  api/confirmations.ts
├── stores/task.ts
└── views/TasksView.vue     # 任务页 + 对话中的确认卡片
```

**Structure Decision**：沿用「单 Maven 模块 + 按包分模块」。**工具适配器放 `agent`、领域逻辑放 `task`**，两者通过 `TaskService` 而非数据库交互；`ToolContributor` SPI 定义在 `common/ai`，使 `chat` 模块无需依赖具体业务模块即可收集工具（阶段 3 的知识库工具可同样接入）。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| 引入 `ToolContributor` SPI（一层间接） | `chat` 需要把工具挂到 `ChatClient`，但不该依赖 `agent`/`task` | 让 `chat` 直接依赖 `agent`：方向反了（业务模块反向依赖对话模块），且阶段 3 再加知识库工具时依赖会继续膨胀 |
| 幂等与工具审计共用一张表 | 两者都需要「键 → 结果」的持久化，且审计表天然记录了每次调用 | 拆成两张表：需要在同一事务里写两处，且「被去重的调用」的归属会含糊（到底是审计还是幂等记录？） |
| 关键字段修改走确认 | 用户明确要求；改动截止时间/优先级会实质影响用户的计划 | 不确认：违反宪法原则 II 对「可能破坏数据的 Agent 操作」的约束 |
