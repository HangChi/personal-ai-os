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

（实现后回填）
