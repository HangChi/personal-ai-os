# Feature Specification: 阶段 12 — 数据导出

**Feature Branch**: `012-data-export`

**Created**: 2026-10-25

**Status**: Draft

**Input**: 用户需求：「帮我逐个完成」的第 3 项 —— 全部数据只有 PostgreSQL 一份副本，
没有任何导出入口，删库即全没。

## 背景

这个系统的全部用户资产（任务、日程、记忆、知识库、对话历史）都只存在于一个
Docker volume 里的 PostgreSQL 中。没有导出、没有备份、没有迁移路径。

这个风险与「有没有 bug」不同：**它不需要出任何错就能造成完全损失** ——
`docker compose down -v`、一次误删 volume、一次磁盘故障，资产就没了。
而对于自托管产品，「我的数据能带走」本身也是用户信任的一部分。

本阶段提供**只读导出**：一次请求，得到一个包含全部业务数据的 JSON 文件。

### 一个必须如实说明的边界

`knowledge_document` 表**不保存上传的原始文件**（V4 迁移的原话：「原文不落库，
只保留分块后的片段」）。所以知识库能导出的是：

- 文档元数据（标题、文件名、类型、大小、字符数、分块数、上传时间）
- 每个分块（chunk）的**文本内容**

**不能**导出原始 PDF/docx/图片本身。用这些分块重建索引是可行的，
但要拿回「原始文件」只能重新上传。这个限制必须写在界面上，不能让人以为导出了全部。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 一键导出全部数据（Priority: P1）

作为用户，我希望在界面上点一下就能下载一个 JSON 文件，里面有我所有的任务、日程、
记忆、知识库文档与对话历史。

**Why this priority**：这是本阶段的唯一核心。没有它，其余都是零。

**Independent Test**：库里造几条各模块数据 → 点击导出 → 下载的文件能被
`python3 -m json.tool` 解析，且各数组条数与库中 `count(*)` 一致。

**Acceptance Scenarios**：

1. **Given** 各模块都有数据，**When** 请求导出，**Then** 返回 200、
   `Content-Type: application/json`、
   `Content-Disposition: attachment; filename="personal-ai-os-export-<时间戳>.json"`。
2. **Given** 导出文件，**When** 解析，**Then** 顶层含 `exportedAt` / `version` / `application` / `counts`，
   以及 `sessions` / `tasks` / `calendarEvents` / `memories` / `knowledgeDocuments` 五个数组。
3. **Given** 库里有 8 个任务，**When** 导出，**Then** `counts.tasks == 8` 且 `tasks` 数组长度也是 8。
4. **Given** 空库，**When** 导出，**Then** 仍然是合法 JSON（各数组为 `[]`、counts 为 0），不是 404/500。

---

### User Story 2 - 知识库内容可审计（Priority: P2）

作为用户，我希望导出的知识库部分能让我确认「每个文档到底存了什么内容」，
即使原始文件已经丢了。

**Why this priority**：知识库是唯一「原文不落库」的模块，导出是它内容可追溯的最后机会。

**Independent Test**：上传一个 md，导出后该文档的 `chunks` 数组非空，
且拼接后包含原文的关键句。

**Acceptance Scenarios**：

1. **Given** 一个已入库的文档，**When** 导出，**Then** 该文档含元数据字段与 `chunks`（按分块顺序）。
2. **Given** 一个没有任何分块的文档（异常数据），**When** 导出，**Then** `chunks` 为 `[]`，
   导出仍然成功（不因单条异常数据整包失败）。

---

### User Story 3 - 失败要说清楚（Priority: P3）

作为用户，当导出失败时我要看到可读原因，而不是一个坏掉的文件。

**Independent Test**：后端不可达时点导出，界面提示错误且不产生 0 字节文件。

**Acceptance Scenarios**：

1. **Given** 后端返回错误，**When** 点击导出，**Then** 界面显示可读错误，**不**触发下载。

## Edge Cases

| 输入 | 期望行为 |
|---|---|
| 空库 | 合法 JSON，各数组为空，`counts` 全 0 |
| 文档有分块但 `chunk_count` 与实际片段数不符 | 以**实际片段**为准导出，并在 `counts` 中如实反映总量 |
| 数据量较大（万级消息） | 一次性加载到内存可接受（个人量级）；不做流式 |
| 导出过程中数据被并发修改 | 不加全局锁；导出是快照语义尽力而为，单表内的读一致性由事务提供 |
| 客户端中断下载 | 服务端不残留临时文件（不落临时文件，直接写响应） |

## Requirements *(mandatory)*

- **FR-001**：`GET /api/v1/export` MUST 直接返回 JSON 文件（`Content-Disposition: attachment`），
  **MUST NOT** 包在 `ApiResponse` 信封里 —— 文件就是文件，外加一层 `{code,data}` 只会让
  下游解析多一步。
- **FR-002**：导出失败 MUST 仍走统一错误信封（`code != 0`），MUST NOT 返回半个合法文件。
- **FR-003**：导出 MUST 包含 `sessions`（含各自 message 列表）、`tasks`、`calendarEvents`、
  `memories`、`knowledgeDocuments`（含 chunks）。
- **FR-004**：导出 MUST NOT 包含向量（可由分块重建）、提醒（由日程派生）、
  待确认事项（短期状态）、审计记录（运维数据而非用户资产）。
- **FR-005**：顶层 MUST 含 `exportedAt`、`version`、`application`、`counts`。
- **FR-006**：新增业务模块 MUST 只需实现 `ExportContributor` 即可被导出包含，
  MUST NOT 需要改动导出服务本身。
- **FR-007**：前端 MUST 在首页提供导出入口；失败时 MUST NOT 产生空文件。
- **FR-008**：界面上 MUST 明确说明「知识库导出的是分块文本，不含原始文件」。
- **FR-009**：本阶段 MUST NOT 实现导入 —— 导入涉及主键冲突、外键顺序、幂等与合并策略，
  是另一个量级的问题，塞进本阶段只会两边都做不好（见 Assumptions）。

## Success Criteria

- **SC-001**：`./mvnw -B verify` 全绿；新增集成测试覆盖「各模块计数一致」「空库合法」
  「知识库含 chunks」三条。
- **SC-002**：`npm run test:logic` 全绿，覆盖下载成功与失败两条路径。
- **SC-003**：真机点击导出，下载文件能被 `python3 -m json.tool` 解析，
  且 `counts.tasks` 与 `curl /api/v1/tasks` 的 `total` 一致。
- **SC-004**：导出响应中不含任何向量数据（文件体积应远小于「含 1024 维向量」的量级）。

## Assumptions

- **只导出、不导入**。导入的难点不在搬运数据，而在「目标库已有数据时怎么办」：
  按 id 覆盖？按内容合并？冲突了几条字段怎么选？这些策略一旦做错就是静默数据损坏。
  先把「能带走」做对，导入留作独立阶段。
- 不做导出历史/定时备份。那是运维能力，不属于应用层；用户拿到文件后放到哪里由他决定。
- 导出不加密、不加密码：文件落在用户自己的机器上，加一层自制加密只会制造
  「以为安全」的错觉（真正的保护是磁盘加密与访问控制）。
