# Feature Specification: 对话知识库增强 + 设置页

**Feature Branch**: `019-knowledge-chat-settings`

**Created**: 2026-10-26

**Status**: Approved

**Input**: 用户要求「知识库检索集成到对话中」+「需要设置功能」；经确认：知识库走**自动 RAG 增强**（会话开关、回答带 [n] 引用、引用落库刷新不丢），设置页先做**前端偏好层**（localStorage，零后端配置接口）。

## 背景与现状（2026-10-26 实测）

- 对话流端点 `POST /api/v1/chat/sessions/{id}/stream` 只收 `{content}`，无任何知识库参数。
- 助手虽有 `searchKnowledge` 工具（Agent 工具调用），但**是否调用由模型自行决定**——不可控、不保证引用、用户无法感知「这次回答有没有查知识库」。
- 后端已有成熟能力可直接复用：`KnowledgeRetrievalService.search(query, topK)`（混合检索，返回 `KnowledgeHitDto`：documentId / documentTitle / filename / chunkIndex / content / score / matchedBy）。
- 消息落库路径：`MessageChatMemoryAdvisor` → `JpaChatMemory.add()`（只追加，不裁剪）。设置页目前不存在，配置全在 `.env`。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 会话级知识库增强开关 (Priority: P1)

作为用户，我在对话页打开「知识库增强」开关后发送的每条消息，都会先自动检索我的知识库，助手基于检索到的内容回答，并在回复下方列出引用的文档片段。

**Why this priority**: 这是本阶段核心价值——把「模型想不起才查」变成「确定性地查、确定性地引用」。

**Independent Test**: 任意会话打开开关，问一个知识库里有的问题（如「支持哪些隔离级别？」），回复带 `[1]` 编号且下方出现引用来源；关掉开关再问同一问题，回复不带引用。

**Acceptance Scenarios**:

1. **Given** 会话中开关为关, **When** 发送消息, **Then** 行为与现在完全一致（无检索、无引用）
2. **Given** 开关为开且知识库有相关内容, **When** 发送消息, **Then** 流式开始后立即收到 `citations` 事件（含命中片段），回复正文使用 `[n]` 编号引用
3. **Given** 开关为开但知识库无相关内容（检索 0 条）, **When** 发送消息, **Then** 正常回答且不出现引用区，不报错（与知识页问答的「无命中不调模型」不同——这里模型仍然回答，只是没有依据）
4. **Given** 开关为开, **When** 切换会话 / 刷新页面, **Then** 开关状态保持（按会话持久化）
5. **Given** 新会话, **When** 打开对话页, **Then** 开关默认值取自设置页的「知识库增强默认开关」

---

### User Story 2 - 引用随消息持久化 (Priority: P1)

作为用户，刷新页面或切走再回来后，历史助手消息下方的引用来源依然在。

**Why this priority**: 引用是「回答依据」的证据，只存在于当次会话等于没有；知识页问答已有引用展示，对话历史不能缺。

**Independent Test**: 开增强发一条消息 → 刷新页面 → 重新进入该会话 → 引用区仍在且内容与刷新前一致。

**Acceptance Scenarios**:

1. **Given** 一条带引用的助手消息, **When** `GET /sessions/{id}/messages`, **Then** 该消息的 `citations` 数组非空，字段含 index / documentTitle / filename / chunkIndex / content
2. **Given** 删除知识文档, **When** 查看历史消息引用, **Then** 引用快照仍展示（快照语义：记录「回答当时」的依据，不随文档删除消失）
3. **Given** 删除会话, **When** 查看数据库, **Then** 该会话的引用记录一并清除（外键级联）

---

### User Story 3 - 设置页 (Priority: P2)

作为用户，我有一个设置页，可以调整外观与对话偏好：主题（浅色/深色/跟随系统）、侧栏折叠、知识库增强默认开关、默认检索条数、Enter 发送还是 Ctrl+Enter 发送、日程默认提前提醒分钟数；还能一键导出数据和重置全部本地偏好。

