# Data Model: 阶段 3 — 个人知识库与 RAG

## 表结构（Flyway `V4`）

### `knowledge_document`

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `uuid` | PK DEFAULT `uuid_generate_v4()` | 同时作为向量元数据中的 `document_id` |
| `title` | `varchar(300)` | NOT NULL | 默认取文件名（不含扩展名） |
| `filename` | `varchar(300)` | NOT NULL | 原始文件名 |
| `content_type` | `varchar(100)` | NOT NULL | `text/markdown` / `text/plain` |
| `size_bytes` | `bigint` | NOT NULL | 原始字节数 |
| `char_count` | `integer` | NOT NULL | 解析后的字符数 |
| `chunk_count` | `integer` | NOT NULL DEFAULT 0 | 分块数；**必须**等于该文档的向量条数（SC-001） |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |
| `updated_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |

### `vector_store`（由 Flyway 创建，字段名与 `PgVectorStore` 期望的完全一致）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `uuid` | PK，由应用侧生成（`PgIdType.UUID`） |
| `content` | `text` | 片段原文 |
| `metadata` | `json` | 片段元数据（见下） |
| `embedding` | `vector(1024)` | **维度写死在迁移里** |

**索引**：`CREATE INDEX ... USING hnsw (embedding vector_cosine_ops)` ——
与配置的 `distance-type: COSINE_DISTANCE` 必须**成对**出现，opclass 与距离类型不匹配会导致索引不被使用（查询仍能跑，但退化为全表扫描，且症状是「慢」而不是「错」）。

**为什么列名/类型要严格对齐框架期望**：`PgVectorStore` 内部按固定列名拼 SQL；
但本项目**关闭**了框架的自动建表（`initialize-schema=false`），
所以这张表由迁移创建 —— 一旦列名或类型写偏，框架的 SQL 会在运行期报错。
理论上框架会用 `schemaValidation` 检查，但其默认值是 `false`，不会替我们发现。

## 片段元数据（存入 `vector_store.metadata`）

| 键 | 说明 |
|---|---|
| `document_id` | 来源文档标识（**删除时按它过滤清理向量**） |
| `document_title` | 来源文档标题（引用展示用，避免展示时再查库） |
| `chunk_index` | 该片段在文档内的序号（从 0 开始） |
| `filename` | 原始文件名（引用中展示更精确的来源） |

**为什么把标题与文件名冗余进元数据**：引用列表要在一次检索后立即返回，
若只存 `document_id`，展示 N 条引用就要多 N 次查询（N+1）。
元数据随向量一起取回，零额外查询。代价是文档改名后需重建索引 —— 本阶段不支持改名，故无影响（记为偏差 D-02）。

## 维度一致性（本阶段的核心不变量）

维度在**三个地方**出现，任何一处不一致都会出问题：

| 位置 | 来源 | 不一致时的症状 |
|---|---|---|
| Flyway 迁移的 `vector(1024)` | 表结构 | 只有写入时才暴露 |
| `app.knowledge.embedding-dimensions` | 应用配置 | 与表不一致 → 写入报原始 SQL 错误 |
| Embedding 模型的输出维度 | 模型本身 | 与表不一致 → 写入报原始 SQL 错误 |

**三重校验**（FR-013 / FR-014）：

1. **启动期**：查询 `pg_attribute` + `format_type` 解析出列的真实维度，与配置比对；
   不一致 → **启动失败**，错误信息包含「维度」并在提示中给出两条修复路径
   （改配置 / 新增迁移重建列）。
2. **写入前**：守卫检查配置维度与表维度已一致（启动已保证），并把写入异常翻译成可读错误。
3. **写入失败时**：捕获 pgvector 抛出的维度错误，翻译为「嵌入模型输出维度与表不一致」，
   并保证**不产生部分写入**（批量写入要么全成要么全不成）。

## 删除的清理顺序

```
① 先删向量（按 metadata.document_id 过滤）  ← 提交点
② 再删文档记录
```

**为什么先删向量**：反过来先删文档记录的话，一旦第 ② 步之后进程崩溃，
残留的向量将**永远无法按 document_id 定位清理**（只剩下一堆指向已不存在文档的向量）。
先删向量则最坏情况是「文档记录还在但向量已空」，用户看到的是一份空文档，可以直接重删 ——
这是**可恢复**的失败，而前者不可恢复。

## 偏差清单

| 编号 | 偏差 | 原因 |
|---|---|---|
| D-01 | 不保存原文，只保存分块 | 阶段 3 无「原文下载/重切分」需求；保存原文会引入存储与隐私面（原则 I / II）。代价：换切分策略需重新上传 |
| D-02 | 元数据里冗余文档标题与文件名 | 避免引用展示时的 N+1 查询；代价是改名需重建索引，而本阶段不支持改名 |
| D-03 | `metadata` 用 `json` 而非 `jsonb` | 与 `PgVectorStore` 内部预期保持一致（框架 DDL 用 `json`）；本项目不按元数据字段做 SQL 查询 |
| D-04 | 未建「文档集合/命名空间」 | 本地单用户单知识库，加集合会产生「看起来支持多租户」的误导 |
