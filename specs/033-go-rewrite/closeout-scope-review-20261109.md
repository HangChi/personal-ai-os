# Java第一版收尾：候选范围逐组审查

日期：2026-11-09；源码锚点：`0f7d5fa4f7d7382115820f508d54331572c62c0a`。

本次登记修复普通清单校验漂移，不将任何候选或五个发布门禁提升为verified。原1093文件、42迁移oracle、工具/路由/声明基线保持不变。

## 已有组的变更审查

逐文件对照旧候选哈希与源码提交，确认变化来自已提交的16条嵌入批次、扫描PDF/上传上限、结构块保护、查询扩展开关、任务/日程边界回归及对应文档；前端格式修正只改变排版。组内未变化文件保持原字节。

### scope:calendar-import-recurrence-20261105

- 候选 `backend/src/main/java/com/personalos/export/DataImportService.java`：对应当前已提交源码；不继承旧验证。

### scope:chat-retrieval-and-citation-candidate-20261105

- 候选 `.env.example`：对应当前已提交源码；不继承旧验证。
- 候选 `backend-go/internal/business/knowledge/module.go`：对应当前已提交源码；不继承旧验证。
- 候选 `backend-go/internal/business/knowledge/rag.go`：对应当前已提交源码；不继承旧验证。
- 候选 `backend/src/test/java/com/personalos/knowledge/KnowledgeRagIntegrationTest.java`：对应当前已提交源码；不继承旧验证。

### scope:dashboard-independent-statistics-20261105

- 跨组职责 `backend-go/internal/business/knowledge/http.go`：绑定同一源码提交的当前哈希。

### scope:test-and-stream-shutdown-20261106

- 候选 `backend/src/test/java/com/personalos/calendar/CalendarReminderIntegrationTest.java`：对应当前已提交源码；不继承旧验证。
- 候选 `backend/src/test/java/com/personalos/knowledge/KnowledgeIndexJobsIntegrationTest.java`：对应当前已提交源码；不继承旧验证。
- 候选 `backend/src/test/java/com/personalos/knowledge/RichDocumentIngestIntegrationTest.java`：对应当前已提交源码；不继承旧验证。

### scope:task-workspace-candidate-20261106

- 候选 `backend/src/test/java/com/personalos/task/TaskWorkspaceIntegrationTest.java`：对应当前已提交源码；不继承旧验证。

### scope:recovery-and-rag-followup-candidate-20261106

- 候选 `backend-go/internal/business/knowledge/http.go`：对应当前已提交源码；不继承旧验证。
- 候选 `backend/src/test/java/com/personalos/knowledge/rag/RagRetrievalPipelineTest.java`：对应当前已提交源码；不继承旧验证。
- 候选 `frontend/src/components/knowledge/RagTrace.vue`：对应当前已提交源码；不继承旧验证。
- 候选 `frontend/src/views/CalendarView.vue`：对应当前已提交源码；不继承旧验证。
- 候选 `frontend/test/component/knowledge-rag.test.ts`：对应当前已提交源码；不继承旧验证。
- 跨组职责 `backend-go/internal/business/knowledge/rag.go`：绑定同一源码提交的当前哈希。

### scope:workspace-ui-consolidation-20261106

- 候选 `frontend/src/components/knowledge/KnowledgeDocumentsTab.vue`：对应当前已提交源码；不继承旧验证。
- 跨组职责 `backend-go/internal/business/knowledge/http.go`：绑定同一源码提交的当前哈希。
- 跨组职责 `backend/src/main/java/com/personalos/knowledge/service/KnowledgeDocumentService.java`：绑定同一源码提交的当前哈希。

### scope:chat-evidence-context-20261106

- 候选 `backend/src/main/java/com/personalos/knowledge/rag/RagRetrievalPipeline.java`：对应当前已提交源码；不继承旧验证。
- 跨组职责 `backend/src/test/java/com/personalos/knowledge/rag/RagRetrievalPipelineTest.java`：绑定同一源码提交的当前哈希。

### scope:agent-rag-coverage-20261107

- 候选 `scripts/eval/README.md`：对应当前已提交源码；不继承旧验证。

### scope:backend-quality-gates-20261107

- 候选 `testdata/README.md`：对应当前已提交源码；不继承旧验证。
- 候选 `testdata/agent-rag/chunking.json`：对应当前已提交源码；不继承旧验证。

### scope:prior-documentation-and-test-additions-20261107

- 候选 `backend/src/test/java/com/personalos/calendar/CalendarEventBoundaryTest.java`：对应当前已提交源码；不继承旧验证。
- 候选 `docs/backend-parity.md`：对应当前已提交源码；不继承旧验证。
- 候选 `docs/configuration.md`：对应当前已提交源码；不继承旧验证。
- 候选 `docs/evaluation.md`：对应当前已提交源码；不继承旧验证。
- 候选 `docs/rag-ingestion.md`：对应当前已提交源码；不继承旧验证。
- 候选 `docs/rag.md`：对应当前已提交源码；不继承旧验证。
- 候选 `docs/reports/README.md`：对应当前已提交源码；不继承旧验证。
- 候选 `docs/user-guide.md`：对应当前已提交源码；不继承旧验证。

