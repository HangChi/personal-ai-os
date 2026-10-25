---
description: "Task list for 阶段 17 — 跨模块全局搜索"
---

# Tasks: 阶段 17 — 跨模块全局搜索

**Input**: Design documents from `/specs/017-global-search/`

---

## Phase 1: SPI 与聚合

- [ ] T001 [P] `common/search/SearchHit`：`kind` / `id` / `title` / `subtitle` / `matchedField`
- [ ] T002 [P] `common/search/SearchGroup`：`module` / `kind` / `hits`
- [ ] T003 `common/search/SearchContributor`：`moduleName()` / `kind()` / `search(keyword, limit)`
- [ ] T004 `GlobalSearchService`：收集 + 按 `moduleName` 排序 + **单模块失败隔离**（try-catch + warn）
- [ ] T005 `SearchController`：`GET /api/v1/search`，校验 keyword 非空、≤100 字符，limit ≤20
- [ ] T006 `SearchControllerTest`：空关键词 400 / 超长 400 / limit 收敛

## Phase 2: 五个贡献者

- [ ] T007 [P] `TaskSearchContributor`：标题 / 描述，标注命中字段
- [ ] T008 `ChatSearchContributor`：标题优先；消息内容命中返回**会话**并去重
- [ ] T009 `ChatMessageRepository` 加「按内容查 conversationId」（分页 + 去重）
- [ ] T010 [P] `CalendarSearchContributor`：标题 / 描述 / 地点
- [ ] T011 [P] `MemorySearchContributor`：内容
- [ ] T012 `KnowledgeSearchContributor`：标题 / 文件名 / 分块内容（命中分块返回**文档**）
- [ ] T013 `VectorStoreInspector` 加「按内容查 documentId」

## Phase 3: 测试

- [ ] T014 `GlobalSearchIntegrationTest`：五模块各造一条 → 五个分组、条数正确
- [ ] T015 同测试：标题命中优先于内容命中；消息命中返回会话且只出现一次
- [ ] T016 同测试：空关键词 400；limit 收敛；无命中时返回空 groups
- [ ] T017 `./mvnw -B verify` 全绿

## Phase 4: 前端

- [ ] T018 `api/search.ts`：`globalSearch(keyword, limit)`
- [ ] T019 `stores/search.ts`：keyword / groups / loading / error / search
- [ ] T020 `SearchView.vue`：按分组展示，空态文案，条目跳转
- [ ] T021 `App.vue`：顶栏搜索框（回车跳 `/search?q=`）
- [ ] T022 `router`：`/search` 路由；`kind → route` 映射表放前端
- [ ] T023 `test/search.test.mjs`：kind→路由映射、空 groups 文案、失败处理
- [ ] T024 `type-check` / `build` / `test:logic` 全绿

## Phase 5: 验证与文档

- [ ] T025 真机：顶栏搜索 → 分组结果 → 点击跳转对应模块
- [ ] T026 README 路线图加阶段 17
- [ ] T027 提交：`docs(search): 阶段 17 规格` + `feat(search): 阶段 17 实现`

## 完成记录

（实现后回填）
