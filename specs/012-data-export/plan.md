# Implementation Plan: 阶段 12 — 数据导出

**Branch**: `012-data-export` | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## Summary

`GET /api/v1/export` 一次返回包含全部业务数据的 JSON 文件。各业务模块通过
`ExportContributor` SPI 提供自己的快照，导出服务只负责组装与计数 ——
新增模块不需要改导出代码。

**零新依赖、零迁移、零新表、零写路径。**

## Technical Context

- **语言/框架**：Java 21，Spring Boot 4.1.1，Spring Web MVC，Spring Data JPA
- **存储**：PostgreSQL 17；只读，不改结构
- **前端**：Vue 3 + TypeScript + Pinia + Element Plus
- **测试**：JUnit 5 + Testcontainers；前端 `.mjs` store 测试
- **新增依赖**：无
- **规模**：后端 1 个 SPI + 1 个 Service + 1 个 Controller + 5 个 Contributor；
  前端 1 个 api 模块 + 首页一张卡片

## Constitution Check

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先 | ✅ | 零依赖零迁移。**不**做流式导出（个人量级全量内存即可）、**不**做导出历史、**不**做压缩加密（R-05） |
| II. 边界即安全 | ✅ | 只读。导出不包含任何密钥（`MCP_SERVERS_*_ENV_*` 不落库，本就不在导出范围）；响应不落临时文件，避免残留 |
| III. 分层与写路径收敛 | ✅ | 无写操作。Controller 只包响应头，数据由 Service 经各模块 **Service**（非 Repository）取得 |
| IV. 数据变更可追溯 | ✅ | 无结构变更 |
| V. 可验证的交付 | ✅ | 集成测试断言「counts 与数组长度一致」「空库合法」「知识库含 chunks」；真机下载后用 `json.tool` 解析 |
| 工作流：阶段串行 | ✅ | 012 接在 011 之后 |

## Project Structure

**后端**
```
export/ExportContributor.java        新：SPI（key + snapshot）
export/DataExportService.java        新：收集 contributors、组装扁平结构 + counts
export/DataExportController.java     新：GET /api/v1/export，带 Content-Disposition
chat/ChatExportContributor.java      新：会话 + 各自消息（嵌套）
task/TaskExportContributor.java      新
calendar/CalendarExportContributor.java 新
memory/MemoryExportContributor.java  新
knowledge/KnowledgeExportContributor.java 新：文档元数据 + chunks
task/service/TaskService.java        改：加 listAll()
calendar/service/CalendarEventService.java 改：加 listAll()
knowledge/service/VectorStoreInspector.java 改：加 chunksByDocumentId()
common/config/AppProperties.java     已有 name/version，直接复用
```

**前端**
```
src/api/export.ts              新：downloadExport()（blob + 触发下载）
src/views/HomeView.vue         改：加「数据备份」卡片
frontend/test/export-api.test.mjs 新
```

## Phase 0 / Phase 1 结论

### R-01 用 SPI 而不是让导出服务直接依赖五个模块

项目已有两个同构 SPI（`ToolContributor` / `PromptContributor`）。
不这样做的代价很具体：导出服务会 import 全部业务 Service，
将来加第六个模块就要改导出服务，而「改一个已经正确的类」正是回归的来源。

`ExportContributor` 只有两个方法，实现方是各模块自己 —— 它最清楚「本模块导出什么算完整」。

### R-02 复用现有 DTO，不新造导出模型

各模块已有对外 DTO（`TaskDto` / `CalendarEventDto` / `MemoryDto` / `ChatSessionDto` /
`ChatMessageDto` / `KnowledgeDocumentDto`）。再造一套 `TaskExportDto` 的唯一后果是
**两套视图慢慢漂移** —— 加字段时只改一处，导出就静默缺字段。

只有两处需要新结构：会话要嵌套消息、文档要嵌套分块。用模块内的 record 表达。

### R-03 向量不导出，但分块文本必须导出

向量是「分块文本 + 嵌入模型」的派生物，导出它只会让文件膨胀几十倍（1024 个 float/条），
且换模型后完全失效。真正不可再生的是**分块文本**——因为原文不落库（V4 注释明说）。

所以 `VectorStoreInspector` 增加 `chunksByDocumentId()`：这是向量表的第二类查询，
放在「唯一 SQL 入口」里而不是新开一个类。

### R-04 counts 与数组同源

`counts` 由「sections 的实际长度」算出，不是另跑一次 `count(*)`。
两次统计必然有偏差窗口（并发写入），而「文件里说 8 条、实际给了 9 条」会让用户
对整份备份失去信任。

### R-05 不压缩、不加密

- 不压缩：JSON 已可被 `gzip` 处理，服务端再压一层只是把「用户能不能直接读」这个优点弄没。
- 不加密：文件落在用户自己的磁盘上，自制加密只会制造「以为安全」的错觉；
  真正的保护是磁盘加密与访问控制。

### R-06 不做导入

导入的难点不在搬运，而在「目标库已有数据怎么办」：按 id 覆盖？按内容合并？
冲突时哪边优先？每一个选择做错都是静默数据损坏。先把「能带走」做对。

## Complexity Tracking

无违例。SPI 的抽象成本已被两个既有先例摊平。
