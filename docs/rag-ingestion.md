# 知识摄入、切块与索引

本文解释资料如何成为可检索片段。阅读前可先看[RAG 总览](rag.md)。Java/Go 的共享算法以当前实现为准，语言差异在文末明确列出。

## 1. 入口与解析

| 输入 | 处理方式 | 限制 |
| --- | --- | --- |
| `.md/.markdown/.txt` | 严格 UTF-8 解码，保留文本结构 | 无效编码拒绝，不猜编码 |
| `.pdf` | Java PDFBox；Go Poppler `pdfinfo/pdftotext` | 只读文本层；加密、损坏、超过 500 页或无可用文字时拒绝；不含扫描 PDF OCR |
| `.docx` | 按正文顺序提取段落与表格单元格 | 不支持旧 `.doc`；不能还原完整页面版式 |
| `.png/.jpg/.jpeg/.webp/.gif` | 视觉模型转录后进入同一文本链路 | 需要可读图的聊天模型；这是模型转录，不是确定性 OCR |
| 公开网页采集 | 抓取公开正文并直接排入索引任务，保留正文版本和哈希 | 公开网页有抓取边界；不执行网页指令，不绕过登录/私网限制 |

服务端校验文件名、扩展名、空文件和大小。默认 `AI_KNOWLEDGE_MAX_FILE_SIZE=5242880`，解析文本总长小于 `AI_KNOWLEDGE_MIN_CHUNK_SIZE_CHARS=350` 时拒收。因此一个合法的短文件也可能无法入库。

知识文件摄入和聊天附件是不同入口：聊天附件供当前对话使用，上传附件本身不自动建知识索引；知识采集需要显式提交。原始知识文件不作为可下载原件长期保留，任务输入字节只服务任务执行/清理周期；需要保存原件时自行备份。

## 2. 结构切块算法

当前不是固定 token 的 `TokenTextSplitter`，也没有使用“语义相似度聚类切块”。实际步骤如下：

1. **标题分节**：识别行首 `#` 到 `######` 的 Markdown 标题，维护六级标题路径。代码围栏内部的 `#` 不当标题。没有标题的 PDF/DOCX 文本退化为一节。
2. **段落累积**：短节直接成块；长节按空行分段，逐段累积，超过目标长度前输出已有块。
3. **长段兜底**：单段超过目标时按字符切。在目标位置向前约 30% 范围内优先寻找句末标点/换行，再找空白，最后硬切。
4. **可选重叠**：同节拆出的相邻块可把前一块尾部接到下一块头部；默认 0。它不是整个文档的滑动 token 窗口，也不在所有标题之间自动重叠。
5. **短块合并**：当前块不足最小字符数时，尝试并入前一块，保留前一块的标题路径；合并后正文不能超过目标长度的 1.5 倍。首块或不满足上限的短块仍可能保留。
6. **最后加标题前缀**：每个合并后的块只加一次 `【章节：一级 > 二级】`，路径最多保留约 120 字符后加省略号。
7. **连续编号**：对最终分块重新写 `chunk_index=0..n-1` 并附上文档元数据。

默认参数：

| 参数 | 默认 | 含义 |
| --- | --- | --- |
| `AI_KNOWLEDGE_CHUNK_SIZE_CHARS` | 1200 | 正文切分目标，不是最终长度硬上限 |
| `AI_KNOWLEDGE_MIN_CHUNK_SIZE_CHARS` | 350 | 短块合并阈值，也是整篇解析后的拒收阈值 |
| `AI_KNOWLEDGE_CHUNK_OVERLAP_CHARS` | 0 | 同节相邻块尾部重叠 |

合并、重叠和章节前缀都会使输出超过 1200 字符；不要按“最大 1200”估算嵌入请求。代码围栏只参与标题识别保护，超长代码段仍可能被字符兜底切开；当前切分器不是完整 Markdown AST 解析器，也没有 PDF 页码/坐标级引用。

示例：

```text
原文：# 报销制度 → ## 试用期 → 若干段落
块0：【章节：报销制度 > 试用期】
## 试用期
……
元数据：document_id / document_title / filename / chunk_index=0
```

## 3. 嵌入与存储

知识分块保存于 `vector_store`，向量列固定为 `vector(1024)`，余弦索引与查询距离类型配套。启动检查表结构与配置维度；实际模型输出维度还要在写入时验证。配置写 1024 不会把任何模型自动变成 1024 维。

OpenAI 文档嵌入遵循当前 Spring AI EMBED 格式，把标题、块序号、文件名、文档 ID 与内容组合；Go 用固定 Java oracle 对齐格式。Ollama 文档路径与普通查询字符串保持各自实现语义。不能只比较向量维数就假设两个模型/格式的向量可混用。

