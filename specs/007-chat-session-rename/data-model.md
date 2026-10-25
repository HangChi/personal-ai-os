# Data Model: 阶段 7 — 会话重命名

**Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## 结论：本阶段**零数据库结构变更**

- 不新增表
- 不新增列
- 不新增索引
- **不新增 Flyway 迁移**（迁移链停在 `V8`）

宪法原则 IV 要求结构变更必须走迁移；本阶段没有结构变更，因此正确的动作是**什么都不做**，
而不是"顺手加一列以后可能有用"（宪法原则 I）。

## 复用的既有实体

`com.personalos.chat.entity.ChatSessionEntity`（表 `chat_session`）—— 只写 `title` 一个字段。

| 字段 | 列类型 | 本阶段是否写入 | 说明 |
|---|---|---|---|
| `conversationId` | `varchar(64)` PK | 否 | 主键，创建后不可变 |
| `title` | `varchar(200) NOT NULL` | **是** | 唯一被本阶段修改的字段 |
| `createdAt` | `timestamptz NOT NULL` | 否 | |
| `updatedAt` | `timestamptz NOT NULL` | **否**（刻意） | 语义是「最近一次对话活动」，改名不算活动，见 research R-03 |

## `title` 的写入规则（唯一事实来源）

| 步骤 | 规则 | 依据 |
|---|---|---|
| 1. 归一 | `strip()` 去除首尾空白 | FR-002 |
| 2. 非空 | 归一后为空 → `VALIDATION_FAILED`「标题不能为空」 | FR-002 |
| 3. 长度 | 归一后长度 ≤ **200**（`Texts.TITLE_MAX_LENGTH`） | FR-003 |
| 4. 落库 | 写入归一后的值，**不截断** | FR-002 |

> 长度在**归一之后**判定，与 Bean Validation 的 `@Size`（判原始长度）不同 —— 因此 DTO 上不写 `@Size`，
> 详见 research R-02。列宽 `varchar(200)` 与本上限一致，超长在进入数据库前就被拒绝。

## 与「自动标题」的交互矩阵

自动标题逻辑（`initializeTitleFromFirstMessage`）的触发条件**保持不变**：
`标题 == DEFAULT_TITLE("新会话")` **且** 该会话消息数为 0。

| 场景 | 改名后标题 | 消息数 | 首条消息写入后 |
|---|---|---|---|
| 空会话改名为「报销流程」 | 报销流程 | 0 | 保持「报销流程」 |
| 空会话改名为「新会话」（字面量） | 新会话 | 0 | **被覆盖**为首条消息前 30 字（已知取舍，见 spec Assumptions） |
| 有消息的会话改名为「新会话」 | 新会话 | > 0 | 保持「新会话」（消息数守卫挡住） |
| 未改名的空会话 | 新会话 | 0 | 被自动标题覆盖（阶段 1 既有行为，不变） |

## 明确不做的变更

| 不做的变更 | 理由 |
|---|---|
| `ADD COLUMN title_locked boolean` | 只为覆盖上表第 2 行，收益不足（research R-04） |
| `ALTER COLUMN title DROP NOT NULL` | 会引入"null = 未命名"的二态，全链路判空（research R-04） |
| 标题唯一约束 / 唯一索引 | 允许同名会话，见 spec「明确不含」 |
| `rename_history` 表 | 单用户本地场景无追溯需求 |
| 修改 `updatedAt` 的写入时机 | 会破坏列表排序语义（research R-03） |
