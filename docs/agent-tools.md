# Agent 工具目录与扩展

本文覆盖两版后端的内置工具、权限、调用方式及新增流程。当前实现共有 **23 个常规内置工具，另有配置后启用的 `searchWeb`**；实际运行清单受模块开关与 MCP 发现结果影响。Go 的基础定义分阶段保存，不能把 P4 的 16 个工具当成整个项目清单。

## 调用路径

模型收到工具名称、说明与 JSON Schema 后发出 tool calls。Java `@Tool`/`ToolContributor` 和 MCP callback 汇入 `AiChatClientProvider`；Go 将固定定义与业务 Action 装入 Registry。参数经适配/校验，再进入统一执行器和业务服务。

```text
工具定义 → 模型选择 → 参数校验/预算 → 执行器/幂等/审计
                                      ├─ 直接业务服务 → 回执
                                      └─ 待确认 → 用户确认接口 → 处理器 → 最终状态
```

Schema 描述是帮助模型选型，实际服务端校验才是执行边界。以下列出主要参数，必填/可选及完整约束以工具 Schema 与服务代码为准。

## 内置工具

### 任务（8）

| 工具 | 主要参数 | 执行行为 |
| --- | --- | --- |
| `createTask` | `title`，可选 description/priority/dueAt/tags | 直接创建；创建时的初始字段与后续关键字段修改不是同一确认规则 |
| `findTasks` | keyword/status/tag/limit | 查询任务，限制返回数量 |
| `getTask` | `taskId` | 读取单条任务 |
| `updateTaskDetails` | `taskId`，title/description/tags | 直接更新一般字段 |
| `setTaskCompleted` | `taskId/completed` | 完成或重新打开 |
| `deleteTask` | `taskIds` | 创建删除确认，不立即删除 |
| `scheduleTask` | `taskId`，可选 `dueAt` | 修改/清除截止时间需确认 |
| `setTaskPriority` | `taskId/priority` | 修改优先级需确认 |

### 日程与提醒（5）

| 工具 | 主要参数 | 执行行为 |
| --- | --- | --- |
| `createCalendarEvent` | `title/startsAt/endsAt`，可选地点/描述/全天/重复/提醒提前量 | 直接创建；时间使用带偏移的 ISO-8601 |
| `findCalendarEvents` | from/to/limit | 查询区间并展开重复规则 |
| `findDueReminders` | limit | 查询到点未读提醒 |
| `markReminderRead` | `reminderId` | 直接标记已读 |
| `deleteCalendarEvent` | `eventIds` | 创建删除确认 |

### 知识、记忆与交互（3）

| 工具 | 主要参数 | 执行行为 |
| --- | --- | --- |
| `searchKnowledge` | `query`，可选 topK | 检索当前知识；开启 RAG 时包含多阶段处理和保护状态 |
| `saveMemory` | `content` | 保存长期记忆，执行规范化/去重逻辑；适用于用户明确要求记住的稳定事实 |
| `askUser` | `question`，可选 options | 持久化澄清问题并暂停 Run；不属于危险操作批准 |

### 计划（7）

| 工具 | 主要参数 | 执行行为 |
| --- | --- | --- |
| `createPlan` | `goal/steps` | 建立会话计划，最多 12 步 |
| `startPlanStep` | `seq` | 按序进入执行，失败步骤可重启 |
| `completePlanStep` | `seq`，可选 summary | 完成当前步骤；须有工具证据或明确 summary |
| `failPlanStep` | `seq/reason` | 保存失败原因 |
| `skipPlanStep` | `seq`，可选 reason | 显式跳过允许跳过的步骤 |
| `revisePlan` | `reason/steps`，可选 goal | 保留旧计划并重规划 |
| `getPlan` | 无 | 获取当前/最近计划 |

### 可选联网工具（1）

| 工具 | 参数 | 启用与数据边界 |
| --- | --- | --- |
| `searchWeb` | `query`，1–500 字 | 配置 `BRAVE_SEARCH_API_KEY` 后注册；仅发公开关键词，返回可引用网页摘录 |

