---

description: "Task list for 阶段 3 — 个人知识库与 RAG"
---

# Tasks: 阶段 3 — 个人知识库与 RAG

**Input**: Design documents from `/specs/004-knowledge-rag/`

---

## Phase 1: Setup

- [x] T001 `pom.xml` 增加 `spring-ai-starter-vector-store-pgvector`（另一种拼写 `vectorstore` 实测 404）
- [x] T002 `application.yml`：`spring.ai.model.embedding=ollama`、`spring.ai.ollama.embedding.model=bge-m3`、`spring.ai.vectorstore.pgvector.*`、multipart 上限
- [x] T003 `.env.example` 增加嵌入模型与知识库相关变量

## Phase 2: Foundational

- [x] T004 `V4__create_knowledge_tables.sql`（`knowledge_document` + `vector_store vector(1024)` + HNSW `vector_cosine_ops`）
- [x] T005 [P] `ErrorCode` 增加 `UNSUPPORTED_DOCUMENT(40003)` / `EMBEDDING_DIMENSION_MISMATCH(50201)`
- [x] T006 [P] `KnowledgeDocumentEntity` + `KnowledgeDocumentRepository`
- [x] T007 `KnowledgeProperties`（维度 / topK / 阈值 / 上限 / 分块参数）
- [x] T008 `VectorStoreInspector`（向量表 SQL 的唯一入口）+ `VectorDimensionValidator`（启动期校验，失败即启动失败）
- [x] T009 [P] `knowledge/dto/*`（文档 / 命中 / 引用 / 检索与问答请求响应）

## Phase 3: 入库

- [x] T010 `KnowledgeDocumentReader`（实现 `DocumentReader`，内部用 `TextReader`；严格 UTF-8 校验 + 去 BOM + 附加元数据）
- [x] T011 `KnowledgeDocumentService.ingest`：校验 → 解析 → 分块（重编号 chunk_index）→ `vectorStore.add` → 回填 chunk_count
- [x] T012 写入异常翻译：维度 → `50201`（含「维度」字样）；连不上模型 → `50200`；失败零写入
- [x] T013 `POST /api/v1/knowledge/documents`（multipart）

## Phase 4: 检索与问答

- [x] T014 `KnowledgeRetrievalService`（`SearchRequest` + `similaritySearch`，阈值显式给）
- [x] T015 `KnowledgeQaService`：编号注入 → 生成 → 服务端组装引用；无命中**不调用模型**
- [x] T016 `POST /search` 与 `POST /ask`

## Phase 5: 删除与清理

- [x] T017 `delete`：**先删向量、再删记录** + 残留自检（不为 0 则整体回滚）
- [x] T018 `DELETE /documents/{id}` 返回 `deletedChunks`
- [x] T019 `GET /documents`

## Phase 6: Agent 工具

- [x] T020 `KnowledgeTools`：`@Tool searchKnowledge`（只读；空结果返回 `OK` 而非 `REJECTED`）
- [x] T021 `KnowledgeToolContributor`（复用阶段 2 SPI，`chat` 零改动）

## Phase 7: 前端

- [x] T022 [P] `api/knowledge.ts`、`stores/knowledge.ts`、类型扩展
- [x] T023 `views/KnowledgeView.vue`（上传 / 列表 / 检索 / 问答 + 引用卡片）
- [x] T024 路由 `/knowledge` + 导航；`type-check` + `build` 通过

## Phase 8: 验收与交付

- [x] T025 集成测试 9 个 RAG 用例（另加维度守卫 1 个），`./mvnw -B verify` → **87 测试全绿**
- [x] T026 真机 REST 冒烟（边界校验 + 零写入核对）
- [x] T027 真机维度不一致 → 启动失败实测（退出码 1，错误含「维度」与修复路径）
- [x] T028 回填 `quickstart.md`；更新 README / architecture；更新 memory 并提交

---

## 实现偏差记录

