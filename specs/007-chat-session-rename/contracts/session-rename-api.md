# Contract: 会话重命名接口

**Date**: 2026-10-25 | **Spec**: [spec.md](../spec.md)

## 端点

```
PATCH /api/v1/chat/sessions/{conversationId}
Content-Type: application/json
```

请求体：

```json
{ "title": "报销流程问答" }
```

- `title` 为唯一字段。缺失 / `null` / 空串 / 全空白均视为"未提供有效标题"，返回 `40000`。
- 无 `Idempotency-Key` 头（research R-07）。

## 响应 `200`

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "conversationId": "6f1a...",
    "title": "报销流程问答",
    "createdAt": "2026-10-25T05:00:00Z",
    "updatedAt": "2026-10-25T04:59:00Z",
    "messageCount": 4
  }
}
```

- 返回**完整会话视图**，字段与 `GET /sessions` 的元素完全一致（同一 `ChatSessionDto`）——
  前端因此可以就地替换列表项，无需二次请求（FR-008 / research R-05）。
- `updatedAt` 与改名请求**之前**的值相同（FR-004）。

## 错误响应

| HTTP | `code` | `message` | 触发条件 |
|---|---|---|---|
| 400 | 40000 | 会话标识格式非法 | `conversationId` 不匹配 `[A-Za-z0-9-]{1,64}`，**在查库前**拒绝 |
| 400 | 40000 | 标题不能为空 | 归一（strip）后为空 |
| 400 | 40000 | 标题长度不能超过 200 | 归一后长度 > 200 |
| 404 | 40004 | 会话不存在：{id} | 格式合法但库中无此会话 |
| 405 | 40005 | 请求方法不被支持 | 用 GET/PUT 等访问该路径（全局处理器，非本端点专门实现） |

> 注意错误码 **不区分** 三种 400 场景（格式/空/超长），靠 `message` 区分 ——
> 这是阶段 0 定下的统一错误契约，不在本阶段扩展错误码枚举（新增错误码会影响所有客户端）。

## 判定顺序（顺序本身是契约）

1. **路径参数格式校验**（`ChatSessionService.validateConversationId`）→ 400
2. **标题归一与非空**（`Texts.requireNotBlank`）→ 400
3. **标题长度**（同上，同一方法内）→ 400
4. **会话存在性**（`requireSession` 查库）→ 404
5. 写入 `title` → 200

**为什么顺序重要**：非法格式与空标题必须在查库**之前**被拒。
否则一个 `conversationId = "../../etc"` 的请求会先打一次数据库，既浪费又让"格式错误"的表现依赖库状态。
同理，标题为空时不应触发任何查询 —— 用 SC-003 的"数据库零写入"断言守住这一点。

## 行为用例表

| # | Given | When | Then |
|---|---|---|---|
| C-1 | 存在会话 S，标题「新会话」 | PATCH `{"title":"报销流程"}` | 200；`data.title == "报销流程"`；`data.updatedAt == 改名前的值` |
| C-2 | 同 C-1 | 再次 PATCH 相同标题 | 200；结果与 C-1 相同（幂等） |
| C-3 | 存在会话 S | PATCH `{"title":"  报销流程  "}` | 200；`data.title == "报销流程"`（首尾空白被去除） |
| C-4 | 存在会话 S | PATCH `{"title":""}` / `{"title":"   "}` / `{}` / `{"title":null}` | 400 / 40000 / 「标题不能为空」；库中 `title` 不变 |
| C-5 | 存在会话 S | PATCH 长度 201 的标题 | 400 / 40000 / 「标题长度不能超过 200」；库中 `title` 不变 |
| C-6 | 存在会话 S | PATCH 长度 200 的标题 | 200；`data.title` 长度 200 |
| C-7 | 存在会话 S | PATCH 长度 190、首尾各 20 个空格（原始 230） | **200**（按归一后长度判定，research R-02） |
| C-8 | 会话 T 不存在 | PATCH（合法格式 id） | 404 / 40004 |
| C-9 | — | PATCH `../../etc`（非法格式） | 400 / 40000 /「会话标识格式非法」；**无任何数据库查询** |
| C-10 | 会话 S 有 4 条消息 | PATCH 新标题 | 200；`messageCount == 4`；消息表行数不变 |
| C-11 | 三个会话 A、B、C（A 最近，`updatedAt` 递减） | 给 C 改名后 GET 列表 | 顺序仍为 A、B、C（FR-004） |
| C-12 | 会话 S 无消息，标题「新会话」 | PATCH「新会话」后再写入首条用户消息 | 标题被自动标题覆盖（已知取舍，spec Assumptions） |

## 前端契约

| 关注点 | 契约 |
|---|---|
| API 函数 | `renameSession(conversationId: string, title: string): Promise<ChatSession>` |
| Store action | `renameSession(id, title)`：成功后就地替换 `sessions` 中该项；**不**调用 `loadSessions` |
| 失败处理 | 设置 `error`，`sessions` 保持原样（不回滚、不乐观更新） |
| 参数校验 | 提交前：`title.trim()` 非空、长度 ≤ 200；与后端同口径（去空白后计算） |
| 取消 | 用户取消对话框 → 不发起请求，无副作用 |