明确联网前缀也可走内置搜索预处理。知识、网页与受支持的 MCP 来源共用本轮编号；工具回执里的 URL/摘录不授权执行网页指令。搜索会产生外部请求，不能携带私有文档或凭据。

## 返回值、审计与幂等

统一工具结果包含 `status/message/data/confirmationId`：

| status | 含义 |
| --- | --- |
| `OK` | 工具当前动作成功，例如查询完成或创建落库 |
| `REQUIRES_CONFIRMATION` | 申请已创建，目标副作用尚未执行 |
| `DUPLICATE` | 复用先前回执；不能计作又执行了一次 |
| `REJECTED` | 参数或业务规则拒绝；修正意图/参数后再判断下一步 |
| `ERROR` | 执行失败或结果无法确认；核对状态，不能直接盲重试 |

写工具以会话 + 执行/轮次 + 工具 + 参数摘要建立工具层幂等。读工具只审计不复用旧结果。HTTP 幂等与工具层不同；未知 TOOL 所有权不能随普通成功审计清理。失败同工具同参数在当前执行预算内阻止再次执行，连续失败和总调用次数均有上限。

## 直接调用与快捷指令

`GET /api/v1/chat/tools` 返回**允许直接调用的已注册子集**，不是完整 Agent 工具全集。目录包含业务查询/写入、`getPlan` 和已注册 MCP 工具；`askUser`、计划状态修改等依赖 Run 的工具不在此目录。

`POST /api/v1/chat/sessions/{conversationId}/tools` 要求 UUID `Idempotency-Key`：

```json
{"tool":"createTask","arguments":{"title":"整理本周资料"}}
```

直接调用省去对话模型选型，仍使用认证、CSRF、会话租约、参数上限 64 KiB、确认、幂等和审计。搜索工具本身仍可能调用嵌入/RAG/外部服务；“无模型解析”不等于全链路零模型调用。前端快捷指令、sessionStorage 身份与未知结果处理见[快捷指令](chat-commands.md)。

## MCP 两个方向

外部 stdio server 被包装为 `mcp_<server>_<tool>`。原名列在 `AUTO_TOOLS` 中的工具被部署者视为只读直接执行，其余默认确认；不能把写工具加入 AUTO 来省确认。只读分类由配置者承担，不能仅相信远端自称只读。

Personal AI OS 自身对外 MCP 只发布 `personal_list_tasks/personal_search_knowledge/personal_list_calendar/personal_list_memories` 四个只读工具，与上面的 23/24 个内部 Agent 工具是不同集合。配置、重连、错误分类及外部幂等元数据见[MCP 指南](mcp.md)。

## 新增工具时同步两版

1. 先确定用户场景、输入 Schema、读取/写入/确认分类、业务服务返回值与失败语义。已有 HTTP 能力不会自动成为工具；任务工作区新字段也不意味着旧 createTask 已支持该字段。
2. Java 在相应业务适配层添加 `@Tool`，通过 `ToolContributor` 注册；执行经过 `ToolExecutor.read/write`，不要在 Controller 或提示词里实现副作用。
3. Go 在 `business/agent` 添加对应定义/Action 与注册。既有 `tool-definitions*.json` 来源是固定 Java oracle；更新定义应走已有提取/对照流程，不能手填一个仅让自己测试通过的期望值。
4. 参数规范化、枚举、UUID、空值、批量上限、确认处理器、审计/进度、恢复与未知结果都要有测试。不要给模型注册“批准自己”能力。
5. 若支持直接调用，再检查两版 allowlist、`GET /chat/tools` 和前端指令。MCP 冲突、模型热切换后的工具集合也需检查。
6. 同步本文、API/配置/版本差异，运行文档校验与对应双端回归。

源码：Java [ToolExecutor](../backend/src/main/java/com/personalos/common/ai/tool/ToolExecutor.java)、[TaskTools](../backend/src/main/java/com/personalos/agent/tool/TaskTools.java)、[DirectToolService](../backend/src/main/java/com/personalos/agent/tool/DirectToolService.java)；Go [tools.go](../backend-go/internal/business/agent/tools.go)、[definitions.go](../backend-go/internal/business/agent/definitions.go)、[commands.go](../backend-go/internal/business/agent/commands.go)。