| 编号 | 偏差 | 原因 | 已同步文档 |
|---|---|---|---|
| K1-D1 | 把 `ToolExecutor` / `ToolExecutionResult` 从 `agent` 移到 `common/ai/tool` | `knowledge` 也要实现工具；留在 `agent` 会让 `knowledge` 反向依赖 `agent`，方向颠倒 | 本文件 |
| K2-D2 | `AiChatClientProvider` 新增 `requirePlainChatClient()` | 知识库问答**不能**复用对话客户端：`MessageChatMemoryAdvisor` 强制要求每次请求带 `conversationId`，单轮问答直接调用会抛参数异常（阶段 1 已实测过这个错误） | 类注释 |
| K3-D3 | multipart 上限设为 6MB（> 应用层 5 MiB），并新增 `MaxUploadSizeExceededException` 处理器 | 若容器上限小于应用上限，超限文件在进入控制器前就被拒，用户看到的是 500 而不是「文件过大」 | application.yml 注释 |
| K4-D4 | 前端移除 axios 实例的默认 `Content-Type` | 硬编码 `application/json` 会让 FormData 上传带上**缺少 boundary** 的 Content-Type，服务端直接解析失败 | http.ts 注释 |
| K5-D5 | 启动期只校验「表 ↔ 配置」，**不**调用 `EmbeddingModel.dimensions()` | 后者的默认实现会真的发起一次嵌入调用；Ollama 未启动时会抛异常，破坏「无模型也能启动」（FR-016） | research R-03 |
| K6-D6 | 删除顺序「先向量、后记录」+ 残留自检 | 反过来一旦失败，残留向量将**永远无法按 document_id 定位清理**；当前顺序最坏情况是可重试的空文档 | data-model.md |
| K7-D7 | 修改阶段 2 的断言：`containsExactlyInAnyOrder(8 个任务工具)` → `contains(任务工具)` + `noneMatch(confirm/approve)` | 阶段 3 合法地新增了 `searchKnowledge`，原断言把「新增工具」误报成回归。真正要守的不变量是「没有确认类工具」，不是「恰好 8 个」 | 测试注释 |
| K8-D8 | 不保存原文，只保存分块 | 本阶段无「原文下载/重切分」需求；保存原文会引入存储与隐私面。代价：换切分策略需重新上传 | data-model.md 偏差 D-01 |
| K9-D9 | `VectorStoreInspector` 集中持有向量表的 SQL，表名/schema 直接读 pgvector 配置 | 避免「配置改了表名、SQL 还指着旧表」。该组件同时提供 SC-002/SC-003 断言所需的计数能力 | 类注释 |
| K10-D10 | 嵌入模型替身用**词袋**而非哈希随机向量 | 随机向量的相似度没有语义，「第一条命中来自正确文档」这类断言会失去意义；词袋让词面重叠体现为相似度，断言才有意义且完全确定 | research R-07 |

### 集成测试替身的边界（必须明确）

测试替换的是**嵌入模型**，不替换向量库：`PgVectorStore` 与 pgvector 表都是真实的。
因此「维度一致」「删除后零残留」验证的是**真实链路**。
但词袋模型没有语义泛化能力，所以**检索效果**无法自动化验证 —— 已列为人工项（quickstart 场景 D）。

## 推荐提交

```
feat(knowledge): 阶段 3 个人知识库与 RAG（T001-T028）

- knowledge 模块：上传 Markdown/TXT → DocumentReader 解析 → 分块 → 嵌入 → PgVectorStore
- 向量表由 Flyway 创建（vector(1024) + HNSW vector_cosine_ops），关闭框架自动建表
- 三重维度保障：启动期校验「表↔配置」（不一致直接启动失败）、写入期翻译「模型↔表」错误、失败零写入
- 检索（纯向量 + 阈值）与问答（编号注入 + 服务端组装引用）分离，引用片段可逐条溯源
- 删除文档时先清向量再删记录，且自检残留（不为 0 则整体回滚）
- 检索作为只读 Agent 工具接入对话，复用阶段 2 的 ToolContributor SPI
- 前端知识库页：上传 / 列表 / 检索 / 问答 + 引用卡片
- 测试：87 例全绿，其中 10 个 RAG 用例全程无网络无密钥（确定性词袋嵌入 + 真实 pgvector）
```
