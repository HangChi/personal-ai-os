# Data Model: 阶段 2 — 任务管理与 Agent 工具调用

## 表结构（Flyway `V3`）

### `task`

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `uuid` | PK DEFAULT `uuid_generate_v4()` | 依赖阶段 0 已启用的 `uuid-ossp` |
| `title` | `varchar(200)` | NOT NULL | 标题 |
| `description` | `text` | | 描述 |
| `status` | `varchar(20)` | NOT NULL DEFAULT `'TODO'` | `TODO` / `DOING` / `DONE` |
| `priority` | `varchar(10)` | NOT NULL DEFAULT `'MEDIUM'` | `LOW` / `MEDIUM` / `HIGH` |
| `due_at` | `timestamptz` | | 截止时间，可空 |
| `completed_at` | `timestamptz` | | 完成时间；由状态迁移自动维护 |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |
| `updated_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |

**索引**：`(status, due_at)` —— 支撑「按状态筛选 + 按截止时间排序」这一最常用的查询形态。
**CHECK**：`status` / `priority` 的取值约束放在数据库层，与应用层枚举形成双重保障 —— 模型通过工具传参是最不可控的输入来源，多一层拦截是划算的。

### `task_confirmation`

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `uuid` | PK DEFAULT `uuid_generate_v4()` | |
| `token` | `varchar(64)` | NOT NULL, UNIQUE | 一次性确认令牌，**只经面向人类的接口下发** |
| `conversation_id` | `varchar(64)` | | 发起该请求的会话 |
| `action` | `varchar(40)` | NOT NULL | `DELETE_TASK` / `SCHEDULE_TASK` / `SET_PRIORITY` |
| `summary` | `varchar(500)` | NOT NULL | 面向人类的可读描述（由服务端依据 payload 生成，不采信模型文本） |
| `payload` | `text` | NOT NULL | 执行参数（JSON 文本） |
| `affected_count` | `integer` | NOT NULL | 预计影响行数，供用户判断风险 |
| `status` | `varchar(20)` | NOT NULL DEFAULT `'PENDING'` | `PENDING` / `CONFIRMED` / `CANCELLED` / `EXPIRED` |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |
| `expires_at` | `timestamptz` | NOT NULL | 默认 10 分钟后 |
| `resolved_at` | `timestamptz` | | 确认或取消的时间 |

**索引**：`(status, expires_at)` —— 支撑「列出未过期的待确认项」。
**为什么 `payload` 用 `text` 而不是 `jsonb`**：本表的 payload 只做**整体回放**，从不需要在 SQL 里按字段查询；而 Hibernate 对 `jsonb` 的映射需要额外的类型注解，会在 `ddl-auto=validate` 下引入脆弱点。用 `text` 存 JSON 是「够用且不易碎」的选择。

### `operation_record`

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `bigserial` | PK | |
| `idempotency_key` | `varchar(160)` | NOT NULL, UNIQUE | 幂等键（工具层与 HTTP 层共用该列） |
| `source` | `varchar(10)` | NOT NULL | `TOOL` / `HTTP` |
| `operation` | `varchar(120)` | NOT NULL | 工具名，或 `POST /api/v1/tasks` 这类操作标识 |
| `request_digest` | `varchar(64)` | NOT NULL | 参数的 SHA-256；同键不同摘要 → 冲突 |
| `conversation_id` | `varchar(64)` | | 工具调用才有 |
| `turn_id` | `varchar(64)` | | 工具调用才有；工具层幂等的范围边界 |
| `outcome` | `varchar(24)` | NOT NULL | `SUCCESS` / `REQUIRES_CONFIRMATION` / `REJECTED` / `DUPLICATE` / `FAILED` |
| `duration_ms` | `integer` | | 执行耗时 |
| `response_payload` | `text` | | 序列化后的响应，供幂等回放 |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |

**索引**：`(conversation_id, created_at DESC)` —— 支撑「查看某会话的工具调用历史」。

## 状态与迁移

### 任务状态
```
TODO ──(标记完成/开始)──▶ DOING ──(标记完成)──▶ DONE
  ▲                                              │
  └──────────────(取消完成)───────────────────────┘
```
- 进入 `DONE` 时写入 `completed_at`；离开 `DONE` 时清空 `completed_at`（保持「只有已完成任务才有完成时间」这一不变量）。
- 每次变更刷新 `updated_at`。

### 待确认状态
```
PENDING ──(人类确认)──▶ CONFIRMED   （执行目标操作，单次）
   │
   ├──(人类取消)────▶ CANCELLED
   └──(超过有效期)──▶ EXPIRED
```
- 只有 `PENDING` 可以被确认或取消；其余状态再次确认一律拒绝（FR-011）。
- 目标在确认时已不存在 → 记录置为 `CANCELLED`，返回可读提示，**不报 500**。

## 偏差清单

| 编号 | 偏差 | 原因 |
|---|---|---|
| D-01 | 任务表未加 `user_id` / 标签 / 父子关系 | 第一版本地单用户；标签与子任务尚无需求，属于「为想象的需求建结构」 |
| D-02 | `payload` 用 `text` 而非 `jsonb` | 只需整体回放，不需要 SQL 内查询；避免 Hibernate JSON 类型映射在 `validate` 下的脆性 |
| D-03 | 未建后台任务清理过期确认记录 | 过期在查询时按 `expires_at` 判定即可，无需定时任务；避免为「可能性」引入调度 |
| D-04 | 未给 `task.title` 加唯一约束 | 同名任务完全合法（用户可能在不同时间各建一个同名任务） |
