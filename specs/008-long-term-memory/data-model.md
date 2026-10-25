# Data Model: 阶段 8 — 长期记忆

**Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## 新表 `memory`（迁移 `V9`）

```sql
CREATE TABLE memory (
    id         uuid        PRIMARY KEY,
    content    text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
```

| 字段 | 类型 | 语义 | 写入规则 |
|---|---|---|---|
| `id` | `uuid` | 主键 | 服务端生成，客户端不可指定 |
| `content` | `text` | 记忆原文 | 落库前去首尾空白；归一后 1–2000 字符 |
| `created_at` | `timestamptz` | 首次保存时间 | 创建时写入，之后不变 |
| `updated_at` | `timestamptz` | 最近修改时间 | 编辑时刷新；新增时等于 `created_at` |

### 索引与约束

- **不建索引**：预期几十至几百行，`ORDER BY updated_at DESC` 全表扫描即最优（research R-08）。
- **不加 CHECK 长度约束**：长度在 Service 层按归一后字符数判定，避免库里出现第二个口径（同 007 的标题校验）。
- **不加唯一约束**：去重在应用层按归一后精确比对完成，需要给出明确返回值而不是抛 SQL 冲突（R-06）。

### 为什么没有 `category` / `source_conversation_id` / `embedding`

见 research R-02 与 R-03：没有消费方的字段是负债。记忆的消费方式是**每次对话全量注入（带预算）**，
既不需要分类，也不需要相似度召回。

## 实体映射

`com.personalos.memory.entity.MemoryEntity`

| 字段 | 列 | 说明 |
|---|---|---|
| `id` | `id` | `String`（与项目其它实体一致，UUID 文本形式） |
| `content` | `content` | `String` |
| `createdAt` | `created_at` | `Instant` |
| `updatedAt` | `updated_at` | `Instant` |

按宪法 III：Entity **不**直接出参，对外一律 `MemoryDto(id, content, createdAt, updatedAt)`。

## 派生：注入块（不落库）

注入块是**渲染产物**，每次请求实时计算，因此记忆的增删改立即生效（FR-012），不存在缓存失效问题。

| 规则 | 值 | 依据 |
|---|---|---|
| 条数上限 | 30 | FR-006 |
| 累计字符上限 | 6000 | FR-006 |
| 排序 | `updated_at` 倒序 | FR-009 同一顺序，便于用户预期 |
| 单条是否截断 | **否**（超预算即停止累加） | R-05：残缺约束比缺失更危险 |
| 无记忆时 | 输出空串（不注入任何内容，连标题都没有） | FR-005 / SC-004 |

## 明确不做的变更

| 不做的变更 | 理由 |
|---|---|
| `memory.category` | 无消费方（R-02） |
| `memory.embedding vector(1024)` | 与「约束必须始终生效」语义冲突（R-03） |
| `memory.source_conversation_id` | 界面不展示来源、不按来源过滤 |
| `content` 唯一索引 | 去重在应用层，避免用 SQL 冲突承载业务规则（R-06） |
| 复用 `chat_message` 存记忆 | 记忆是跨会话的，消息是会话内有序流；混表会让两者的查询都变复杂 |
| 任何 V1–V8 的改写 | 迁移纪律：已应用的只追加 |
