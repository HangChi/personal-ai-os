# 接口清单

> 端点清单与**特殊约定**。字段级细节以 `dto/` 下的 record 为准 ——
> 这里不复制字段表：复制出来的那份必然先过期，而读者不会知道哪份才对。
>
> 所有路径以 `/api/v1` 开头，下文省略该前缀。

## 通用约定

**响应信封**（除导出与 SSE 外）：

```json
{ "code": 0, "message": "OK", "data": {}, "timestamp": "2026-10-27T11:00:00Z" }
```

`code == 0` 为成功；非 0 见 `common/api/ErrorCode.java`。错误响应**不含堆栈、类名或 SQL**。

**认证**：`AUTH_ENABLED=true` 时，除 `health`、`auth/*`、`/error` 外的全部端点都要求
会话 Cookie。未登录返回 `401` + 统一信封（见 `SecurityConfig`）。

**请求链路**：任意请求可带 `X-Request-Id`（`[A-Za-z0-9._-]{1,64}`），
响应会回显；不传则服务端生成。报障时提供它，一条 `grep` 就能定位全部相关日志。

**分页**：统一用 `page`（从 0 开始）+ `size`，返回
`{ items, total, page, size }`（`PageDto`）。

**限流与并发**：覆盖对话、附件、语音转写、知识检索/问答/上传/替换及模型探测入口。
默认 60 次/分钟、最多 4 个在途请求，超出返回 `429` + `code=42900`；SSE 直到异步结束才释放并发许可。
Agent 每轮预算、错误码 `50203` 与追踪字段见 [执行边界说明](agent-reliability.md)。

---

## 健康与认证

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 应用 + 数据库 + AI 配置状态。监控探针用，免认证 |
| POST | `/auth/login` | 登录，成功后下发会话 Cookie |
| POST | `/auth/logout` | 登出并作废当前会话 |
| GET | `/auth/me` | 当前用户；**未登录也返回 200**（`data: null`），便于前端静默探测 |

## 对话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/chat/sessions` | 新建会话；标题可省略（首条消息自动生成） |
| GET | `/chat/sessions` | 全量列表（老契约，被导出链路依赖） |
| GET | `/chat/sessions/search` | 分页 + 标题关键词，按最近活动倒序。**界面用这个** |
| GET | `/chat/sessions/{id}/messages` | 消息，正序。`limit` + `beforeSeq` 用于「加载更早」；不带参数返回全部 |
| POST | `/chat/sessions/{id}/stream` | **SSE 流式对话**，见下 |
| GET | `/chat/sessions/{id}/context` | 上下文用量（token 口径，估算值） |
| PUT | `/chat/context-budget` | 设置上下文预算；同时是窗口裁剪阈值，故必须落服务端 |
| PATCH | `/chat/sessions/{id}` | 重命名（部分更新） |
| DELETE | `/chat/sessions/{id}` | 删除会话及其全部消息 |

### SSE 流式对话（唯一不套信封的接口）

```
POST /chat/sessions/{id}/stream
Body: { content, useKnowledge?, topK?, thinkingLevel?, attachments? }
Header: Idempotency-Key?   ← 同一键重试时，模型重复发出的工具调用不会二次执行
```

事件序列：`start` → `citations`（仅知识库增强）→ `delta`* → `done`；失败为 `error`。
服务端每 15 秒发一次 `: ping` 注释帧（SSE 规范里客户端本就忽略），用于穿透反代的空闲判定。

**三条客户端必须遵守的约定**：

1. **`done` 表示「该落的库都落完了」**，收到后才能去查引用。引用挂载刻意排在 done 之前。
2. `error` 之后**必须重新拉取消息**，不要沿用本地乐观插入的内容 —— 失败轮次只保留用户消息。
3. 用 `AbortController` 取消；取消后服务端会 dispose 上游订阅，不再消耗模型额度。

