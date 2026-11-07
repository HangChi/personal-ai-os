# 检索、重排、证据与引用

本项目同时保留基础检索与多阶段 RAG。下面按实际方法区分路径，避免把“混合检索”“全文搜索”和“最终回答”混成一件事。

## 1. 基础检索

`KnowledgeRetrievalService.search` / Go `Retriever.Search` 服务直接的 `/knowledge/search` 与关闭多阶段 RAG 的兼容路径。

- `topK` 默认 4，上限 20；先取 `min(topK × 3, 20)` 条候选，给去重留余量。
- 向量通道以 query 嵌入做 pgvector 余弦检索，显式应用 `similarityThreshold=0.3`。
- 关键词通道开启时提取最多 3 个英文/数字标识符（长度至少 2、含字母、去停用词），例如 `3PC/ip_hash/Object.defineProperty`。每个词各自做转义后的 `ILIKE '%token%'`，不是词首正则，也不是 PostgreSQL 全文 BM25。
- 关键词结果按片段长度与 ID 排名，并统计截断前的文档频率；融合后每篇文档保留一条最高排名片段，再取 topK。
- 基础路径的纯中文问题没有英文关键词时退为纯向量，不使用下述候选路径的中文二字拆分。

向量阈值只控制向量通道；关键词仍可能返回低向量分或没有向量分的命中。DTO 的 `score` 是向量相似度或 `null`，`matchedBy` 是 `VECTOR/KEYWORD/BOTH`，不是融合分或可信度。

PostgreSQL 关键词融合公式（rank 从 1 开始）：

```text
idf(token) = clamp(log(N / (1 + df(token))) / log(N), 0, 1)
RRF(chunk) = 1 / (k + vectorRank)
           + Σ keywordWeight × idf(token) / (k + tokenRank)
默认 k=60，keywordWeight=1.0；N<=1 时 IDF=1。
```

缺失通道贡献为 0。融合分只用于排序，不是概率；不要对 RRF 分直接应用 0.3 的向量阈值。

## 2. 多阶段候选召回

完整 RAG 调用 `candidates(query, 40)` / Go `Candidates`。该接口保留分块，不按文档提前去重，候选请求上限 60。

| 条件 | 路径 |
| --- | --- |
| 启用 OpenSearch 且当前没有待投影记录 | 查询嵌入 + OpenSearch lexical/vector 两通道；按各自排名 RRF，随后回 PostgreSQL 取当前分块 |
| OpenSearch 未启用、投影未就绪或请求失败 | PostgreSQL 向量 + 关键词候选召回 |
| PostgreSQL 候选路径没有英文关键词，且混合开关开启 | 从连续中文中按步长 2 提取最多 6 个二字片段，作为关键词兜底 |

OpenSearch lexical 通道走外部索引的词法检索，不能当成 PostgreSQL 的 ILIKE；OpenSearch 两通道使用排名融合，不沿用 PostgreSQL 每词 IDF 公式。配置与 mapping 见 Java [OpenSearchGateway](../backend/src/main/java/com/personalos/knowledge/search/OpenSearchGateway.java)、Go [search.go](../backend-go/internal/business/knowledge/search.go)。

`AI_KNOWLEDGE_HYBRID_ENABLED=false` 控制 PostgreSQL 关键词通道；它本身不禁用启用中的 OpenSearch 双通道。要比较 pgvector 纯向量，应同时固定 `OPENSEARCH_ENABLED=false` 和相关路径，不能只切一个开关。

## 3. 会话路由与查询改写

启用知识增强的聊天会先读取最近最多 6 条 USER/ASSISTANT 文本，每条最多 600 字符，以理解“那试用期呢”等指代。模型只返回 `retrieve` 和独立 `query`：任务修改/闲聊可不检索；资料追问需要补全对象、时间与限制。历史不是证据，不能拿前一轮助手文字证明事实。

路由要求严格结构，query 至多 2000 字符，retrieve=false 时必须为空。异常在对话调用方被捕获，发送 `KNOWLEDGE_RETRIEVAL_FAILED` warning 并降级普通对话；不能把降级当作“资料没有答案”或已完成知识核验。

完整检索保留原问题，再由模型生成最多 2 条、每条不超过 500 字符的检索改写。改写失败保留原问题并产生 warning。各查询候选按 `documentId:chunkIndex` 合并，再用 `Σ 1/(k+rank)` 跨查询融合，排序后最多 40 条。

## 4. 重排与多样性

默认由无工具的结构化模型返回完整候选编号排列，必须不重复、不越界、无遗漏。若显式启用 `RAG_RERANKER_ENABLED=true`，改用配置的 reranker（支持 cohere 兼容或 dashscope 原生协议）；端点、密钥和模型必须明确指定。这会把候选正文发送给该服务。