## 新增候选组

### scope:knowledge-ingestion-boundaries-20261109

嵌入条数/Token双重分批、扫描PDF与10MiB上传、受保护Markdown结构及真实边界回归；仅登记已提交增量。

- `backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeIndexJobs.java`
- `backend/src/main/java/com/personalos/knowledge/reader/ChatClientVisionTextExtractor.java`
- `backend/src/main/java/com/personalos/knowledge/reader/DocumentReaderFactory.java`
- `backend/src/main/java/com/personalos/knowledge/reader/PdfDocumentReader.java`
- `backend/src/main/java/com/personalos/knowledge/reader/ReaderSupport.java`
- `backend/src/main/java/com/personalos/knowledge/service/BoundedEmbeddingBatchingStrategy.java`
- `backend/src/main/java/com/personalos/knowledge/service/KnowledgeDocumentService.java`
- `backend/src/main/java/com/personalos/knowledge/splitter/MarkdownBlockScanner.java`
- `backend/src/main/java/com/personalos/knowledge/splitter/MarkdownStructureTextSplitter.java`
- `backend/src/test/java/com/personalos/knowledge/reader/PdfDocumentReaderTest.java`
- `backend/src/test/java/com/personalos/knowledge/service/BoundedEmbeddingBatchingStrategyTest.java`
- `backend/src/test/java/com/personalos/knowledge/splitter/ProtectedMarkdownBlocksTest.java`
- `backend/src/test/java/com/personalos/knowledge/splitter/MarkdownBlockScannerBoundaryTest.java`
- `frontend/nginx.conf`
- `backend-go/internal/business/knowledge/reader.go`
- `backend-go/internal/business/knowledge/reader_test.go`
- `backend-go/internal/business/knowledge/markdown_blocks.go`
- `backend-go/internal/business/knowledge/splitter.go`
- `backend-go/internal/business/knowledge/splitter_test.go`
- `backend-go/internal/platform/config/process.go`
- `backend-go/internal/platform/config/process_test.go`

### scope:query-rewrite-config-20261109

RAG查询扩展开关与精确查询模式、双端默认值和阶段SKIPPED回归；application.yml同时记录PDF视觉与上传配置。

- `backend/src/main/resources/application.yml`
- `backend/src/test/java/com/personalos/knowledge/rag/QueryRewriteConfigurationTest.java`
- `backend-go/internal/business/knowledge/query_rewrite.go`
- `backend-go/internal/business/knowledge/query_rewrite_test.go`

### scope:calendar-boundary-regressions-20261109

登记重复日程时区边界回归和日历首次窗口/视图切换组件测试；现有业务语义不因登记变更。

- `backend/src/test/java/com/personalos/calendar/recurrence/RecurrenceCalculatorTest.java`
- `frontend/test/component/calendar-view.test.ts`

### scope:corpus-evaluation-additions-20261109

登记公开语料采集、断点入库、冻结候选重排/对抗评测、离线回归和原始报告；保留失败及裁判/曝光限制，不当作双端质量批准。

- `docs/reports/knowledge-1000-2026-11-08.md`
- `docs/reports/knowledge-adversarial-2026-11-08.md`
- `docs/reports/knowledge-adversarial-2026-11-08.metrics.json`
- `scripts/eval/discover-juejin-sitemap.py`
- `scripts/eval/fetch-juejin-scale.py`
- `scripts/eval/java/KnowledgeCorpusCheck.java`
- `scripts/eval/java/KnowledgeScaleHarness.java`
- `scripts/eval/knowledge-adversarial-corpus.py`
- `scripts/eval/knowledge-adversarial-eval.py`
- `scripts/eval/knowledge-scale-cases.json`
- `scripts/eval/knowledge-scale-eval.py`
- `scripts/eval/knowledge-scale.py`
- `scripts/eval/requirements-knowledge-scale.txt`
- `scripts/eval/test_knowledge_adversarial.py`
- `scripts/eval/test_knowledge_corpus.py`
- `scripts/eval/test_knowledge_scale.py`

## 验证与边界

收尾前15项Markdown/PDF定向测试通过；格式已按Prettier修正。完整覆盖率、设施回归、当前发布物关键流程及备份恢复使用新的本地输出目录执行，结果另记Java第一版收尾报告。此处不预填通过结论。

1000/2050文档报告及原始失败不改写；新增采集工具不在本次运行中访问外站或付费模型。Go普通校验恢复可用与check-complete仍拒绝未批准发布可以同时成立。

完整执行结果见 [Java 第一版本地收尾验证](java-v1-closeout-20261109.md)。原始失败、后续补验与 Go 发布门禁分别保留。