**前置校验失败不走 SSE**：会话不存在 / 参数非法 / 模型未配置会返回普通 JSON（404/400/503），
而不是一个只推了 `error` 的半截流。

### 附件

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/chat/uploads` | 上传附件（multipart），返回标识；发送消息时带上 |
| GET | `/chat/uploads/{id}/content` | 取回附件内容 |

## 任务

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/tasks` | 创建 |
| GET | `/tasks` | 列表，支持按状态 / 标签筛选 |
| GET | `/tasks/tags` | 已有标签去重列表（供筛选下拉） |
| GET | `/tasks/{id}` | 详情 |
| PATCH | `/tasks/{id}` | 部分更新 |
| POST | `/tasks/{id}/complete` | 标记完成 |
| POST | `/tasks/{id}/reopen` | 重新打开 |
| DELETE | `/tasks/{id}` | 删除 |

> 助手调用同名工具时，**删除、设置优先级、改截止时间**会先落一条待确认记录，
> 而不是直接执行（见下文 Agent 确认）。

## 日程与提醒

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/calendar/events` | 创建（含重复规则） |
| GET | `/calendar/events` | 区间查询；重复已展开，前端不感知规则 |
| GET | `/calendar/events/{id}` | 详情 |
| PATCH | `/calendar/events/{id}` | 部分更新 |
| DELETE | `/calendar/events/{id}` | 删除（先删提醒再删日程） |
| GET | `/reminders` | 未读且到点的提醒（前端 30 秒轮询） |
| POST | `/reminders/{id}/read` | 标记已读 |
| POST | `/reminders/scan` | 手动触发一次扫描（调试用；定时扫描同一入口） |

## 知识库

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/knowledge/documents` | 上传文档（md/txt/pdf/docx/图片），解析 → 切分 → 嵌入 |
| GET | `/knowledge/documents` | 文档列表（分页） |
| GET | `/knowledge/documents/search` | 按标题 / 文件名搜索 |
| DELETE | `/knowledge/documents/{id}` | 删除（先删向量再删记录） |
| POST | `/knowledge/search` | 混合检索，返回命中片段与来源 |
| POST | `/knowledge/ask` | 基于检索的非流式问答（内部 `.call()`，与对话走不同路径） |

## 长期记忆

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/memories` | 列表 |
| POST | `/memories` | 手动写入 |
| PATCH | `/memories/{id}` | 修改 |
| DELETE | `/memories/{id}` | 删除 |

## Agent 与确认

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/agent/tool-invocations` | 工具调用审计（分页 + 按结果筛选） |
| GET | `/agent/confirmations` | 待确认事项，`status` 可筛 |
| POST | `/agent/confirmations/{id}/confirm` | 确认并执行 |
| POST | `/agent/confirmations/{id}/cancel` | 取消 |

> **确认接口只对前端开放，工具集合中不存在任何「确认」类工具** ——
> 模型无法自行批准自己发起的破坏性操作。这是结构性约束，不依赖令牌保密。

## MCP / 搜索 / 导出

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/mcp/servers` | 各 server 的连接状态与工具数（活动页用） |
| GET | `/search` | 跨模块全局搜索，按模块分组 |
| GET | `/export` | 导出全部业务数据；**直接返回文件**，不套信封 |

## 状态码约定

| 码 | 含义 | 典型场景 |
|---|---|---|
| 400 / `40000` | 参数校验失败 | `@Valid` 未通过 |
| 401 / `40100` | 未登录或会话过期 | 前端据此跳登录页 |
| 404 / `40004` | 资源不存在 | 会话 / 任务不存在 |
| 409 / `40900` | 业务规则冲突 | 状态不允许的流转 |
| 429 / `42900` | 模型调用被限流 | 见「通用约定」 |
| 502 / `50200` | AI 上游失败 | 模型不可达、密钥错误 |
| 503 / `50300` | 模型未配置 | `AI_CHAT_PROVIDER=none` |