重排失败回退 RRF，标记 `rerank=FALLBACK`。重排后先每文档取一个，再补每文档第二个，形成最多 16 条的精审池；已知冲突对方来源可能被补回。先审多来源再截 topK，避免 `topK=1` 隐藏相反资料。

`rankingScores` 区分融合分与可选 rerank 分。模型返回的排序通常只有次序，不应伪造数值重排分。

## 5. 证据精审与来源治理

每条证据必须被标记为 `SUPPORTED/PARTIAL/IRRELEVANT`。冲突必须给出左右来源、连续原文摘录（至少 4 字符）和原因；服务端验证编号、覆盖和引文真实性。不同有效期或适用对象不能仅因措辞不同就判为事实矛盾。

来源元数据包括空间、版本、适用范围、有效起止日期、authority、lifecycle、revision、reviewState。数据库保存冲突与人工决定；重新检索时核对来源版本、恢复相关的既有冲突，并复核它是否影响本轮具体问题。authority 是来源信息，不是模型自行裁定真假的权限。

| 状态 | 生成策略 |
| --- | --- |
| `READY` | 有可用证据，按 topK 和每文档最多两个片段生成资料回答 |
| `INSUFFICIENT` | 无相关证据；知识问答返回固定无内容提示、`answered=false`，不调用最终回答模型 |
| `CONFLICT` | 展示影响本轮问题的来源分歧；可准备逐事项说明，不强行选择一方 |
| `CLARIFY` | 缺少时期、对象、范围等条件；提出需要补充的信息 |
| `UNVERIFIED` | 精审超时/格式错误/失败；显示来源并暂停确定结论 |

保护性说明可分开处理“已有依据的部分”和“冲突/待补充的部分”。其输出也经过结构和来源校验，失败使用保守文本回退。`answered=false` 不一定没有文字，而是没有无条件确定作答。

已知分类差异：Go 针对全部无关证据清除不必要的澄清，Java 的对应历史失败仍有保留。具体见[双版本差异](backend-parity.md)和评测报告。

## 6. 回答、引用与审计

最终资料问答使用不挂工具的客户端，片段按 `[1]...[n]` 注入。返回引用由服务端从命中元数据构造，不接受模型自编标题、UUID 或 URL。

普通 `/knowledge/ask` 从答案提取合法 `[n]`，保留原编号；如果一个合法编号都没有，当前实现回退全部候选来源。这是兼容行为，不能将其计作“显式引用正确”。聊天还通过本轮引用收集器统一知识、Brave 与受支持 MCP 网页来源，保存来源快照，历史消息可回看。

`APP_KNOWLEDGE_RAG_CITATION_AUDIT_ENABLED=true` 可为知识问答增加一次限时模型审计：最多 12 条事实，输出 `SUPPORTED/PARTIAL/UNSUPPORTED`，校验 claim 是答案连续原文、引用编号与 quote 确实对应片段。失败/超长/格式不合要求返回 `UNVERIFIED`；该审计不自动改写答案，也不保证没有遗漏。

## 7. 缓存与时间预算

- 本地嵌入缓存默认关闭；可选 Redis 共享缓存按模型/配置身份隔离。
- RAG 候选缓存默认关闭；需 `REDIS_ENABLED=true` 与 `RAG_CANDIDATE_CACHE_ENABLED=true`，TTL 20 秒，单条最多 256 KiB。缓存未过滤候选，键覆盖检索参数、模型、索引就绪和数据库版本代数；命中后仍执行证据治理，不能缓存“已批准的最终真相”。
- 候选缓存含私有正文，查询文本只以摘要进入 key；缓存 Redis 必须可信。缓存故障回到正常检索。
- RAG 总检索预算默认 20 秒；证据阶段最多 12 秒，其他调用受剩余时间和各自上限约束。超时可能产生回退，而非整个回答端到端必在 20 秒结束。

## 8. 如何排障

先看 `retrieval.status/stages/warnings/queries`，再区分召回没找到、重排把证据排后、精审否决、来源冲突、最终生成缺编号。保持相同语料、模型、检索路径和版本，再调整参数。不要用降低阈值掩盖错误嵌入模型、未完成投影或不兼容向量。

源码：Java [RagRetrievalPipeline](../backend/src/main/java/com/personalos/knowledge/rag/RagRetrievalPipeline.java)、[EvidenceReview](../backend/src/main/java/com/personalos/knowledge/rag/EvidenceReview.java)、[EvidenceAnswer](../backend/src/main/java/com/personalos/knowledge/rag/EvidenceAnswer.java)、[CitationAudit](../backend/src/main/java/com/personalos/knowledge/rag/CitationAudit.java)；Go [rag.go](../backend-go/internal/business/knowledge/rag.go)、[evidence_answer.go](../backend-go/internal/business/knowledge/evidence_answer.go)、[citation_audit.go](../backend-go/internal/business/knowledge/citation_audit.go)。
