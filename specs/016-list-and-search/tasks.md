---
description: "Task list for 阶段 16 — 列表分页与站内搜索"
---

# Tasks: 阶段 16 — 列表分页与站内搜索

**Input**: Design documents from `/specs/016-list-and-search/`

---

## Phase 1: 会话

- [x] T001 [P] `chat/dto/ChatSessionQuery`：`keyword` / `page` / `size`（收敛口径同 `TaskQuery`）
- [x] T002 `ChatSessionRepository` 加 `JpaSpecificationExecutor`
- [x] T003 `ChatSessionService.search(query)`：Specification（keyword 匹配标题）+
      按 `updatedAt` 倒序 + 消息条数只对当前页做一次分组查询
- [x] T004 `ChatSessionController` 新增 `GET /sessions/search`（**原 `GET /sessions` 保持全量不动**）
- [x] T005 确认 `list()` 与 `GET /sessions` 零改动（导出链路与既有测试依赖它们）

## Phase 2: 知识库

- [x] T006 [P] `knowledge/dto/KnowledgeDocumentQuery`：`keyword` / `page` / `size`
- [x] T007 `KnowledgeDocumentRepository` 加 `JpaSpecificationExecutor`
- [x] T008 `KnowledgeDocumentService.search(query)`：keyword 匹配**标题与文件名**，
      按 `createdAt` 倒序
- [x] T009 `KnowledgeController` 新增 `GET /documents/search`（**原 `GET /documents` 保持全量不动**）
- [x] T010 确认 `list()` 与 `GET /documents` 零改动

## Phase 3: 测试

- [x] T011 `ChatSessionPagingIntegrationTest`：分页不重复不遗漏、越界 page 返回空、
      size 收敛、keyword 筛选、翻页后消息条数仍正确
- [x] T012 `KnowledgeDocumentPagingIntegrationTest`：分页、size 收敛、标题命中、文件名命中、
      无结果时 `total=0`
- [x] T013 `./mvnw -B verify` 全绿，**导出链路（阶段 12 的测试）零改动通过**

## Phase 4: 前端

- [x] T014 `api/chat.ts`：`listSessions({keyword,page,size})` → `PageDto<ChatSession>`
- [x] T015 `api/knowledge.ts`：`listDocuments({keyword,page,size})` → `PageDto<KnowledgeDocument>`
- [x] T016 `stores/chat.ts`：`page/size/total/keyword` + `search` 归零页码
- [x] T017 `stores/knowledge.ts`：同上
- [x] T018 `ChatView`：会话列表加搜索框 + 分页
- [x] T019 `KnowledgeView`：文档列表加搜索框 + 分页
- [x] T020 `test/list-paging.test.mjs`：搜索词变化归零页码、翻页请求参数正确
- [x] T021 `type-check` / `build` / `test:logic` 全绿

## Phase 5: 验证与文档

- [x] T022 真机：知识库翻页 + 标题搜索 + 文件名搜索；对话页搜索会话
- [x] T023 真机：导出功能仍拿全量（`counts.sessions` / `counts.knowledgeDocuments` 与库里一致）
- [x] T024 README 路线图加阶段 16
- [x] T025 提交：`docs(list): 阶段 16 规格` + `feat(list): 阶段 16 实现`

## 完成记录

**提交**：`a8d2529` feat(list): 阶段 16 列表分页与站内搜索（T001-T021）

**测试**
- 后端 `./mvnw -B verify`：**273** 通过（原 264 + 新增 9）
  - `ListSearchIntegrationTest`：会话分页不重复不遗漏 / 越界 page 空 items / size 收敛 /
    关键词筛选 / 翻页后消息数正确 / 原全量端点不变；文档分页 / 标题命中 /
    文件名命中 / 无结果 total=0
- 前端 `npm run test:logic`：**239** 断言通过（原 224 + 新增 15）；`type-check`、`build` 通过

**真机验证（curl + Playwright）**
| 项目 | 结果 |
|---|---|
| 会话分页 | `total=10`、`items=5`、消息条数正确 |
| 会话搜索 | 「记住」→ 1 条；「记忆」→ 0 条（库里标题是「记住：…」，不是匹配失败） |
| 知识库 | 共 3 篇 → 搜 `sample` → 2 篇 → 清空 → 3 篇 |
| 知识库按文件名 | 搜 `docx` → 命中 `sample.docx`（标题同为 `sample`，证明走的是文件名匹配） |
| size 越界 | `size=1000` → 收敛到 100 |
| 原端点 | `GET /chat/sessions` 仍是数组，长度 10（导出链路不受影响） |
| 页面错误 | (无) |

**方案调整记录**：原计划是改造现有 `GET /sessions` / `GET /documents`。
实施时发现它们被 6 个集成测试与导出链路依赖，改造要么波及 6 个测试，
要么做成「同一路径两种响应形状」。改为**新增 `/search` 端点**、原端点不动，
spec/plan/tasks 已同步修正（提交 `docs(list): 阶段 16 方案修正`）。

**顺带修掉的隐含耦合**：`chat-store-rename.test.mjs` 直接 stub `/chat/sessions`，
路径变化才暴露「这个测试其实依赖列表端点」。已改 stub `/sessions/search` 并返回 PageDto。
