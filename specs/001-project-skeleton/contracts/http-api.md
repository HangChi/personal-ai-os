# HTTP API 契约：阶段 0

**Base Path**: `/api/v1`

**统一响应结构**（所有接口均使用）：

```jsonc
{
  "code": 0,                 // 0 表示成功；非 0 为业务错误码
  "message": "OK",
  "data": { },               // 成功时的数据体；失败时为 null
  "timestamp": "2026-10-14T00:20:00Z"  // ISO-8601 UTC
}
```

**错误码约定**：

| code | 含义 | 对应 HTTP |
|---|---|---|
| 0 | 成功 | 200 |
| 40000 | 参数校验失败 | 400 |
| 40004 | 资源不存在 | 404 |
| 40005 | 请求方法不被支持 | 405 |
| 40900 | 业务规则冲突 | 409 |
| 50000 | 服务器内部错误（不泄漏细节） | 500 |

---

## GET /api/v1/health

**用途**：应用存活与版本自检（FR-010）。

**请求**：无参数。

**成功响应（200）**：

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "status": "UP",
    "application": "personal-ai-os",
    "version": "0.1.0-SNAPSHOT",
    "timestamp": "2026-10-14T00:20:00Z"
  },
  "timestamp": "2026-10-14T00:20:00Z"
}
```

**说明**：数据库连通性由框架自带的 `/actuator/health` 反映（含 `db` 组件）。本接口只表达「应用进程存活 + 版本可读」。

---

## 框架自带端点（Actuator）

| 路径 | 用途 |
|---|---|
| `/actuator/health` | 含数据库在内的整体健康（含 `db` 组件） |
| `/actuator/info` | 构建信息 |

> 阶段 0 仅暴露 `health` 与 `info`（`management.endpoints.web.exposure.include`），不暴露其它端点。

---

## 统一错误响应示例

**参数校验失败（400）**：

```json
{
  "code": 40000,
  "message": "参数校验失败: title 不能为空",
  "data": null,
  "timestamp": "2026-10-14T00:20:00Z"
}
```

**未捕获异常（500）** —— `message` 固定为通用文案，**不含堆栈、类名或 SQL**（FR-009 / SC-007）：

```json
{
  "code": 50000,
  "message": "服务器内部错误",
  "data": null,
  "timestamp": "2026-10-14T00:20:00Z"
}
```
