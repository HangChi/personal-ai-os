# HTTP API 与事件流

本页说明客户端必须遵守的协议与业务边界。完整 Controller 方法/路径由[路由参考](reference/http-routes.md)生成；请求/响应字段以对应 DTO、Controller 与共用前端 `src/api` 为准。Java/Go 共享协议目标，装配和已知差异见[双版本说明](backend-parity.md)。除特别注明，下面路径都带 `/api/v1` 前缀。

## 通用约定

普通成功/失败采用 `ApiResponse`：

```json
{"code":0,"message":"OK","data":{},"timestamp":"2026-11-07T00:00:00Z"}
```

`code=0` 表示成功；HTTP 状态与业务码分别读取。SSE、附件/业务文件、Markdown/ICS 导出不套该信封。导出中断可能留下不完整文件，不能当作成功快照。字段级契约见 [ApiResponse](../backend/src/main/java/com/personalos/common/api/ApiResponse.java)及 [ErrorCode](../backend/src/main/java/com/personalos/common/api/ErrorCode.java)。

- 认证使用会话 Cookie；启用后业务路径必须登录，health、指定 auth 与错误入口例外。`auth/me` 未登录可返回 200/null。
- 生产写请求需 `GET /auth/csrf` 后带 `X-CSRF-TOKEN`；登录轮换会话后重新取 token。对外 `/mcp` 有精确协议 CSRF 豁免，仍受认证保护。
- `X-Request-Id` 接受 `[A-Za-z0-9._-]{1,64}`，否则生成并回显；排错保留该 ID。
- 常用分页 `page` 从 0 开始、`size` 有服务端边界；不同 DTO 有 Spring Page 与项目 PageDto 等格式，不假定所有列表均为同一信封内结构。消息使用 `beforeSeq/limit` 游标，Run 事件使用数据库序号。
- 模型入口默认频率 60/分钟、前台在途 4。429 是全局准入；50203 是本轮 Agent 预算。持久 Run 许可由后台执行生命周期持有。

## 对话：持久 Run 与旧 stream

会话 CRUD/search、messages、上下文预算、模型选择、rewind 和 Markdown 导出见[路由参考](reference/http-routes.md)。主要业务入口：

| 方法/路径 | 语义 |
| --- | --- |
| `POST /chat/sessions/{conversationId}/runs` | 使用 StreamChatRequest 启动/幂等读取后台执行 |
| `GET .../runs/latest`、`GET .../runs/{id}?after=0` | 恢复页面状态及指定游标后事件 |
| `GET .../runs/{id}/events?after={cursor}` | SSE `event:run`，data 为 ChatRunDto 快照 |
| `POST .../runs/{id}/resume` | 允许的中断状态继续，受未知写/确认/问题等约束 |
| `POST .../runs/{id}/summarize` | 仅总结，不带工具，不解锁原执行 |
| `POST .../runs/{id}/stop` | 显式停止，不撤销已完成副作用 |
| `POST .../runs/{id}/questions/{questionId}/answer` | 提交澄清答案 |
| `POST .../runs/{id}/confirmations/finish` | 人工处理后收尾，不再次执行原 Agent |
| `POST/DELETE/PATCH .../runs/{id}/followups[/{inputId}]` | 排队/引导，取消或修改待发输入 |
| `POST .../runs/{id}/followups/{inputId}/steer` | 将待发输入转为执行中引导 |

这里 `...` 表示同一会话路径，不是字面 URL。Run 状态为 RUNNING/WAITING_USER/WAITING_CONFIRMATION/INTERRUPTED/STOPPED/COMPLETED；失败/恢复详情不应按不存在的通用 FAILED Run 状态处理。事件游标去重，断开读取不停止执行；等待/停止/中断不自动消费 QUEUE。完整约束见[Agent 运行](agent-runtime.md)。

旧 `POST /chat/sessions/{conversationId}/stream` 保留兼容，body 包含 `content/useKnowledge/topK/thinkingLevel/attachments` 等字段，输出 `start/citations/delta/done/error`、`rag` 检索状态、`activity` 工具进度、`warning` 降级、`yield` 安全让出及心跳。warning 不代表结束，不应因此释放执行状态。它和持久 Run 的断开语义不同，取消会处置旧流上游。前置校验可返回普通 JSON；流已建立后的错误走 SSE。客户端在 error/终态后重新拉持久消息和审计，不能假定失败永远只留下用户消息，也不能假定断开已回滚工具写入。

## 工具、计划、问题与确认

