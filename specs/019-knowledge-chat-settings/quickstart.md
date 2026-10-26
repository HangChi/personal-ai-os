# Quickstart: 019 验证步骤

每段完成后**必须真做**本节动作；截图存档 `/tmp/ui-audit/p19-*.png`。

## 0. 环境

```bash
curl -s http://localhost:8080/api/v1/health        # 期望 code 0（挂了先 docker compose up -d + 重启后端）
curl -s http://localhost:8080/api/v1/knowledge/documents/search?page=0\&size=1 | head -c 120   # 知识库有文档（26 篇基线）
```

## 1. 门禁（每段提交前）

```bash
cd backend && ./mvnw -B verify          # 后端（Testcontainers 需 Docker）
cd frontend && npm run type-check && npm run test:logic && npm run build
```

## 2. 接口级验证（Phase A，curl 即可）

```bash
CID=$(curl -s -X POST http://localhost:8080/api/v1/chat/sessions | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['conversationId'])")

# 关：无 citations 事件
curl -N -X POST "http://localhost:8080/api/v1/chat/sessions/$CID/stream" \
  -H 'Content-Type: application/json' -d '{"content":"支持哪些隔离级别？"}' | grep -c '^event:citations'   # 期望 0

# 开：有 citations 事件（N 应 >0，基线库有「隔离级别」相关内容）
curl -N -X POST "http://localhost:8080/api/v1/chat/sessions/$CID/stream" \
  -H 'Content-Type: application/json' -d '{"content":"支持哪些隔离级别？","useKnowledge":true,"topK":4}' \
  | grep '^event:' | sort | uniq -c     # 期望 start 1 / citations 1 / delta N / done 1

# 落库：GET messages 带回 citations
curl -s "http://localhost:8080/api/v1/chat/sessions/$CID/messages" | python3 -c "
import sys,json
msgs=json.load(sys.stdin)['data']
last=[m for m in msgs if m['role']=='ASSISTANT'][-1]
print('citations:', len(last['citations']), [c['documentTitle'] for c in last['citations']])"

# topK 收敛：99 → 20（看 citations 条数 ≤20）
# 删会话级联：DELETE /sessions/$CID 后查库无残留引用
```

## 3. 真机验证（Phase B/C/D，Playwright）

| 动作 | 期望 |
|---|---|
| 会话开增强，问「支持哪些隔离级别？」 | 回复带 `[1]`；消息下方「引用来源（N）」可展开；状态区出现过「已找到 N 条依据」 |
| 刷新页面重进该会话 | 引用区仍在且内容一致（SC-002） |
| 关增强问同一问题 | 无 citations、无引用区 |
| 知识库无相关内容的问题（如「今天天气」） | citations N=0，正常回答，无引用区 |
| 设置页改 Enter 发送=关 | 对话页 Enter 换行、Ctrl+Enter 发送 |
| 设置页改默认 topK=8、提醒=30 | 新增强会话默认 8；日程新建表单默认 30 |
| 设置页重置偏好（确认） | os.* 清空、刷新、主题回跟随系统 |
| 导航第 8 项「设置」 | 跳转 /settings，当前项高亮 |

## 4. 降级路径真做（spec 6.5 手法）

| 项 | 动作 |
|---|---|
| 检索失败降级 | 临时把 `KnowledgeSearchPort` 的 bean 包一层抛错的代理（或改检索服务抛异常），发一条增强消息 → 期望 error 提示事件 + 流继续答完；验完 `git checkout --` 还原 |
| 脏设置值 | DevTools 把 `os.settings.knowledgeTopK` 改成 `"abc"` → 刷新 → 回落默认 4，不白屏 |
| 并发假设 | 两个标签页同会话同时发送（前端应拦第二个）→ 确认不会双写引用 |

## 5. 回滚

每 Phase 一个提交，出问题按提交回滚；临时注入的降级验证代码不提交。
