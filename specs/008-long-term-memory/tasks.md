---
description: "Task list for 阶段 8 — 长期记忆"
---

# Tasks: 阶段 8 — 长期记忆

**Input**: Design documents from `/specs/008-long-term-memory/`

> 状态说明：本清单为**规格阶段**产物，实现待用户确认规格后开始。
> 每个 Phase 一次提交；`[P]` 标记可并行任务；完成后在方框内记 `x`。

---

## Phase 1: 数据与 CRUD

- [ ] T001 `V9__create_memory.sql`：建 `memory` 表（id/content/created_at/updated_at），不建索引不加 CHECK（R-08）
- [ ] T002 [P] `memory/entity/MemoryEntity.java`
- [ ] T003 [P] `memory/repository/MemoryRepository.java`（`findAllByOrderByUpdatedAtDesc`、`existsByContent`）
- [ ] T004 `memory/service/MemoryService`：`create`（归一 + 2000 上限 + 去重）/ `update` / `delete` / `list`；id 格式校验在查库前
- [ ] T005 `memory/dto/`：`MemoryDto`、`CreateMemoryRequest`、`UpdateMemoryRequest`
- [ ] T006 `memory/controller/MemoryController`：GET/POST/PATCH/DELETE `/api/v1/memories`
- [ ] T007 `MemoryServiceTest`：校验、去重、归一、id 顺序
- [ ] T008 `MemoryIntegrationTest`（REST 部分）：M-1/M-3/M-4/M-10~M-13
- [ ] T009 `./mvnw -B verify` 全绿
- [ ] T010 **Phase 1 提交**

## Phase 2: 注入与工具

- [ ] T011 [P] `common/ai/PromptContributor`：SPI（`String contribute()`）
- [ ] T012 [P] `memory/MemoryPromptContributor`：按 `updated_at` 倒序渲染记忆块，30 条 / 6000 字符预算，超预算停止（R-05），无记忆返回空串
- [ ] T013 `chat/service/ChatCompletionService`：请求级 `.system(base + contributions)`，基础提示取自 `AiProperties`（R-09）
- [ ] T014 `memory/tool/MemoryTools`：`@Tool saveMemory(content)`，描述含「仅当用户明确要求长期记住」
- [ ] T015 `memory/tool/MemoryToolContributor`：实现既有 `ToolContributor`
- [ ] T016 `MemoryIntegrationTest`（注入部分）：M-2/M-5/M-8/M-9 + 工具 M-14 + 知识库 M-15
- [ ] T017 `MemoryServiceTest` 补渲染预算单测（35 条 / 6000 字符 / 无记忆空串）
- [ ] T018 **Phase 2 提交**

## Phase 3: 前端

- [ ] T019 [P] `api/memory.ts`：list/create/update/remove
- [ ] T020 [P] `stores/memory.ts`：加载、新增后重拉、编辑就地替换、删除本地移除、错误保留
- [ ] T021 `views/MemoryView.vue`：列表 + 新增 + 编辑 + 删除（二次确认）+ 空状态
- [ ] T022 `router/index.ts` + `App.vue`：新增 `/memory` 路由与导航项
- [ ] T023 `frontend/test/memory-store.test.mjs`
- [ ] T024 `type-check` + `build` + `test:logic` 全绿
- [ ] T025 **Phase 3 提交**

## Phase 4: 真机验收与文档

- [ ] T026 `curl` 四端点冒烟 + 三条错误路径
- [ ] T027 真实对话保存记忆 → 记忆页可见 → 新会话生效（需可用模型，否则标 ⏳）
- [ ] T028 浏览器五条路径（列表/新增/编辑/删除/空状态）+ 截图核对布局
- [ ] T029 回填 quickstart；README 与 architecture 路线图补阶段 8；修正 README 结构图中 `memory` 的标注
- [ ] T030 **提交**

---

## Notes

- **不截断单条记忆**（R-05）。超预算就整条不注入，绝不写半条 —— 残缺约束比缺失更危险。
- 请求级 `system()` 会**替换**默认系统提示，必须显式拼上 `AiProperties.systemPrompt()`（R-09）。
  只传记忆块会让助手"变了个样"且不报任何错。
- 删除/修改**不暴露给模型**，只有界面能发起。
- 注入顺序 = 列表顺序 = `updated_at` 倒序，三者必须一致，否则用户无法预期哪 30 条生效。
