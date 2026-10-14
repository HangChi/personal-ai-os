# Research: 阶段 3 — 个人知识库与 RAG

> 所有 Spring AI 2.0.1 的 API 结论均通过**解包 Maven Central 的 jar 并用 `javap` 核实**得出。

## R-01 RAG 相关类的真实归属（避免引错模块）

**Decision**：只依赖已有 starter 的传递依赖，不新增 Maven 坐标。

**核实结果**（`javap` / `unzip -l` 实测）：

| 类 | 所在 jar |
|---|---|
| `Document` / `DocumentReader` / `TextReader` / `TokenTextSplitter` | `spring-ai-commons` |
| `EmbeddingModel` | `spring-ai-model` |
| `VectorStore` / `VectorStoreRetriever` / `SearchRequest` | `spring-ai-vector-store` |
| `PgVectorStore` | `spring-ai-pgvector-store`（由新的 starter 引入） |
| `PgVectorStoreAutoConfiguration` | `spring-ai-autoconfigure-vector-store-pgvector` |

**要点**：`DocumentReader` 是一个 `Supplier<List<Document>>`，提供 `read()` 默认方法；
`TextReader` 接收 `String` 或 `Resource`。**没有**独立的 Markdown reader 模块（2.0 核心不含），
所以本项目用 `TextReader` 读取文本，再自行附加元数据 —— 语义上 Markdown 就是纯文本。

**需要新增的依赖**：`org.springframework.ai:spring-ai-starter-vector-store-pgvector`（版本由 BOM 管理）。

## R-02 pgvector 期望的表结构，以及为什么必须由 Flyway 建表

**Decision**：关闭框架自动建表，由 Flyway 创建 `vector_store`，列名与类型严格对齐框架期望。

**核实结果**（从 `PgVectorStore` 的字节码常量中提取的 DDL 片段）：

```
id,  content text,  metadata json,  embedding vector(%d)
```

默认值：`DEFAULT_TABLE_NAME = "vector_store"`、`DEFAULT_SCHEMA_NAME = "public"`、
`DEFAULT_VECTOR_INDEX_NAME = "spring_ai_vector_index"`、`DEFAULT_SCHEMA_VALIDATION = false`。

配置前缀 `spring.ai.vectorstore.pgvector`，属性含 `dimensions` / `index-type` / `distance-type` /
`table-name` / `schema-name` / `id-type` / `max-document-batch-size` / `schema-validation` /
`remove-existing-vector-store-table`。

**为什么不用框架自动建表**（`initialize-schema=true`）：
1. 宪法原则 IV 要求所有结构变更可追溯；自动建表会绕过 Flyway，`flyway_schema_history` 里看不到这张表。
2. 自动建表会带上 `remove-existing-vector-store-table` 这类开关，一旦误开就是**静默删表**。
3. 维度必须在迁移里写死，才能被启动期校验读取。

**风险与对策**：列名/类型与框架期望不一致时，默认的 `schema-validation=false` **不会**替我们发现，
只会在运行期报 SQL 错。故本项目用 `DEFAULT_VECTOR_INDEX_NAME` 与列名逐一对照迁移脚本（见 data-model.md）。

## R-03 维度一致性：三重校验的具体做法

**Decision**：
1. **启动期**读数据库真实列维度并与配置比对，不一致则启动失败。
2. **写入前**守卫（启动已保证表与配置一致）。
3. **写入异常**翻译为可读错误，且不产生部分写入。

**如何读到列的真实维度**（PostgreSQL 专用但稳定）：

```sql
SELECT format_type(a.atttypid, a.atttypmod)
FROM pg_attribute a
JOIN pg_class c ON c.oid = a.attrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relname = :table AND n.nspname = :schema AND a.attname = 'embedding'
```

返回形如 `vector(1024)`，解析括号内数字即可。**不依赖** pgvector 的扩展函数，
因此在扩展版本变化时仍然可用。

**为什么不调用 `EmbeddingModel.dimensions()` 做启动校验**：
其默认实现会**真的执行一次嵌入调用**来探测维度。Ollama 未启动时这会抛异常，
从而破坏阶段 0 起就承诺的「无模型也能启动」（FR-016）。
故启动期只比对**配置 ↔ 表**（纯本地），**模型 ↔ 配置**的校验推迟到实际写入时。