**Why this priority**: 设置项分散在各自页面里（主题在顶栏、视图偏好在各页），需要一个统一入口；默认值（如检索条数）也是故事 1 的依赖。

**Independent Test**: 访问 /settings，修改每一项 → 立即生效 → 刷新后保持；点「重置偏好」→ 全部回到默认。

**Acceptance Scenarios**:

1. **Given** 设置页, **When** 切换主题为深色, **Then** 全站立即变深色且刷新保持（与顶栏切换同一存储键）
2. **Given** 设置页, **When** 关闭「Enter 发送」, **Then** 对话页 Enter 换行、Ctrl/Cmd+Enter 发送
3. **Given** 设置页, **When** 修改默认检索条数为 8, **Then** 之后新开的增强会话默认按 8 条检索（可在对话页临时改）
4. **Given** 设置页, **When** 修改默认提前提醒为 30 分钟, **Then** 日程页新建表单的「提前 N 分钟」默认变为 30
5. **Given** 设置页, **When** 点击「重置全部偏好」并确认, **Then** 所有 `os.*` 本地键被清除、页面恢复默认（主题回跟随系统）
6. **Given** 设置页, **When** 点击「导出数据」, **Then** 与首页导出同一行为（下载 JSON）

---

### User Story 4 - 增强状态的可见性 (Priority: P2)

作为用户，我能随时看到当前会话是否开着增强、这一轮检索到了几条依据；检索中也有明确指示，不会以为卡住了。

**Why this priority**: 自动 RAG 最大的用户困惑是「它到底查没查」——状态必须可见。

**Independent Test**: 开增强发送消息，观察输入区上方 / 消息区的状态指示与引用计数。

**Acceptance Scenarios**:

1. **Given** 开关为开, **When** 查看对话页, **Then** 输入区附近显示「知识库增强已开启」的持久指示（不是一闪而过的 toast）
2. **Given** 增强会话发送中, **When** 检索正在进行, **Then** 消息区显示「正在检索知识库…」；检索完成后显示「已找到 N 条依据」
3. **Given** 检索接口失败（后端 500）, **When** 发送消息, **Then** 该轮降级为普通回答 + 错误提示，不中断对话（FR-005）

---

### Edge Cases

- 检索命中但模型完全不引用（回答里没有 `[n]`）→ 引用区照常展示命中的片段（它们是真实检索结果），标题注明「本轮检索到的依据」
- 同一会话两条消息并发发送（两个浏览器标签）→ 前端已禁止（streaming 中禁发）；后端引用挂载按会话串行假设，见 plan Complexity Tracking
- 知识库为空（0 文档）时开增强 → 检索 0 条，正常回答，引用区不出现
- topK 超过后端上限 20 → 前后端都收敛到 20
- 引用内容超过 10 万字符（极端）→ 引用区默认折叠，不撑爆消息流
- 设置页在登录页不可达（守卫重定向）；authEnabled=false 时 /settings 直接可达

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 流式请求体 MUST 扩展 `useKnowledge`（boolean，默认 false）与 `topK`（int，默认 4，范围 1–20）；省略时行为与现在逐字符一致
- **FR-002**: `useKnowledge=true` 时，服务端 MUST 在调用模型前用 `KnowledgeRetrievalService` 检索（topK 条），将命中片段以 `[1] [2]…` 编号注入**请求级 system 提示**（显式带基础人设，遵守阶段 1 的替换语义）
- **FR-003**: 注入的检索片段 MUST 附带使用约束（编号引用、无依据时明说、不得编造片段外的内容）
- **FR-004**: 检索成功后 MUST 立即发送 `citations` SSE 事件（载荷 = 命中片段数组，字段与 `KnowledgeHitDto` 对齐）
- **FR-005**: 检索失败（异常）MUST 降级为普通回答并发送提示事件（不得中断本轮对话）
- **FR-006**: 引用 MUST 持久化：V11 建 `chat_message_citation` 表（message_id 外键 + 索引/文档/片段快照），`GET messages` 返回时按消息附带
- **FR-007**: 删除会话 MUST 级联删除其引用记录；删除知识文档 MUST NOT 影响历史引用快照
- **FR-008**: 前端 MUST 提供会话级「知识库增强」开关（按会话持久化 localStorage；新会话取设置页默认值）与 topK 调整（1–20）
- **FR-009**: 助手消息 MUST 在内容下方渲染引用区（编号 + 文档标题 + 文件名 + 片段号 + 内容），默认折叠、可展开；>3 条时折叠为「引用来源（N）」
- **FR-010**: 检索过程 MUST 有可见状态（「正在检索知识库…」→「已找到 N 条依据」）
- **FR-011**: 设置页 `/settings` MUST 包含：主题三态、侧栏折叠、知识库默认开关、默认检索条数、Enter 发送开关、默认提前提醒分钟数、导出数据、重置偏好、关于（版本 / 后端状态）
- **FR-012**: 设置项 MUST 全部持久化 localStorage（`os.settings.*` 命名空间），修改后立即生效、刷新保持
- **FR-013**: 「重置全部偏好」MUST 二次确认，清除全部 `os.*` 键后恢复默认并刷新页面
- **FR-014**: 侧栏导航 MUST 增加「设置」入口（第 8 个导航项）
- **FR-015**: 新增纯逻辑（设置默认值合并、topK 收敛、Enter 行为判定）MUST 有 `.mjs` 单测

