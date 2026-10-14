# HTTP API 契约：阶段 3 — 知识库与 RAG

**Base Path**: `/api/v1/knowledge`

所有响应沿用统一信封（恰好 4 个字段）。新增错误码：

| code | 含义 | HTTP |
|---|---|---|
| 40003 | 不支持的文件类型 / 空文件 / 超出大小上限（`UNSUPPORTED_DOCUMENT`） | 400 |
| 50201 | 向量维度与表结构不一致（`EMBEDDING_DIMENSION_MISMATCH`） | 500 |

沿用既有：`40000` 参数校验失败 / `40004` 资源不存在 / `50200` AI 上游失败 / `50300` AI 未配置 / `50000` 内部错误。

---

## 1. 上传文档

`POST /api/v1/knowledge/documents`　`Content-Type: multipart/form-data`

**表单字段**：

| 字段 | 必填 | 说明 |
|---|---|---|
| `file` | 是 | `.md` / `.markdown` / `.txt`，≤ 5 MiB，UTF-8 |
| `title` | 否 | 文档标题；省略时取文件名（不含扩展名） |

**响应 201**：

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "id": "7b1f…",
    "title": "Spring 学习笔记",
    "filename": "spring-notes.md",
    "contentType": "text/markdown",
    "sizeBytes": 20480,
    "charCount": 12043,
    "chunkCount": 18,
    "createdAt": "2026-10-14T02:00:00Z"
  },
  "timestamp": "…"
}
```

**错误**：
- 类型不支持 / 空文件 / 超限 → `400 / 40003`
- 嵌入维度与表不一致 → `500 / 50201`，且**零写入**
- 嵌入模型未配置 / 不可达 → `503 / 50300` 或 `502 / 50200`

---

## 2. 文档列表

`GET /api/v1/knowledge/documents`

**响应 200**：`data` 为数组，按上传时间倒序；无文档时为空数组。

---

## 3. 删除文档（含向量清理）

`DELETE /api/v1/knowledge/documents/{id}`

**响应 200**：`data` 为 `{ "deleted": true, "deletedChunks": 18 }`

**约定**：删除 MUST 同时清理该文档的全部向量（先删向量、再删文档记录，见 data-model.md）。
**404**：文档不存在。

---

## 4. 检索（不触发模型生成）

`POST /api/v1/knowledge/search`

**请求体**：

```json
{ "query": "事务的隔离级别", "topK": 4 }
```

`query` 必填（1~500 字）；`topK` 可选（默认 4，上限 20）。

**响应 200**：

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "query": "事务的隔离级别",
    "hits": [
      {
        "documentId": "7b1f…",
        "documentTitle": "Spring 学习笔记",
        "filename": "spring-notes.md",
        "chunkIndex": 5,
        "content": "…片段原文…",
        "score": 0.82
      }
    ]
  },
  "timestamp": "…"
}
```

`hits` 为空数组表示没有达到阈值的片段 —— 这是**正常结果**，不是错误。

---

## 5. 问答（附引用）

`POST /api/v1/knowledge/ask`

**请求体**：

```json
{ "question": "事务隔离级别有哪些？", "topK": 4 }
```

**响应 200**：

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "question": "事务隔离级别有哪些？",
    "answer": "根据资料，共有四种隔离级别 [1]…",
    "answered": true,
    "citations": [
      {
        "index": 1,
        "documentId": "7b1f…",
        "documentTitle": "Spring 学习笔记",
        "filename": "spring-notes.md",
        "chunkIndex": 5,
        "content": "…片段原文…",
        "score": 0.82
      }
    ]
  },
  "timestamp": "…"
}
```

**约定**：
- `citations` 的 `content` MUST 是**片段原文**，能在原文中找到（SC-005）。
- 检索无命中时：`answered = false`，`answer` 为「知识库中没有找到相关内容」，`citations` 为空数组 ——
  **不得**调用模型编造回答（FR-009）。
- 引用编号与 `answer` 中的 `[n]` 一一对应。
- **未配置模型**：检索仍会执行，但返回 `503 / 50300`（因为答案需要生成）。

---

## Agent 工具

### `searchKnowledge` — 检索个人知识库

| 参数 | 必填 | 说明 |
|---|---|---|
| `query` | 是 | 检索词，1~500 字 |
| `topK` | 否 | 返回条数，默认 4，上限 20 |

**返回**（沿用 `ToolExecutionResult` 信封）：

```json
{
  "status": "OK",
  "message": "找到 3 条相关内容",
  "data": [ { "documentTitle": "…", "chunkIndex": 5, "content": "…", "score": 0.82 } ],
  "confirmationId": null
}
```

- 无命中时 `status = "OK"`、`message = "没有找到相关内容"`、`data = []`
  （**不**用 `REJECTED`：空结果不是错误，模型应据此如实回答「资料里没有」）。
- 参数非法时 `status = "REJECTED"`，零副作用。
- 该工具是**只读**的，因此不进入人工确认范围。
