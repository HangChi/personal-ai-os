# Quickstart: 阶段 2 验证场景

> 只有**实际执行过**的项才能标 ✅。未执行一律 ⏳。

## 前置

- Docker 运行中；`docker compose up -d` 已启动数据库。
- 无需 API Key：自动化测试用桩模型；手工联调用 `scripts/dev/mock-openai-server.py`（见 phase 1 文档）。

## 场景 A — 自动化测试（对应 SC-001/002/004/006/007/008/009）

```bash
cd backend && ./mvnw -B verify
```

**验证点**（断言口径一律是**数据库受影响行数**）：
- 桩模型发出 `createTask` 工具调用后，`task` 表**真实新增 1 行**（SC-001）。
- 桩模型发出 `deleteTask` 工具调用后，`task` 表**行数不变**，且 `task_confirmation` 新增 1 行 `PENDING`（SC-002）。
- 确认该记录后，`task` 表**真实减少 1 行**（SC-002）。
- 同一轮内重复发出同一工具调用 → 行数只增加 1（SC-004）。
- 非法入参 → `task` 表行数变化为 0（SC-006）。
- 每次工具调用都有对应 `operation_record` 行（SC-007）。
- 过期/已使用的确认再次执行 → 100% 失败（SC-008）。

## 场景 B — REST 直连

```bash
B=http://localhost:8080/api/v1

# 建任务
TID=$(curl -s -X POST $B/tasks -H 'Content-Type: application/json' \
  -d '{"title":"交周报","priority":"HIGH"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])')

# 幂等：同一个 Idempotency-Key 发两次，只应创建 1 个任务
curl -s -X POST $B/tasks -H 'Content-Type: application/json' -H 'Idempotency-Key: smoke-001' -d '{"title":"幂等测试"}'
curl -s -X POST $B/tasks -H 'Content-Type: application/json' -H 'Idempotency-Key: smoke-001' -d '{"title":"幂等测试"}'
curl -s "$B/tasks?keyword=幂等测试"      # 期望只返回 1 条

# 同键不同内容 → 冲突
curl -s -o /dev/null -w '%{http_code}\n' -X POST $B/tasks \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: smoke-001' -d '{"title":"另一个标题"}'   # 期望 409

# 完成 / 取消完成 / 删除（人工直连，不需确认）
curl -s -X POST $B/tasks/$TID/complete
curl -s -X POST $B/tasks/$TID/reopen
curl -s -X DELETE $B/tasks/$TID
```

## 场景 C — Agent 端到端（自然语言操作，需一个模型端点）

```bash
# 终端 1：mock 模型
python3 scripts/dev/mock-openai-server.py --port 9099
# 终端 2：后端指向 mock
AI_CHAT_PROVIDER=openai OPENAI_BASE_URL=http://127.0.0.1:9099 \
OPENAI_API_KEY=mock-key OPENAI_CHAT_MODEL=mock-model ./mvnw spring-boot:run
```

> 注意：mock 只会返回固定文本，**不会真的发起工具调用**。要观察真实的工具调用链路，
> 请使用支持 function calling 的真实模型（本地 Ollama 或任意 OpenAI 兼容服务）。

**验证点**：对话里说「加一个任务：明天交周报」后，
```bash
curl -s "http://localhost:8080/api/v1/tasks?keyword=周报"     # 期望真实出现该任务
curl -s "http://localhost:8080/api/v1/agent/tool-invocations"  # 期望出现 createTask / SUCCESS
```

## 场景 D — 确认流程（对应 SC-003）

```bash
# 让助手尝试删除 → 任务仍在，且出现待确认项
curl -s "http://localhost:8080/api/v1/agent/confirmations?status=PENDING"

# 人类确认
CID=$(...); TOKEN=$(...)
curl -s -X POST "$B/agent/confirmations/$CID/confirm" -H 'Content-Type: application/json' -d "{\"token\":\"$TOKEN\"}"
curl -s "http://localhost:8080/api/v1/tasks/$TID"    # 期望 404
```

