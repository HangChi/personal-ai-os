# 验证记录（2026-11-02）

## 最终门禁

- 干净构建 `./backend/mvnw -f backend/pom.xml clean test`：1112 项通过，0 失败、0 错误、0 跳过。
- JaCoCo：行覆盖率 86.23%，分支覆盖率 70.27%，原门禁 83% / 65% 通过。
- 前端：1133 项逻辑断言、169 项组件测试通过；类型检查、ESLint、Prettier 与生产构建通过。
- Surefire 仍出现项目已有的退出等待 30 秒日志，最终 BUILD SUCCESS；这不影响断言与覆盖率结果。

## 功能验证范围

| 场景 | 证据 |
| --- | --- |
| Redis 跨实例共享、TTL、隔离与宕机回退 | RedisEmbeddingCacheIntegrationTest、CachedEmbeddingModelTest，真实 Redis |
| RabbitMQ 发布确认、无法路由回退、数据库任务恢复 | RabbitJobNotifierIntegrationTest、KnowledgeIndexJobsIntegrationTest，真实 RabbitMQ / PostgreSQL |
| S3 私有上传读取、归属校验、清理与旧本地附件 | S3AttachmentStoreIntegrationTest、ChatAttachmentServiceTest，真实 SeaweedFS |
| 中文 BM25 / kNN、旧写入拒绝、删除和维度校验 | OpenSearchGatewayIntegrationTest，真实 OpenSearch |
| PostgreSQL → outbox → OpenSearch → RAG 冲突问答 | AdvancedRagIntegrationTest，真实双存储，脚本化生成模型 |
| 删除后不返回旧片段、OpenSearch 停止后回退 | AdvancedRagIntegrationTest |
| 投影失败重试、条件 ACK 防止吞掉并发更新 | KnowledgeGovernanceIntegrationTest |
| 查询改写保留原问题、原查询零结果仍能改写召回 | RagRetrievalPipelineTest |
| 非法重排、伪造引用、模型超时与未完成精审 | RagRetrievalPipelineTest、RagModelGatewayTest |
| 可选 cross-encoder 编号、分数与协议边界 | CrossEncoderRerankerTest，本地真实 HTTP 协议交互 |
| topK=1 保留冲突双方，要求补充适用条件 | AdvancedRagIntegrationTest、RagRetrievalPipelineTest |
| 普通知识增强对话绕过答案模型、保存问答及双方引用 | ChatKnowledgeIntegrationTest |
| 录入审查增量推进、失败重试、版本变化使旧决定失效 | KnowledgeGovernanceIntegrationTest |
| 来源元数据导出恢复后重新审查 | KnowledgeGovernanceIntegrationTest、原导入导出回归 |
| 前端显示分歧、未完成状态、原文及编辑入口 | knowledge-rag.test.ts |

## 使用边界

这是可重复的功能与故障测试；脚本化模型不证明真实语料下的语义准确率提升。
上线质量需用实际资料和经过人工标注的问题，对比召回率、排序质量、冲突漏报及延迟。
后台审查只覆盖每个片段召回的相关候选，CHECKED_CANDIDATES 不表示穷尽或不存在冲突。
新增外部服务默认关闭；没有迁移现有附件，也没有重启当前运行的后端。配置见 docs/operations.md。
