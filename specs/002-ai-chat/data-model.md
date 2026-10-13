# Data Model: 阶段 1 — AI 对话与聊天历史

## 表结构（Flyway `V2`）

### `chat_session`

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `conversation_id` | `varchar(64)` | PK | 对外会话标识，同时作为 Spring AI 的 conversationId（隔离边界） |
| `title` | `varchar(200)` | NOT NULL | 会话标题；未指定时取首条用户消息前 30 字 |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `now()` | 创建时间 |
| `updated_at` | `timestamptz` | NOT NULL DEFAULT `now()` | 最近活动时间，新消息时刷新（FR-019） |

### `chat_message`

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `bigserial` | PK | 自增主键 |
| `conversation_id` | `varchar(64)` | NOT NULL, FK → `chat_session(conversation_id)` ON DELETE CASCADE | 所属会话（FR-006 级联删除） |
| `message_type` | `varchar(20)` | NOT NULL | `USER` / `ASSISTANT` / `SYSTEM` / `TOOL` |
| `content` | `text` | NOT NULL | 文本内容 |
| `sequence_no` | `integer` | NOT NULL | 会话内顺序号，从 1 递增 |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `now()` | 创建时间（仅展示用，排序不依赖它） |

**约束与索引**：
- `UNIQUE (conversation_id, sequence_no)` —— 保证顺序稳定（FR-018），并在并发写入时以唯一冲突暴露问题而不是静默乱序。
- `INDEX (conversation_id, sequence_no)` —— 消息查询与窗口取值的走法。

**为什么用 `sequence_no` 而不是只靠 `created_at` 排序**：同一轮对话中用户消息与助手消息的写入可能落在同一毫秒，且系统时钟可回拨；序号是唯一的稳定依据。契约里明确「不依赖时间戳排序」（FR-018）。

**为什么用 `bigserial` 而不是 `uuid` 作为消息主键**：消息量大且严格有序，自增主键写入更友好，也让「第几条」语义直观。

## 领域对象与表映射

| 领域概念 | 表 | 备注 |
|---|---|---|
| 会话 | `chat_session` | 一行一会话 |
| 消息 | `chat_message` | 一行一消息，按 `sequence_no` 排序 |

## 自定义 ChatMemory 的读写语义

> 这是本阶段最关键的实现约定，先钉死避免实现时各做各的。

| 操作 | 语义 |
|---|---|
| `add(conversationId, messages)` | **追加**：为每条消息分配 `sequence_no = max(sequence_no) + 1`，**不删除任何既有记录**；同时刷新会话 `updated_at` |
| `get(conversationId)` | 读取**最近 N 条**（N 来自 `app.ai.memory.max-messages`，默认 20），按 `sequence_no` 正序返回，并转换为 Spring AI 的 `Message` |
| `clear(conversationId)` | 删除该会话的**全部**消息（用于删除会话） |

**为什么不用 `MessageWindowChatMemory`**：它在每次 `add` 后会把「裁剪过的窗口」整体 `saveAll` 回仓库，等价于**用窗口覆盖历史**。窗口滑出的旧消息会从数据库消失，与 FR-007 冲突。自定义实现把「上下文窗口」与「持久化历史」彻底解耦：窗口只作用于**送给模型的内容**，库中历史永远保留。

## 偏差清单

| 编号 | 偏差 | 原因 |
|---|---|---|
| D-01 | 消息表未记录 token 数与模型名 | 阶段 1 无计费与统计需求；阶段 5 若做用量统计再补列（避免为想象的需求建列） |
| D-02 | 会话表未加 `user_id` | 第一版仅本地单用户（宪法原则 II），加该列会产生「看起来支持多用户」的误导 |
| D-03 | 未建 `chat_session.title` 的唯一约束 | 标题可重复（Edge Case 已明确以标识为准区分） |