Go 将文档嵌入按最多 16 块分批，所有批次通过数量、索引、维度与数值校验后才进入写事务。Java 异步索引、替换和数据导入使用 `BoundedEmbeddingBatchingStrategy`：先按 Spring AI token 预算切批，再将每批限制为最多 16 块，保留顺序与原 token 边界。同步新增仍走 `vectorStore.add` 的提供商路径，不能据此假定所有 Java 入口都已采用同一条数上限。

更换嵌入模型、格式或切块参数时，已有向量不会自动重建。即使维度相同也可能不可比；应准备备份、隔离重建和质量回归。维度变更还需要追加迁移与重建，不能只改环境变量或旧迁移。

## 4. 异步索引任务

工作台使用 `POST /api/v1/knowledge/jobs` 提交 multipart 文件；可选 `documentId` 指定替换目标，`title` 指定标题。返回 `202 + ApiResponse<Job>`，任务状态为 `PENDING/RUNNING/SUCCEEDED/FAILED`。

```mermaid
sequenceDiagram
    participant U as 工作台/API
    participant DB as PostgreSQL
    participant W as index worker
    participant M as 嵌入模型
    U->>DB: 校验幂等键/容量，保存任务及输入
    DB-->>U: 任务ID，202
    W->>DB: 原子领取、租约/文档锁
    W->>M: 事务外解析后的分块嵌入
    M-->>W: 完整向量批次
    W->>DB: 短事务核验租约/文档所有权，替换索引并完成任务
    U->>DB: 查询进度/最终文档
```

Java 提交端同键同摘要回放、不同内容拒绝，待执行容量上限 20；index 角色单进程通常处理一个，combined 最多两个并行索引。索引尝试至多 3 次，可重试错误与参数/不可读文档等终态错误分开。Go 对应实现和边界由自身测试核对，不能把角色数当成全局总并发。

租约定期续租；Worker 崩溃后可由数据库任务恢复。提交时失去租约的旧 Worker 不能写新结果。替换以同一文档 ID 更新元数据、删除旧向量、插入新向量并记录成功；失败不能提前删除旧代。

RabbitMQ 仅发唤醒通知；broker 故障时数据库轮询仍可领取任务。`GET /knowledge/jobs/{id}` 查看当前进度，`GET /knowledge/jobs` 返回最近任务。成功 `result` 是文档 DTO 的 JSON 文本，失败 `error` 是面向用户的通用信息。

## 5. 同步入口与版本差异

原 `POST /knowledge/documents` 和 `PUT /knowledge/documents/{id}` 继续存在。

- **Java 同步新增**：解析在事务外；短事务先建元数据行；`vectorStore.add` 写向量；成功后短事务更新计数；失败执行补偿清理。这不是“元数据与所有向量全程同一事务”。
- **Go 同步新增**：先准备解析/全部嵌入，再在短事务持久化文档与向量。
- **异步索引**：两端均采用准备后提交、任务租约和短事务，不能据此抹掉旧同步路径差异。
- **PDF**：Java 使用库内解析与页数/内存约束；Go 使用外部 Poppler 和进程超时。部署 Go 时必须安装所需程序。
- **字符计数**：Java 大量路径按 UTF-16 长度，Go 切分辅助函数兼容该口径；补充平面 Unicode 等边界应以双端测试为准，不能把字符目标等同于 token 数。

## 6. 后续投影与资料审查

提交后的数据库触发链维护来源、版本代数、搜索 Outbox 和审查任务。`delivery` 推进 OpenSearch 投影，`review` 进行资料冲突候选核验；过期版本不能覆盖新版本。资料可以处于待审或审查不可用状态，不意味着它已被事实认证。

网页采集直接创建版本并排入索引，成功时推进当前版本，失败保留先前成功文档；没有单独的草稿审批步骤。来源空间、范围、有效期与人工冲突决定参与后续检索精审，见[证据治理](rag-retrieval.md)。

## 源码入口

| 步骤 | Java | Go |
| --- | --- | --- |
| 解析 | [reader](../backend/src/main/java/com/personalos/knowledge/reader) | [reader.go](../backend-go/internal/business/knowledge/reader.go) |
| 切块 | [MarkdownStructureTextSplitter](../backend/src/main/java/com/personalos/knowledge/splitter/MarkdownStructureTextSplitter.java) | [splitter.go](../backend-go/internal/business/knowledge/splitter.go) |
| 同步存储 | [KnowledgeDocumentService](../backend/src/main/java/com/personalos/knowledge/service/KnowledgeDocumentService.java) | [store.go](../backend-go/internal/business/knowledge/store.go) |
| 异步索引 | [KnowledgeIndexJobs](../backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeIndexJobs.java) | [jobs.go](../backend-go/internal/business/knowledge/jobs.go) |
| 采集 | [KnowledgeCaptureService](../backend/src/main/java/com/personalos/knowledge/capture/KnowledgeCaptureService.java) | [captures.go](../backend-go/internal/business/knowledge/captures.go)、[web_capture.go](../backend-go/internal/business/knowledge/web_capture.go) |