## R-04 分块策略

**Decision**：`TokenTextSplitter`，默认参数（`chunkSize=800` tokens、`minChunkSizeChars=350`、
`minChunkLengthToEmbed=5`、`maxNumChunks=10000`），保持可配置。

**Rationale**：
- 按 **token** 切分而非按字符，避免「按字符切」在中文语境下切出语义碎片。
- 它自带 `minChunkSizeChars` 与 `minChunkLengthToEmbed` 兜底，能处理**没有换行的超长文本**这一 Edge Case
  （纯按段落切分会在这种输入上产出单个超大块）。
- 中文兼容性由 tokenizer 保证：UTF-8 多字节字符不会被截断。

**Alternatives considered**：
- 自写按标题/段落递归切分 → Markdown 结构感知更好，但要处理代码块、表格等边界，成本远高于本阶段收益 → 否决，记为后续优化项。
- 固定字符窗口 + 重叠 → 简单但会切断句子，且重叠窗口会放大向量条数 → 否决。

## R-05 检索与阈值的语义

**Decision**：`SearchRequest.builder().query(q).topK(k).similarityThreshold(t).build()`，
`distance-type: COSINE_DISTANCE`，默认 `topK=4`、`threshold=0.3`。

**Rationale**：
- **必须显式给阈值**：Spring AI 的默认阈值会过滤掉大量结果；不设阈值则会把毫不相关的内容塞进提示词，
  导致模型「拿着无关材料硬答」。阈值是可配置项，便于按知识库语料调整。
- 与 `COSINE_DISTANCE` 配套的索引 opclass 是 `vector_cosine_ops`。**opclass 与距离类型不匹配不会报错**，
  只会让索引失效、查询退化为全表扫描 —— 症状是「慢」而不是「错」，属于典型的隐性退化，故在迁移里成对写死。

**Alternatives considered**：混合检索（向量 + 关键词）与重排序 —— 用户已确认本阶段只做纯向量，记为后续阶段。

## R-06 引用来源为什么必须由服务端产出

**Decision**：引用列表（文档标识、标题、片段序号、片段内容、相似度）由**检索结果的元数据**直接构造，
模型只负责在正文里用 `[n]` 标注编号。

**Rationale**：
- 让模型「自己说出引用」等于把溯源交给一个会编造的东西 —— 引用一旦不可信，整个 RAG 就不可信。
- 服务端构造的引用天然与「实际进入上下文的片段」一一对应，可用测试逐条断言（SC-005）。
- 提示词中给每个片段编号 `[1] [2] ...` 并附标题，模型只需照抄编号，成本极低。

**Alternatives considered**：让模型输出 JSON（含引用字段）→ 需要结构化输出与解析容错，且模型仍可能填错来源 → 否决。

## R-07 测试策略：确定性词袋嵌入模型

**Decision**：测试用自实现的 `DeterministicEmbeddingModel`（1024 维，词袋 + L2 归一化），
配合**真实**的 `PgVectorStore` 与 Testcontainers PostgreSQL。

**Rationale**：
- 真实嵌入模型要么需要 API Key、要么需要下载 GB 级模型，二者都无法在 CI 里重复执行（SC-008）。
- 哈希随机向量**不行**：它的余弦相似度没有语义，检索结果近乎随机，
  无法断言「第一条结果来自正确文档」（SC-004）。
- 词袋向量能让**词面重叠**直接体现为相似度：这样「检索命中正确文档」这类断言才有意义，
  且完全确定（同样的输入永远得到同样的向量）。
- 关键在于**只替换模型，不替换向量库**：真实 `PgVectorStore` 会真的写出 `vector(1024)` 数据，
  所以「维度一致性」「删除后零残留」这些断言验证的是**真实链路**，而不是替身。

**代价（已记录）**：词袋模型没有语义泛化能力（同义词、改写检索不到）。
因此本阶段的检索质量只能验证「链路正确」，**不能**验证「效果好坏」——
后者需要真实嵌入模型，属于人工验证项（见 quickstart 场景 D）。