- `GET /chat/tools` 返回直接调用子集；完整 Agent 23 个标准工具、可选联网/MCP 见[工具目录](agent-tools.md)。
- `POST /chat/sessions/{conversationId}/tools` body 为 `{"tool":"createTask","arguments":{"title":"整理资料"}}`，必须携带 UUID `Idempotency-Key`。参数规范化后最多 64 KiB；未知结果保留相同身份核查，不自动重复写。
- `GET /conversations/{conversationId}/plan` 读取计划；计划变更由 Agent 工具执行，步骤证据不能只靠自然语言完成标签。
- `GET /agent/tool-invocations` 返回审计与 turnId/argumentsDigest；`outcome` 是初次调用结果，`confirmationStatus` 是关联确认的实时状态。
- `GET /agent/confirmations` 查询待办；`POST /agent/confirmations/{id}/confirm` 或 `/cancel` 由人工决定。PENDING/CONFIRMED/CANCELLED/EXPIRED/FAILED 是确认状态。没有让模型自行批准的工具。

HTTP 幂等、TOOL 所有权与外部服务幂等是不同层；相同键不同请求拒绝，结果未知不能因 UI 超时就创建新键。参见[可靠性](agent-reliability.md)。

## 知识库与来源治理

| 入口 | 用途 |
| --- | --- |
| `/knowledge/documents`、`/{id}`、`/search` | 同步上传/替换、列表、删除、标题/文件名搜索 |
| `POST /knowledge/jobs` | 异步 multipart：file，可选 documentId/title，UUID 幂等键；202 |
| `GET /knowledge/jobs[/{id}]` | 最近 20 项或单项进度；成功 result 为文档 DTO 的 JSON 文本 |
| `POST /knowledge/search` | 基础混合检索，每文档去重；score 是 cosine 或 null |
| `POST /knowledge/ask` | 多阶段保守问答；返回 answered、sources、retrieval 等 |
| `/knowledge/governance/*` | 空间、来源元数据、重试审查、冲突与人工决定 |
| `/knowledge/search-index`、`/reindex` | OpenSearch 投影积压/排队重建 |
| `/knowledge/captures`、`/{id}/refresh`、`/{id}/versions` | 公开网页抓取、版本和索引任务 |

网页采集提交后直接排索引，不存在单独“审批草稿入库”接口。刷新内容相同可不创建新版本；任务失败保留旧成功版本。解析、切块和同步/异步事务区别见[RAG 摄入](rag-ingestion.md)；READY/CONFLICT/CLARIFY/INSUFFICIENT/UNVERIFIED 与降级见[检索证据](rag-retrieval.md)。

## 其余模块

| 模块 | 路径与边界 |
| --- | --- |
| 任务/项目 | `/tasks` CRUD、complete/reopen、tags/stats；`/projects`；`/workspace/runs` |
| 日程/提醒 | `/calendar/events` CRUD；`/conflicts`；`/ics` 导出、preview/import；`/reminders`、read/scan |
| 提醒 SSE | `GET /reminders/events` 完整到点未读快照，无持久游标；见[提醒说明](reminder-stream-and-queue.md) |
| Web Push | `/reminders/push` 公开 key/开关及 `/subscriptions`，按启用配置工作 |
| 记忆 | `/memories` CRUD 与 pin；相关性注入不是全库返回 |
| 模型 | `/settings/model`、test、模型档案相关路由；返回值不暴露原密钥 |
| 附件/转写 | `/chat/uploads` multipart、`/{id}/content`；`/chat/transcribe` 及 status |
| 用量 | `/usage`，实际/估算口径分别识别 |
| 只读工作流 | `/workflows` 定义、执行、history/retry，非任意命令执行 |
| 维护 | `/maintenance` 快照、verify、drill-reports；提交演练记录不代表实际已恢复 |
| 全局搜索/交换 | `/search` 分模块结果；`/export` 流式文件；`/import` 有空库/格式约束 |
| MCP 状态 | `/mcp/servers`、reconnect、`/mcp/server`；协议端点是独立 **`/mcp`**，没有 `/api/v1` 前缀 |

## 关键错误码

| HTTP / 业务码 | 含义 |
| --- | --- |
| 400 / 40000、40003 | 参数或文件不支持 |
| 409 / 40001、40002、40900 | 幂等冲突、确认失效、状态冲突 |
| 401 / 40100；403 / 40300 | 登录或认证/CSRF 边界 |
| 404 / 40004；405 / 40005；406 / 40600 | 资源、方法、内容协商 |
| 400 / 41301 | 上下文超预算；业务码不等于 HTTP 413 |
| 429 / 42900 | 模型请求准入拒绝 |
| 502 / 50200；504 / 50202 | 模型上游错误或超时 |
| 500 / 50201 | 嵌入维度不符；不可盲重试 |
| 502 / 50203 | Agent 执行预算停止，已完成操作保留 |
| 503 / 50300；500 / 50000 | 模型未配置或内部通用错误 |

Java Controller 清单不包括 Spring Actuator/MCP 框架动态端点，也不是 Go 路由等价认证。Go 默认诊断只提供 livez/readyz；业务模块、Worker 隔离与管理监听需按其[架构](../backend-go/docs/architecture.md)核对。