### Key Entities

**后端新增**：

- `chat_message_citation`（V11）：id / message_id（FK → chat_message.id，ON DELETE CASCADE）/ citation_index / document_id / chunk_index / document_title / filename / content（快照）/ created_at
- `StreamChatRequest` 扩展：content / useKnowledge / topK

**前端新增**：

- `stores/settings.ts`：主题、侧栏、知识库默认开关、默认 topK、enterSend、默认提醒提前量（全部 `os.settings.*` 持久化）
- `ChatMessage.citations`：消息 DTO 扩展
- SSE `citations` 事件载荷

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 增强开启时，知识库有相关内容的问题 100% 触发检索（集成测试断言 `citations` 事件与落库记录）；关闭时 0 检索
- **SC-002**: 引用持久化：刷新 / 切会话再回，历史消息引用区内容与刷新前一致（集成测试 + 真机截图）
- **SC-003**: 门禁全绿：后端 `./mvnw -B verify`（新增迁移 + 集成测试）、前端 `type-check` + `test:logic`（基线 314 + 新增） + `build`
- **SC-004**: 检索失败降级：检索服务抛错时本轮对话正常完成且给出提示（集成测试）
- **SC-005**: 真机验收截图：增强开 / 关两态对话、引用区展开、设置页各 section、重置偏好后的默认态
- **SC-006**: 无知识库内容时增强开启：正常回答、无引用区、无报错（真机）

## Assumptions

- 「自动 RAG」指服务端确定性检索 + 注入，不依赖模型是否选择调用工具；工具 `searchKnowledge` 保持原样（模型仍可主动调用，两者不冲突）
- 引用为**快照**（内容 / 标题 / 文件名取回答当时的值），不做与向量库的实时关联
- 会话级开关存前端 localStorage（本地单用户，无多端同步需求）；全局默认值在设置页
- 同一会话同一时刻只有一个进行中的流（前端已保证）；后端引用挂载依赖该假设
- 设置页只做前端偏好；后端运行时配置（模型 / 阈值）明确不做（用户已确认）

## 明确不含（Out of Scope）

| 项 | 依据 |
|---|---|
| 后端运行时配置接口（模型选择 / 相似度阈值 / 混合检索开关） | 用户确认设置页先做前端偏好层 |
| 检索结果的手动挑选 / 重排（人在回路） | 与「自动 RAG」定位不同；如需要是后续阶段 |
| 引用内容与向量库的实时同步（文档更新后历史引用刷新） | 快照语义已声明 |
| 跨会话的知识库范围选择（按文档过滤） | v1 全库检索 |
| 设置页的账号体系（改密码等） | 本地单用户 + auth 默认关闭 |
