# Implementation Plan: 阶段 8 — 长期记忆

**Branch**: `008-long-term-memory` | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## Summary

把 `memory` 包从占位变成实现：一张 `memory` 表 + 一个显式保存的 Agent 工具 + 记忆管理页面，
并在**每次会话对话**的系统提示里追加记忆块。

关键设计取舍：注入走 `common/ai` 的 SPI（与既有 `ToolContributor` 同构），
让 `chat` 仍然只依赖 `common` —— 否则会与 `ToolContributor` 注释里写明的架构理由正面冲突。

## Technical Context

- **语言/框架**：Java 21 / Spring Boot 4.1.1 / Spring Data JPA / Flyway
- **存储**：PostgreSQL 17，新表 `memory`（迁移 `V9`）
- **前端**：Vue 3 + TypeScript + Pinia + Element Plus，新增 `/memory` 页面
- **新增依赖**：**无**
- **规模**：后端 1 迁移 + 7 个类（entity/repository/service/tool/dto/controller/prompt-contributor）+ 桥接改动 2 处；前端 1 页 + 1 api + 1 store

## Constitution Check

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先，拒绝过度设计 | ✅ 通过 | 零新依赖；**不**做向量检索、分类标签、冲突消解、自动归纳；注入预算用常量而非配置中心；SPI 只因为已有同构先例才加 |
| II. 边界即安全 | ✅ 通过 | 保存记忆只经显式 Agent 工具；**删除/修改不暴露给模型**，只能在界面上由人发起；不向前端暴露 Entity |
| III. 分层与写路径收敛 | ✅ 通过 | 新 `com.personalos.memory` 包含 controller/service/repository/entity/dto/tool；写操作在 Service；构造器注入；`chat` 不依赖 `memory`（靠 `common/ai` 的 SPI） |
| IV. 数据变更可追溯 | ✅ 通过 | 唯一迁移 `V9__create_memory.sql` 只建一张表；`ddl-auto=validate`；V1–V8 零触碰 |
| V. 可验证的交付 | ✅ 通过 | SC-001/002/004/006 用桩模型断言**送给模型的 SystemMessage**（不是看模型回答）；SC-003 断言表行数；增删改查走集成测试 |

**结论**：无违例。Complexity Tracking 登记 1 条（为何新增 SPI 而不是让 chat 直接依赖 memory）。

## Project Structure

```text
backend/src/main/resources/db/migration/
└── V9__create_memory.sql                        # 新增：memory 表

backend/src/main/java/com/personalos/
├── common/ai/PromptContributor.java             # 新增 SPI：向系统提示追加文本
├── chat/service/ChatCompletionService.java      # 改：请求级 system = 基础提示 + 各贡献者
├── memory/
│   ├── entity/MemoryEntity.java                 # 新增
│   ├── repository/MemoryRepository.java         # 新增
│   ├── service/MemoryService.java               # 新增：校验/去重/渲染
│   ├── MemoryPromptContributor.java             # 新增：实现 SPI，输出记忆块
│   ├── tool/MemoryTools.java                    # 新增：@Tool saveMemory
│   ├── tool/MemoryToolContributor.java          # 新增：实现 ToolContributor
│   ├── controller/MemoryController.java         # 新增：list/create/update/delete
│   ├── dto/…（4 个 record）
│   └── package-info.java                        # 改：去掉「阶段 0 仅占位」

backend/src/test/java/com/personalos/
├── memory/MemoryServiceTest.java                # 新增：校验/去重/渲染预算
└── memory/MemoryIntegrationTest.java            # 新增：REST + 工具 + 注入

frontend/src/
├── api/memory.ts / stores/memory.ts / views/MemoryView.vue
├── router/index.ts / App.vue                    # 改：新增导航与路由
frontend/test/memory-store.test.mjs              # 新增
```

## Phase 0 / Phase 1 结论

- **Phase 0**：见 [research.md](./research.md)，9 条决策；其中 R-04（SPI）与 R-05（预算不截断）直接决定实现形态。
- **Phase 1**：结构见 [data-model.md](./data-model.md)；接口与注入格式见 [contracts/memory-api.md](./contracts/memory-api.md)。

## Complexity Tracking

| 取舍 | 为什么需要 | 更简单的替代方案为何被否决 |
|---|---|---|
| 新增 `PromptContributor` SPI | `chat` 需要在每次请求里拼上记忆，但它**不应**依赖 `memory` | 直接让 `ChatCompletionService` 注入 `MemoryService`：与 `ToolContributor` 注释里写明的「业务模块反向依赖、每加一类都要改 chat」问题完全一样，会让既有架构自相矛盾 |
| 注入预算写成常量而非配置项 | 预算值需要可解释、可测试 | 做成 `@ConfigurationProperties` 会新增一个配置面，而实际只有单用户单机在使用；将来要调再提取，成本相同 |
