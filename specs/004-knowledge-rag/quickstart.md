# Quickstart: 阶段 3 验证场景

> 只有**实际执行过**的项才能标 ✅。未执行一律 ⏳。

## 场景 A — 自动化测试（对应 SC-001/002/003/004/005/006/007/008）

```bash
cd backend && ./mvnw -B verify
```

**验证点**（测试用确定性词袋嵌入模型 + 真实 PgVectorStore，无网络、无密钥）：
- `knowledge_document.chunk_count` == 该文档的 `vector_store` 行数（SC-001）。
- `vector_store` **逐行**校验 `vector_dims(embedding) = 1024`（SC-002）。
- 删除文档后其向量条数为 **0**，另一文档向量条数不变（SC-003）。
- 检索某主题时第一条命中来自对应文档（SC-004）。
- 问答返回的每条引用片段都能在原文中找到（SC-005）。
- 不支持类型 / 空文件 / 超限 → 两张表变化行数均为 0（SC-006）。
- 维度不一致 → 零写入且错误信息含「维度」（SC-007）。

## 场景 B — REST 冒烟（上传 → 检索 → 问答 → 删除）

```bash
B=http://localhost:8080/api/v1

# 上传
printf '# 事务隔离级别\n\nPostgreSQL 支持四种隔离级别：读未提交、读已提交、可重复读、串行化。\n' > /tmp/iso.md
DOC=$(curl -s -X POST $B/knowledge/documents -F 'file=@/tmp/iso.md' -F 'title=事务笔记')
echo "$DOC"
ID=$(printf '%s' "$DOC" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])')

# 检索（不触发模型）
curl -s -X POST $B/knowledge/search -H 'Content-Type: application/json' \
  -d '{"query":"隔离级别","topK":3}'

# 问答（附引用）
curl -s -X POST $B/knowledge/ask -H 'Content-Type: application/json' \
  -d '{"question":"支持哪些隔离级别？"}'

# 文档列表
curl -s $B/knowledge/documents

# 删除并确认向量清理
curl -s -X DELETE $B/knowledge/documents/$ID
docker compose exec -T postgres psql -U personalos -d personal_ai_os \
  -c "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = '$ID'"   # 期望 0
```

## 场景 C — 维度一致性校验（对应 SC-002/SC-007 / FR-013）

```bash
# 查看表里的真实维度
docker compose exec -T postgres psql -U personalos -d personal_ai_os -c "
SELECT format_type(a.atttypid, a.atttypmod) AS embedding_type
FROM pg_attribute a
JOIN pg_class c ON c.oid = a.attrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relname = 'vector_store' AND n.nspname = 'public' AND a.attname = 'embedding'"
# 期望：vector(1024)

# 把配置改成一个不匹配的值后启动，应当**启动失败**并提示维度不一致
AI_KNOWLEDGE_EMBEDDING_DIMENSIONS=768 ./mvnw spring-boot:run
```

## 场景 D — 真实嵌入模型（人工，可选）

自动化测试用的是确定性词袋模型，**它不能验证检索效果好坏**（没有语义泛化能力）。
要看真实效果，需本地 Ollama + bge-m3：

```bash
ollama pull bge-m3
# 默认配置即为 ollama + bge-m3，直接启动即可
./mvnw spring-boot:run
```

然后上传真实笔记，用**改写过的问法**（不含原词）提问，观察是否能召回 —— 这才是语义检索的验证。

## 场景 E — 对话中使用知识库

```bash
# 用仓库自带的 mock（支持工具调用），或任意支持 function calling 的真实模型
python3 scripts/dev/mock-openai-server.py --port 9099
AI_CHAT_PROVIDER=openai OPENAI_BASE_URL=http://127.0.0.1:9099 \
OPENAI_API_KEY=mock-key OPENAI_CHAT_MODEL=mock-model ./mvnw spring-boot:run
# 在对话里问与已上传文档相关的问题，然后查：
curl -s "http://localhost:8080/api/v1/agent/tool-invocations" | python3 -m json.tool
```

## 验证记录表

