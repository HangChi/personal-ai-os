# Implementation Plan: 阶段 3 — 个人知识库与 RAG

**Branch**: `004-knowledge-rag` | **Date**: 2026-10-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-knowledge-rag/spec.md`

## Summary

新增 `knowledge` 模块：上传 → `DocumentReader` 解析 → `TokenTextSplitter` 分块 → `EmbeddingModel` 向量化 → `PgVectorStore` 落库；检索走 `VectorStoreRetriever`；问答把检索片段编号后注入提示词，并把**片段的来源元数据**作为结构化引用返回。向量表由 Flyway 创建（`vector(1024)`），启动期校验表维度与配置维度一致，写入前校验嵌入维度。检索同时以 Agent 工具形式接入对话（复用阶段 2 的 `ToolContributor` SPI）。测试用**确定性词袋嵌入模型**替代真实模型，做到无网络、无密钥、可重复，并逐行验证向量维度。

## Technical Context

**Language/Version**: Java 21；TypeScript 5.9.3

**Primary Dependencies**:
- Spring AI 2.0.1：`Document` / `DocumentReader` / `TextReader` / `TokenTextSplitter`（`spring-ai-commons`）、`EmbeddingModel`（`spring-ai-model`）、`VectorStore` / `VectorStoreRetriever` / `SearchRequest`（`spring-ai-vector-store`）、`PgVectorStore`（`spring-ai-pgvector-store`）
- 已核实：pgvector 期望的表结构为 `id` / `content text` / `metadata json` / `embedding vector(%d)`；
  配置前缀 `spring.ai.vectorstore.pgvector`（含 `dimensions`、`index-type`、`distance-type`、`table-name`、`schema-name`）
- 嵌入模型：`spring.ai.model.embedding=ollama` + `spring.ai.ollama.embedding.model=bge-m3`

**Storage**: PostgreSQL 17 + pgvector；新增表 `knowledge_document`、`vector_store`

**Testing**: JUnit 5 + `@SpringBootTest` + Testcontainers + **确定性词袋 EmbeddingModel**（测试支撑）

**Target Platform**: 本地单用户自托管

**Project Type**: Web application（Monorepo：backend + frontend）

**Performance Goals**: 单文档（≤1 MiB）入库 < 30s（本地模型）；检索 topK=4 < 200ms（本地，少量数据）

**Constraints**: 无密钥可启动；维度不一致必须零写入；向量清理不得残留

**Scale/Scope**: 个人笔记量级（数百文档、数万片段）；阶段 3 新增约 35 个文件

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先，拒绝过度设计 | ✅ 通过 | 不引入 ETL 框架、消息队列、异步任务；上传同步处理（个人量级足够）。不做混合检索与重排序（用户已确认） |
| II. 边界即安全 | ✅ 通过 | 知识检索作为工具暴露，但**只读**，不产生任何副作用；文件类型/大小/编码在边界校验；不保存原文，减少敏感面 |
| III. 分层与写路径收敛 | ✅ 通过 | 新增 `knowledge`（controller/service/repository/entity/dto + reader/splitter 适配）；入库与删除均经 Service；向量操作不直连 SQL |
| IV. 数据变更可追溯 | ✅ 通过 | `V4__create_knowledge_tables.sql` 单一迁移创建 `knowledge_document` 与 `vector_store`（含 HNSW 索引）；`ddl-auto=validate` |
| V. 可验证的交付 | ✅ 通过 | SC-002 要求**逐行**校验向量维度（SQL 全表比对）；SC-003 要求删除后向量条数为 0；SC-006/007 要求失败路径**零写入** |

**结论**：无违例。

## Project Structure

### Documentation (this feature)

```text
specs/004-knowledge-rag/
├── spec.md  ├── plan.md  ├── research.md  ├── data-model.md
├── quickstart.md  ├── tasks.md
├── contracts/knowledge-api.md
└── checklists/requirements.md
```

### Source Code (repository root)

```text
backend/src/main/java/com/personalos/
├── common/ai/EmbeddingProperties.java          # app.knowledge 之外的嵌入维度/模型配置
├── knowledge/
│   ├── entity/KnowledgeDocumentEntity.java
│   ├── repository/KnowledgeDocumentRepository.java
│   ├── reader/KnowledgeDocumentReader.java      # 实现 DocumentReader（内部用 TextReader）
│   ├── service/KnowledgeDocumentService.java    # 上传 / 列表 / 删除（含向量清理）
│   ├── service/KnowledgeRetrievalService.java   # 检索（VectorStoreRetriever）
│   ├── service/KnowledgeQaService.java          # 问答（检索 → 编号注入 → 生成 → 引用）
│   ├── service/VectorDimensionValidator.java    # 启动期表维度校验 + 写入期维度守卫
│   ├── dto/*                                    # KnowledgeDocumentDto / Citation / AskRequest / AskResponse / SearchRequest ...
│   └── controller/KnowledgeController.java      # 上传 / 列表 / 删除 / 检索 / 问答
├── knowledge/tool/KnowledgeTools.java           # @Tool：searchKnowledge
├── knowledge/tool/KnowledgeToolContributor.java
└── common/api/ErrorCode.java                    # 新增 40003 / 50201（见契约）

backend/src/main/resources/db/migration/V4__create_knowledge_tables.sql
backend/src/test/java/com/personalos/
├── support/DeterministicEmbeddingModel.java     # 确定性词袋嵌入（1024 维）
└── knowledge/KnowledgeRagIntegrationTest.java   # ≥5 个可重复 RAG 用例

frontend/src/
├── api/knowledge.ts  stores/knowledge.ts
└── views/KnowledgeView.vue                      # 上传 / 文档列表 / 检索 / 问答（含引用）
```

**Structure Decision**：沿用「单 Maven 模块 + 按包分模块」。`knowledge` 自持 reader/splitter 适配与检索编排；向量表由 Flyway 拥有，**不**使用 pgvector 的自动建表（`initialize-schema=false`），因为结构必须可追溯（宪法原则 IV）。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| 自定义 `EmbeddingModel`（仅测试作用域） | 真实嵌入模型要么需要密钥、要么需要下载 GB 级模型，二者都无法在 CI 中重复执行 | 用真实模型：测试不可重复、有网络依赖，且「维度一致性」这类断言会被外部因素掩盖 |
| 启动期读 `pg_attribute` 解析列维度 | 维度不一致是本阶段最容易踩且最难排查的坑（pgvector 只在写入时才报错，且报错信息不含配置项） | 只在写入时报错：错误发生得太晚，用户已经完成上传，且原始 SQL 报错无法指向「该改配置还是该改迁移」 |
