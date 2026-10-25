---
description: "Task list for 阶段 16 — 列表分页与站内搜索"
---

# Tasks: 阶段 16 — 列表分页与站内搜索

**Input**: Design documents from `/specs/016-list-and-search/`

---

## Phase 1: 会话

- [ ] T001 [P] `chat/dto/ChatSessionQuery`：`keyword` / `page` / `size`（收敛口径同 `TaskQuery`）
- [ ] T002 `ChatSessionRepository` 加 `JpaSpecificationExecutor`
- [ ] T003 `ChatSessionService.search(query)`：Specification（keyword 匹配标题）+
      按 `updatedAt` 倒序 + 消息条数只对当前页做一次分组查询
- [ ] T004 `ChatSessionController` 新增 `GET /sessions/search`（**原 `GET /sessions` 保持全量不动**）
- [ ] T005 确认 `list()` 与 `GET /sessions` 零改动（导出链路与既有测试依赖它们）

## Phase 2: 知识库

- [ ] T006 [P] `knowledge/dto/KnowledgeDocumentQuery`：`keyword` / `page` / `size`
- [ ] T007 `KnowledgeDocumentRepository` 加 `JpaSpecificationExecutor`
- [ ] T008 `KnowledgeDocumentService.search(query)`：keyword 匹配**标题与文件名**，
      按 `createdAt` 倒序
- [ ] T009 `KnowledgeController` 新增 `GET /documents/search`（**原 `GET /documents` 保持全量不动**）
- [ ] T010 确认 `list()` 与 `GET /documents` 零改动

## Phase 3: 测试

- [ ] T011 `ChatSessionPagingIntegrationTest`：分页不重复不遗漏、越界 page 返回空、
      size 收敛、keyword 筛选、翻页后消息条数仍正确
- [ ] T012 `KnowledgeDocumentPagingIntegrationTest`：分页、size 收敛、标题命中、文件名命中、
      无结果时 `total=0`
- [ ] T013 `./mvnw -B verify` 全绿，**导出链路（阶段 12 的测试）零改动通过**

## Phase 4: 前端

- [ ] T014 `api/chat.ts`：`listSessions({keyword,page,size})` → `PageDto<ChatSession>`
- [ ] T015 `api/knowledge.ts`：`listDocuments({keyword,page,size})` → `PageDto<KnowledgeDocument>`
- [ ] T016 `stores/chat.ts`：`page/size/total/keyword` + `search` 归零页码
- [ ] T017 `stores/knowledge.ts`：同上
- [ ] T018 `ChatView`：会话列表加搜索框 + 分页
- [ ] T019 `KnowledgeView`：文档列表加搜索框 + 分页
- [ ] T020 `test/list-paging.test.mjs`：搜索词变化归零页码、翻页请求参数正确
- [ ] T021 `type-check` / `build` / `test:logic` 全绿

## Phase 5: 验证与文档

- [ ] T022 真机：知识库翻页 + 标题搜索 + 文件名搜索；对话页搜索会话
- [ ] T023 真机：导出功能仍拿全量（`counts.sessions` / `counts.knowledgeDocuments` 与库里一致）
- [ ] T024 README 路线图加阶段 16
- [ ] T025 提交：`docs(list): 阶段 16 规格` + `feat(list): 阶段 16 实现`

## 完成记录

（实现后回填）
