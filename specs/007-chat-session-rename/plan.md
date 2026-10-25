# Implementation Plan: 阶段 7 — 会话重命名

**Branch**: `007-chat-session-rename` | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## Summary

给已有会话增加改名能力：后端一个 `PATCH` 端点写入 `title`，前端在会话列表项上提供改名入口。
本阶段**不新增数据库列、不新增迁移、不新增运行时依赖**，且刻意不改 `updatedAt`（改名不是对话活动）。
顺带把 `chat` 模块里内联的标题校验收敛到阶段 6 之后抽出的 `common/text/Texts`，
消除「创建时按原始长度校验、改名时按归一后长度校验」这种同一字段两种口径的隐患。

## Technical Context

- **语言/框架**：Java 21，Spring Boot 4.1.1，Spring Web MVC，Spring Data JPA
- **存储**：PostgreSQL 17（表结构不变）
- **前端**：Vue 3 + TypeScript + Pinia + Element Plus
- **测试**：JUnit 5 + MockMvc + Testcontainers（后端集成）；`node --test`（前端 store 逻辑）
- **新增依赖**：无
- **规模**：后端 2 个新文件（1 DTO + 1 端点方法）+ 1 个 Service 方法 + 校验收敛；前端 1 个 API 函数 + 1 个 store action + 视图改动

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先，拒绝过度设计 | ✅ 通过 | 零新依赖、零迁移、零新表。**不**引入「标题是否被用户锁定」状态列（代价见 research R-04）、**不**为此建领域服务、**不**加重命名历史表 |
| II. 边界即安全 | ✅ 通过 | 改名不构成数据破坏风险，故不加确认环节（与删除会话区别对待，理由见 spec Assumptions）。模型侧不暴露任何改名工具，改名只能由人在界面上发起 |
| III. 分层与写路径收敛 | ✅ 通过 | 写操作在 `ChatSessionService.rename`，Controller 只解析与包装；对外返回 DTO，不暴露 Entity；构造器注入。校验统一在 Service 层（理由见 research R-02） |
| IV. 数据变更可追溯 | ✅ 通过 | **本阶段无数据库结构变更**，不新增迁移；`ddl-auto=validate` 不变 |
| V. 可验证的交付 | ✅ 通过 | SC-001~SC-003 由集成测试断言数据库实际行；SC-002 用「三次改名后仍按 createdAt 排序」的构造数据验证；SC-004 前端以 type-check + store 测试 + 生产构建验证，真机点击路径写进 quickstart |

**结论**：无违例。Complexity Tracking 只登记一条「为何不做标题锁定状态」的说明性条目。

## Project Structure

### 改动清单（Documentation → Source Code）

```text
backend/src/main/java/com/personalos/
├── chat/
│   ├── controller/ChatSessionController.java    # 新增 PATCH /sessions/{conversationId}
│   ├── dto/RenameSessionRequest.java            # 新增：单字段 title
│   ├── dto/CreateSessionRequest.java            # 改：去掉 @Size（口径统一到 Texts）
│   └── service/ChatSessionService.java          # 新增 rename()；create() 改用 Texts
├── common/text/Texts.java                       # 复用（不修改）

backend/src/test/java/com/personalos/
└── chat/ChatSessionRenameIT.java                # 新增：改名端点集成测试

frontend/src/
├── api/chat.ts                                  # 新增 renameSession()
├── stores/chat.ts                               # 新增 renameSession()（本地替换，不重拉列表）
└── views/ChatView.vue                           # 会话项增加「重命名」入口

frontend/test/
└── chat-store-rename.test.mjs                   # 新增：本地替换、失败不动列表
```

### 不动的部分

- 数据库：无迁移、无列变更
- `ChatSessionEntity`：无字段变更（`title` 的 setter 已存在）
- 自动标题逻辑 `initializeTitleFromFirstMessage`：保持原样（条件不变，见 spec Assumptions）

## Phase 0 / Phase 1 结论

- **Phase 0（research）**：见 [research.md](./research.md)，6 条决策，其中 R-02（校验层选择）与 R-04（不加状态列）直接决定实现形态。
- **Phase 1（design）**：数据面无变更（[data-model.md](./data-model.md)）；接口契约见
  [contracts/session-rename-api.md](./contracts/session-rename-api.md)；验收步骤见 [quickstart.md](./quickstart.md)。

## Complexity Tracking

| 违例/取舍 | 为什么需要 | 更简单的替代方案为何被否决 |
|---|---|---|
| 不记录「标题已被用户改名」 | 需要一个状态列才能精确区分「用户改过名」与「系统生成名」 | 增加列 + 迁移 + 分支只为覆盖「空会话且恰好取名默认文案」这一条极窄路径，收益不足；代价已在 spec Assumptions 显式登记 |
| Service 层承担长度校验，DTO 不写 `@Size` | `@Size` 校验**原始**长度，业务要求校验**去空白后**长度，两者会在「带大量首尾空白」时给出相反结论 | 两处并存 → 出现「看起来合法却被拒」；只留 `@Size` → 无法 strip 后按真实长度判定 |
