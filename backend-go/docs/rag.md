# Go RAG 实现导航

共享算法以[RAG 全流程](../../docs/rag.md)、[摄入与结构切块](../../docs/rag-ingestion.md)、[召回/重排/证据](../../docs/rag-retrieval.md)为准，避免两份算法说明长期漂移。源码集中于 [internal/business/knowledge](../internal/business/knowledge)，由 [app](../internal/app)显式装配。

## 查代码的顺序

1. 文档服务与 parser：校验扩展名/大小、UTF-8、文本层、图片提取；PDF 使用有超时的 Poppler 外部进程，非 OCR。
2. `splitter.go`：标题栈、代码围栏识别、1200 字符目标、长段回溯、可选 overlap、短片合并、章节前缀；与 Java 结构 splitter 对照。
3. 嵌入/存储：按真实输出检查 1024 维；OpenAI 文档输入按 Java EMBED 元数据格式，默认分批 16 块，查询不是该文档格式。
4. Retriever：基础搜索与 candidates 的数量/去重不同；向量、IDF/RRF、可选 OpenSearch、Redis 候选缓存以及数据库回退。
5. RAG：改写、重排、来源治理、逐块证据精审、状态与问答引用；负例、降级和显式引用需分别验证。
6. jobs/review/projection：数据库任务、租约续租、版本与 fencing、事务后唤醒；Rabbit/OpenSearch 不是事实来源。

## Go 特有边界

- `--p6`（或 p7/p8）接入知识，默认离线诊断不接入。combined 执行全部，API 只接请求，index/review/delivery 按职责领取任务。
- 同步摄入先完成全部解析/嵌入，再在一个短事务写文档和向量；Java 同步路径使用补偿。两版异步路径均在事务外准备，提交时校验版本/租约。
- Poppler 是部署依赖，缺少/损坏/超时返回不可读错误；不承诺扫描 PDF 能得到文本。依赖许可见[第三方声明](../THIRD_PARTY_NOTICES.md)。
- Go 已修复零相关证据被归为 CLARIFY 的特定分类问题；不能据此更改 Java 历史失败或修复前全量报告。
- 数据迁移读共享 Java SQL，不复制向量 schema。变更嵌入模型/切块不会自动更新旧索引。

[评测指南](../../docs/evaluation.md)解释严格检索、多阶段状态、显式引用和 RAGAS 的各自口径；[2026-11-06 报告](../../docs/rag-evaluation-20261106.md)保留当轮双端真实结果，不是生产发布证明。
