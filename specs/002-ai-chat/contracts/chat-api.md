# HTTP API 契约：阶段 1 — AI 对话

**Base Path**: `/api/v1/chat`

所有响应沿用阶段 0 的统一信封 `{code, message, data, timestamp}`（恰好 4 个字段）。

**新增错误码**：

| code | 含义 | HTTP |
|---|---|---|
| 50300 | AI 模型未配置（`AI_NOT_CONFIGURED`） | 503 |
| 50200 | AI 服务调用失败（`AI_UPSTREAM_ERROR`） | 502 |

沿用既有：`40000` 参数校验失败 / `40004` 资源不存在 / `50000` 内部错误。

---

## 1. 创建会话

`POST /api/v1/chat/sessions`

**请求体**：

```json
{ "title": "可选的会话标题" }
```

`title` 可省略；省略时服务端以「新会话」为初值，并在写入首条用户消息时替换为消息前 30 字。

**响应 201**：

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "conversationId": "3f2a0c1e-9b7d-4a55-8f1c-2d9e6a4b7c30",
    "title": "新会话",
    "createdAt": "2026-10-14T01:00:00Z",
    "updatedAt": "2026-10-14T01:00:00Z",
    "messageCount": 0
  },
  "timestamp": "2026-10-14T01:00:00Z"
}
```

**校验**：`title` 长度 ≤ 200。

---

## 2. 会话列表

`GET /api/v1/chat/sessions`

**响应 200**：`data` 为数组，按 `updatedAt` 倒序；无会话时为空数组（不是 null）。

```json
{
  "code": 0,
  "message": "OK",
  "data": [
    { "conversationId": "…", "title": "…", "createdAt": "…", "updatedAt": "…", "messageCount": 4 }
  ],
  "timestamp": "…"
}
```

---

## 3. 查询会话消息

`GET /api/v1/chat/sessions/{conversationId}/messages`

**响应 200**：`data` 为数组，按 `sequenceNo` 正序。

```json
{
  "code": 0,
  "message": "OK",
  "data": [
    { "id": 1, "role": "USER",      "content": "你好",   "sequenceNo": 1, "createdAt": "…" },
    { "id": 2, "role": "ASSISTANT", "content": "你好！", "sequenceNo": 2, "createdAt": "…" }
  ],
  "timestamp": "…"
}
```

**错误**：会话不存在 → `404 / 40004`。

---

## 4. 流式对话（SSE）

`POST /api/v1/chat/sessions/{conversationId}/stream`

**请求头**：`Accept: text/event-stream`

**请求体**：

```json
{ "content": "帮我列一个三天的学习计划" }
```

**响应 200**，`Content-Type: text/event-stream`，事件序列：

| 事件名 | 时机 | data |
|---|---|---|
| `start` | 建立后立即 | `{"conversationId":"…"}` |
| `delta` | 每个内容片段 | `{"content":"片段"}` |
| `done` | 正常结束 | `{"conversationId":"…","content":"完整回复"}` |
| `error` | 出错 | `{"code":50200,"message":"AI 服务调用失败"}` |

**示例流**：

```
event:start
data:{"conversationId":"3f2a…"}

event:delta
data:{"content":"第一"}

event:delta
data:{"content":"天"}

event:done
data:{"conversationId":"3f2a…","content":"第一天…"}
```

**约定**：
- `delta` 的 `content` 片段按顺序拼接后 MUST 等于 `done` 的 `content`（SC-002）。
- 出错时 MUST 推 `error` 事件并结束流，MUST NOT 直接断开连接。
- 会话不存在 → **在建立流之前**返回 `404 / 40004`（普通 JSON 响应，不是 SSE）。
- `content` 为空或超过 4000 字 → 在建立流之前返回 `400 / 40000`。
- 客户端断连时应停止推送；此轮不写入助手消息。

**失败轮次的历史语义（实测）**：某轮失败时，该轮的**用户消息会保留**（由框架在调用前写入），
但**不会写入任何助手消息**。客户端收到 `error` 后应重新拉取消息列表，而不要自行伪造一条助手回复。

---

## 5. 删除会话

`DELETE /api/v1/chat/sessions/{conversationId}`

**响应 200**：`data` 为 `null`。级联删除该会话全部消息。

**错误**：会话不存在 → `404 / 40004`。