**验证点**：确认之前任务始终存在；确认之后才消失。

## 场景 E — 前端

```bash
cd frontend && npm run dev    # http://localhost:5173/tasks
```

**验证点**：任务可新建 / 筛选 / 完成 / 删除；对话中出现需确认操作时展示确认卡片。

## 验证记录表

> 全部为 2026-10-14 本机**实际执行**的结果。

| 场景 | 命令 | 结果 | 证据 |
|---|---|---|---|
| A-自动化测试 | `./mvnw -B verify` | ✅ | `Tests run: 76, Failures: 0, Errors: 0`，`BUILD SUCCESS`。含 Agent 工具调用 12 例、确认流程 9 例、任务 REST 18 例，全程无网络无密钥（桩 ChatModel + Testcontainers PG 17.11） |
| A-Agent 真实改库（SC-001） | 集成测试 | ✅ | 桩模型发出 `createTask` 后，`task` 表**真实新增 1 行**且 title/priority 正确 |
| A-确认前零写入（SC-002） | 集成测试 | ✅ | `deleteTask`/`setTaskPriority` 调用后目标数据**一行未变**，仅新增 1 条 `PENDING` 待确认；确认后行数按预期变化 |
| A-同轮去重（SC-004） | 集成测试 | ✅ | 同一轮内重复发出同一工具调用 → 只新增 1 行；跨轮次的相同调用 → 真实执行 2 次（不误判） |
| A-非法入参零写入（SC-006） | 集成测试 | ✅ | 空标题 / 不存在的任务 → `task` 表变化为 0，审计记 `REJECTED` |
| A-工具集合无确认能力（SC-003） | 集成测试 | ✅ | 反射扫描全部 `ToolContributor` 的 `@Tool` → 恰好 8 个工具，**无一含 confirm/approve** |
| A-审计完整（SC-007） | 集成测试 | ✅ | 每次工具调用都有 `operation_record`，含 outcome/duration/conversationId |
| A-确认失效语义（SC-008） | 集成测试 | ✅ | 令牌不匹配、已执行、已过期、已取消、目标已不存在 → 全部被拒绝 |
| B-REST 与幂等 | `curl` + 集成测试 | ✅ | 建/查/改/完成/删除均正常；同 `Idempotency-Key` 重放只写 1 行且 data 一致；同键不同内容 → 409/40001；键格式非法 → 400 |
| C-Agent 真机端到端 | 真机（OpenAI 兼容 mock + tool_calls） | ✅ | 「创建任务：写周报\|HIGH」→ SSE 正常 → **数据库中出现 `写周报 HIGH TODO`**；工具日志 `createTask SUCCESS 3ms`；`findTasks` 亦可触发 |
| D-确认流程真机验证 | 同上 | ✅ | 「删除任务：\<id\>」→ 任务仍 `HTTP 200` 且生成 `DELETE_TASK \| 删除任务：写周报 \| 影响 1 项`；错误令牌 → **409**；正确令牌 → `executed=true, affectedCount=1` → 任务变 `HTTP 404` |
| E-前端 | `npm run type-check` + `npm run build` | ✅ | 类型检查退出码 0；构建成功，产出 `TasksView` / `ChatView` chunk |
| F-浏览器联调 | `npm run dev` 打开 `/tasks` 与 `/chat` | ✅ | 2026-10-14 真实浏览器（`scripts/dev/verify-frontend.js`）：任务编辑弹窗预填 → 保存 → 列表与**数据库**双更新、未触碰字段不变、控制台无错误；`/chat` 的确认卡片交互于 2026-10-15 补验 |

**未验证项**：无。`F-浏览器联调` 已于 2026-10-14 / 10-15 用真实浏览器完成（含数据库断言），SC-001 ~ SC-009 均有实测证据。

**可复现说明**：C/D 两项需要模型能发起工具调用。仓库自带的
`scripts/dev/mock-openai-server.py` 已支持按前缀协议返回 `tool_calls`，命令见
`docs/development.md` 第五节；也可直接使用支持 function calling 的真实模型。
