---
description: "Task list for 阶段 12 — 数据导出"
---

# Tasks: 阶段 12 — 数据导出

**Input**: Design documents from `/specs/012-data-export/`

---

## Phase 1: 导出骨架

- [x] T001 [P] `export/ExportContributor`：`String key()` + `List<?> snapshot()`
- [x] T002 `export/DataExportService`：注入 `List<ExportContributor>`，按 key 排序组装；
      `counts` 由**数组实际长度**算出（R-04），不另跑 count(*)
- [x] T003 `export/DataExportController`：`GET /api/v1/export` → `ResponseEntity<Map<String,Object>>`，
      带 `Content-Disposition: attachment; filename="personal-ai-os-export-<ISO>.json"`
- [x] T004 `AppProperties` 的 name/version 复用为顶层 `application` / `version`

## Phase 2: 各模块 Contributor

- [x] T005 `TaskService.listAll()` + `TaskExportContributor`（key `tasks`）
- [x] T006 `CalendarEventService.listAll()` + `CalendarExportContributor`（key `calendarEvents`）
- [x] T007 `MemoryExportContributor`（key `memories`，直接用 `MemoryService.list()`）
- [x] T008 `ChatExportContributor`（key `sessions`，会话内嵌套 `messages`）
- [x] T009 `VectorStoreInspector.chunksByDocumentId(UUID)`：按 `metadata->>'chunk_index'` 排序取 `content`
- [x] T010 `KnowledgeExportContributor`（key `knowledgeDocuments`，文档元数据 + `chunks` 文本）

## Phase 3: 测试

- [x] T011 `DataExportIntegrationTest`：
  - 各模块造数后 `counts.X == 数组长度`（三处对齐：counts / 数组 / 库）
  - 空库 → 合法 JSON，counts 全 0
  - 知识库文档含非空 `chunks`，内容包含原文关键句
  - 响应头含 `Content-Disposition: attachment`
  - **响应体中不含 `embedding` 字段**（守住 R-03）
- [x] T012 `./mvnw -B verify` 全绿

## Phase 4: 前端

- [x] T013 `src/api/export.ts`：`downloadExport()` 用 `responseType: 'blob'`，
      成功后创建 ObjectURL 触发下载，失败抛可读错误
- [x] T014 `HomeView` 加「数据备份」卡片：说明 + 导出按钮 + 失败提示 +
      **明确写出「知识库导出的是分块文本，不含原始文件」**
- [x] T015 `frontend/test/export-api.test.mjs`：下载成功触发、失败不产生文件
- [x] T016 `type-check` / `build` / `test:logic` 全绿

## Phase 5: 验证与文档

- [x] T017 真机点导出，用 `python3 -m json.tool` 解析下载文件
- [x] T018 `counts.tasks` 与 `/api/v1/tasks?size=1` 的 `total` 一致
- [x] T019 README 路线图加阶段 12
- [x] T020 提交：`docs(export): 阶段 12 规格` + `feat(export): 阶段 12 实现`

## 完成记录

**提交**：`d184a8f` feat(export): 阶段 12 数据导出（T001-T019）

**测试**
- 后端 `./mvnw -B verify`：**231** 通过（原 226 + 新增 5）
  - `DataExportIntegrationTest`：空库合法 / 三方计数一致（counts↔数组↔库）/
    知识库含分块文本 / `Content-Disposition` 附件头 / 响应不含 `embedding`
- 前端 `npm run test:logic`：**155** 断言通过（原 146 + 新增 9，文件名解析与生成）；
  `type-check` 与 `build` 通过

**真机验证（Playwright，真实下载 + 真实 pgvector）**
- 首页出现「数据备份」卡片，蓝色提示「知识库导出的是分块文本，不含原始文件」
- 点击导出 → 下载 `personal-ai-os-export-20261025-120518.json`（9732 字节）
- 顶层键：`application, version, formatVersion, exportedAt, counts, calendarEvents,
  knowledgeDocuments, memories, sessions, tasks`
- `counts = {calendarEvents:6, knowledgeDocuments:3, memories:2, sessions:10, tasks:5}`
  与各数组长度完全一致
- `tasks=5` 同时等于 `/api/v1/tasks` 的 `total` 与库中 `count(*)` —— 三方对齐
- 响应体不含 `"embedding"`；页面 0 错误

**文件体积说明了「不导向量」的收益**：10 个会话 + 3 个知识库文档（含全部分块文本）
总共 9.7 KB。若带上向量，仅 3 个文档的分块就会是几百 KB 量级。

**踩坑**
- 任务导出若复用 `search()` 会受 `MAX_SIZE=100` 约束而**静默少导** ——
  这类「有上限的分页接口被当成全量接口用」的错误不会报错，只会在数据变多后悄悄发生。
  已改为独立 `listAll()`。
- `(metadata->>'chunk_index')::int` 遇到一条脏数据会让**整条查询**报错，
  而不是跳过那一行。排序里先用正则筛纯数字再转换。
