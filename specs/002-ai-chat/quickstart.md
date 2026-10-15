# Quickstart: 阶段 1 验证场景

> 只有**实际执行过**的项才能标 ✅。未执行一律 ⏳。

## 前置

- Docker 运行中；`docker compose up -d` 已启动数据库（阶段 0 已就绪）。
- 无需任何模型 API Key（默认走本地 Ollama；未安装也不影响启动与测试）。

## 场景 A — 自动化测试（对应 SC-002/003/004/008）

```bash
cd backend && ./mvnw -B verify
```

**验证点**：集成测试使用**桩 ChatModel**，全程无网络、无密钥。
- 一轮流式对话后，库中恰好新增 2 条消息（1 用户 + 1 助手），`done` 的完整文本 == 所有 `delta` 拼接（SC-002）。
- 跨会话上下文泄漏数 == 0（SC-003）。
- 5 轮对话后库中 10 条，而送给模型的上下文 ≤ 上限（SC-004）。

## 场景 B — 无密钥启动（对应 SC-005）

```bash
cd backend && ./mvnw spring-boot:run
curl -s http://localhost:8080/api/v1/health          # 期望 code=0
curl -s http://localhost:8080/api/v1/chat/sessions    # 期望 code=0, data=[]
```

## 场景 C — REST 链路冒烟

```bash
# 1) 建会话
CID=$(curl -s -X POST http://localhost:8080/api/v1/chat/sessions \
  -H 'Content-Type: application/json' -d '{"title":"冒烟测试"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["conversationId"])')
echo "conversationId=$CID"

# 2) 会话列表
curl -s http://localhost:8080/api/v1/chat/sessions

# 3) 不存在的会话 → 应 404/40004
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/chat/sessions/not-exist/messages
```

## 场景 D — SSE 流式（对应 SC-001/006）

```bash
curl -N -s -X POST "http://localhost:8080/api/v1/chat/sessions/$CID/stream" \
  -H 'Accept: text/event-stream' -H 'Content-Type: application/json' \
  -d '{"content":"用一句话介绍你自己"}'
```

**验证点**：
- 依次出现 `event:start` → 多个 `event:delta` → `event:done`。
- 若模型不可达，出现 `event:error` 且含 `code`（SC-006），而不是连接静默断开。

```bash
# 消息确实落库
curl -s "http://localhost:8080/api/v1/chat/sessions/$CID/messages"
```

## 场景 E — 会话隔离（对应 SC-003）

```bash
# 在会话 A 说一个暗号，再在会话 B 提问，检查 B 的上下文不含暗号
# （自动化版本见集成测试；手工版：观察 B 的回复不应提及暗号）
```

## 场景 F — 前端联调

```bash
cd frontend && npm run dev   # http://localhost:5173/chat
```

**验证点**：左侧会话列表可新建/切换；输入消息后回复**逐字出现**；后端未启动时显示可读错误提示。

## 验证记录表

> 全部为 2026-10-14 本机**实际执行**的结果。

| 场景 | 命令 | 结果 | 证据 |
|---|---|---|---|
| A-自动化测试 | `./mvnw -B verify` | ✅ | `Tests run: 37, Failures: 0, Errors: 0`，`BUILD SUCCESS`；其中聊天集成测试 12 个，全程无网络、无密钥（桩 ChatModel + Testcontainers PostgreSQL 17.11） |
| A-上下文与隔离 | 同上 | ✅ | 5 轮后库中 10 条历史一条未少；送入模型的历史 ≤ 6 条；**跨会话泄漏 0 条** |
| B-无密钥启动 | 默认 `AI_CHAT_PROVIDER=ollama` 启动 | ✅ | 应用正常启动、健康检查 200；`AI_CHAT_PROVIDER=openai` 时日志 `AI 聊天能力已启用：model=OpenAiChatModel` |
| C-REST 冒烟 | curl 建会话 / 列表 / 404 / 400 | ✅ | 建会话 201 且返回 `conversationId`；列表含该会话；不存在会话 → 404/`40004`；空内容 → 400/`40000`。响应体恰好 4 字段，无 `success` |
| D-SSE 流式 | `curl -N ... /stream` | ✅ | 原始帧为 `event:start` → `event:delta`×4 → `event:done`；`done.content == 各 delta 拼接`；库中恰好 2 条消息（USER seq1 / ASSISTANT seq2） |
| E-上游失败 | 停掉模型服务后再请求 | ✅ | **0 秒内**推送 `event:error`，`code=50200`；无堆栈/类名；库中只多出一条用户消息，**没有半截助手消息** |
| F-V2 迁移 | `psql` 查 `flyway_schema_history` | ✅ | version 1 与 2 均 `success = t`；`chat_session` / `chat_message` 建表成功 |
| G-前端 | `npm run type-check` + `npm run build` | ✅ | 类型检查退出码 0；构建成功（1663 模块，产出 `ChatView` chunk） |
| H-浏览器联调 | `npm run dev` 打开 `/chat` | ✅ | 2026-10-15 真实浏览器完成：新建会话 → 发消息 → SSE 逐字渲染 → 确认卡片交互 → 点击确认 → 无控制台错误（阶段 6 真机验证时顺带覆盖；SSE 契约此前已由 D 项用 curl 实测） |

**未验证项**：无。`H-浏览器联调` 已于 2026-10-15 用真实浏览器完成，SC-001 ~ SC-008 均有实测证据。

**可复现说明**：D/E 两项的验证需要一个 OpenAI 兼容的模型端点。仓库自带
`scripts/dev/mock-openai-server.py`（仅标准库）用于此目的，命令见 `docs/development.md` 第五节；
也可直接使用本地 Ollama 或任意 OpenAI 兼容服务。