> 全部为 2026-10-14 本机**实际执行**的结果。

| 场景 | 命令 | 结果 | 证据 |
|---|---|---|---|
| A-自动化测试 | `./mvnw -B verify` | ✅ | `Tests run: 87, Failures: 0, Errors: 0`，`BUILD SUCCESS`；其中 RAG 用例 **10 个**（+1 个维度守卫用例），全程无网络、无密钥 |
| A-用例1 建索引 | 集成测试 | ✅ | `chunk_count` == 该文档向量条数；**逐行**校验 `vector_dims = 1024`；列定义 `vector(1024)` |
| A-用例2 主题命中 | 集成测试 | ✅ | topK 内第一条来自对应文档；检索接口**未调用模型** |
| A-用例3 引用可溯源 | 集成测试 | ✅ | 每条引用片段都能在原文中找到；且断言**提示词里确实带了检索片段** |
| A-用例4 删除清理 | 集成测试 | ✅ | 被删文档向量条数 0、另一文档条数不变、删除后检索不到；重复上传的两份互不影响 |
| A-用例5 空库问答 | 集成测试 | ✅ | `answered=false`、`citations` 为空、**未调用模型**（不编造） |
| A-用例6 非法输入 | 集成测试 | ✅ | `.pdf` / 空文件 / 超限 / 非 UTF-8 → `40003`，两张表变化 0 |
| A-用例7 对话检索工具 | 集成测试 | ✅ | 脚本化模型调用 `searchKnowledge` → 工具日志 `SUCCESS`，且后续提示词含检索到的片段 |
| A-用例9 维度守卫 | 集成测试 | ✅ | 768 维模型写 1024 维表 → `EMBEDDING_DIMENSION_MISMATCH`、**两张表零写入** |
| B-REST 冒烟（真机） | `curl` | ✅ | `.pdf` → `400/40003`；空文件 → `400/40003`；删除不存在 → `404/40004`；列表返回空数组；检索词为空 → `400/40000`；`docs=0, vectors=0` |
| C-维度一致性（真机） | `psql` + 启动 | ✅ | 列类型 `vector(1024)`；启动日志「向量维度校验通过」；**把配置改成 768 后退出码 1**，错误信息含「维度」与两条修复路径 |
| C-V4 迁移（真机） | `flyway_schema_history` | ✅ | v1~v4 全部 `success = t` |
| E-前端 | `npm run type-check` + `npm run build` | ✅ | 类型检查退出码 0；构建成功（新增 `KnowledgeView` chunk） |
| D-真实嵌入效果 | DashScope `qwen3.7-text-embedding-flash`（1024 维） | ✅ | **2026-10-25 补验**。用改写过的问法（不含原词）实测：「多个事务同时运行时怎样避免互相干扰」→ 0.396 命中；「how to prevent dirty reads when concurrent queries run」→ 0.464 命中；「数据库并发控制的数据可见性规则」→ 0.426 命中；无关问题「红烧肉怎么做才好吃」→ **无命中**（阈值 0.3 正确过滤）。语义泛化成立 |
| G-浏览器联调 | `npm run dev` + Playwright 实测 `/knowledge` | ✅ | **2026-10-25 补验**（此前因无嵌入模型挂起）。完整 UI 链路：选文件 → 上传（docx，`charCount=87/chunkCount=1`）→ 列表新增 → 检索（命中 3 条，首条相似度 0.804）→ 问答（回答「可重复读 · 读已提交」，引用 3 条**带来源标题与文件名**）→ 删除。控制台 0 错误 |

**未验证项**：无（2026-10-25 全部补齐）。

**为什么 D 曾经只能人工做**：词袋嵌入把「词面重叠」当作相似度，改写过的问法（同义词、换一种说法）
检索不到 —— 这不是实现缺陷，而是**测试替身的固有局限**。接入真实嵌入模型后，上面三条改写问法
全部命中、无关问题被阈值挡住，才算真正验过语义召回。自动化测试里仍保留词袋替身（它验的是链路，
不是效果），两者分工不变。
