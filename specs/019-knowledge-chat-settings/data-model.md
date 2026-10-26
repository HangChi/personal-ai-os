# Data Model: 019-knowledge-chat-settings

本阶段后端只新增一张表；前端只新增 localStorage 偏好与 DTO 扩展。**无既有结构变更。**

## 新增表：chat_message_citation（V11）

```sql
CREATE TABLE chat_message_citation (
    id              BIGSERIAL   PRIMARY KEY,
    message_id      BIGINT      NOT NULL REFERENCES chat_message(id) ON DELETE CASCADE,
    citation_index  INTEGER     NOT NULL,          -- 回答中的引用编号 [n]，从 1 开始
    document_id     VARCHAR(64) NOT NULL,          -- 来源文档（快照，不跟随文档删除）
    chunk_index     INTEGER     NOT NULL,          -- 片段序号（0 起，与检索结果一致）
    document_title  VARCHAR(200) NOT NULL,         -- 文档标题快照
    filename        VARCHAR(255) NOT NULL,         -- 文件名快照
    content         TEXT        NOT NULL,          -- 片段内容快照
    created_at      TIMESTAMP   NOT NULL
);
CREATE INDEX idx_citation_message ON chat_message_citation(message_id);
```

设计要点：

- **快照语义**：title / filename / content 均为「回答当时」的值。知识文档删除后历史引用仍可读
  （spec FR-007）；不做与向量库的实时关联（Out of Scope 已声明）。
- **ON DELETE CASCADE**：删会话 → 消息删 → 引用删；删消息（若有路径）同样级联。
- 不存 score / matchedBy：那是检索过程量，对「回答依据」的展示无价值（知识页展示用，历史存档不必）。

## 既有结构变更

- `StreamChatRequest`：`content` → `content` + `useKnowledge`（Boolean，默认 false）+ `topK`（Integer，默认 4，1–20）
- `ChatMessageDto`：+ `citations`（`List<ChatCitationDto>`，无引用时空列表）
- SSE 事件：+ `citations`（载荷 = 命中片段数组）

## 前端数据结构

### ChatCitation（types/api.ts 新增）

```ts
interface ChatCitation {
  index: number          // [n]
  documentId: string
  documentTitle: string
  filename: string
  chunkIndex: number
  content: string
}
```

### Settings（stores/settings.ts，localStorage `os.settings.*`）

| 键 | 类型 | 默认 | 消费方 |
|---|---|---|---|
| `os.settings.knowledgeDefaultOn` | boolean | false | ChatView 新会话增强默认 |
| `os.settings.knowledgeTopK` | number (1–20) | 4 | ChatView topK 默认 |
| `os.settings.enterSend` | boolean | true | ChatView 键盘行为 |
| `os.settings.reminderLead` | number | 15 | CalendarView 新建表单默认 |

主题（`os.theme`）与侧栏折叠（`os.nav.collapsed`）已在 uiStore，设置页只做读写，不搬家。

### 会话级增强开关（ChatView 本地）

- 键：`os.chat.knowledge.<conversationId>`（布尔）；新会话缺省取 `os.settings.knowledgeDefaultOn`
- 不设全局「当前会话」存储：切会话时按目标会话的键读取，避免「上一会话的开关跟到下一会话」

## 明确不做

- 不改 `chat_message` / `chat_session` 表结构
- 不新增引用与向量库的关联表 / 外键到 knowledge 文档（快照 + 无外键是有意的：文档可删，引用不可失效）
- 不做设置的后端存储（前端偏好层，用户已确认）
