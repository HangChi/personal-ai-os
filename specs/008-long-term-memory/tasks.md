---
description: "Task list for 阶段 8 — 长期记忆"
---

# Tasks: 阶段 8 — 长期记忆

**Input**: Design documents from `/specs/008-long-term-memory/`

> 状态说明：**已实现并验证完毕**（2026-10-25）。验收证据见 [quickstart.md](./quickstart.md)。
> `[P]` 标记可并行任务。实际提交粒度见「完成记录」。

---

## Phase 1: 数据与 CRUD ✅

- [x] T001 `V9__create_memory.sql`：建 `memory` 表（id/content/created_at/updated_at），不建索引不加 CHECK（R-08）
- [x] T002 [P] `memory/entity/MemoryEntity.java`
- [x] T003 [P] `memory/repository/MemoryRepository.java`（`findAllByOrderByUpdatedAtDesc`、`findFirstByContent`）
- [x] T004 `memory/service/MemoryService`：`create`（归一 + 2000 上限 + 去重）/ `update` / `delete` / `list`；id 格式校验在查库前
- [x] T005 `memory/dto/`：`MemoryDto`、`CreateMemoryRequest`、`UpdateMemoryRequest`
- [x] T006 `memory/controller/MemoryController`：GET/POST/PATCH/DELETE `/api/v1/memories`
- [x] T007 `MemoryServiceTest`：校验顺序、去重、渲染预算与不截断（9 个用例）
- [x] T008 `MemoryIntegrationTest`（REST 部分）：M-1/M-3/M-4/M-10~M-13
- [x] T009 `./mvnw -B verify` 全绿

## Phase 2: 注入与工具 ✅

- [x] T011 [P] `common/ai/PromptContributor`：SPI（`String contribute()`）
- [x] T012 [P] `memory/MemoryPromptContributor`：实现 SPI，渲染交给 `MemoryService.renderForPrompt()`
- [x] T013 `chat/service/ChatCompletionService`：请求级 `.system(base + contributions)`，基础提示取自 `AiProperties`（R-09）
- [x] T014 `memory/tool/MemoryTools`：`@Tool saveMemory(content)`，描述含「仅在用户明确要求长期记住时调用」
- [x] T015 `memory/tool/MemoryToolContributor`：实现既有 `ToolContributor`
- [x] T016 `MemoryIntegrationTest`（注入部分）：M-2/M-5/M-8 + 工具 M-14 + 删后不注入
- [x] T017 `MemoryServiceTest` 渲染预算单测（35 条 / 6000 字符 / 无记忆空串）

## Phase 3: 前端 ✅

- [x] T019 [P] `api/memory.ts`：list/create/update/remove
- [x] T020 [P] `stores/memory.ts`：新增**重拉**、编辑**就地替换**、删除**本地移除**、失败保留
- [x] T021 `views/MemoryView.vue`：列表 + 新增 + 编辑 + 删除（二次确认）+ 空状态 + 字数上限
- [x] T022 `router/index.ts` + `App.vue`：`/memory` 路由、导航项、阶段标识改「阶段 8 · 长期记忆」
- [x] T023 `frontend/test/memory-store.test.mjs`（21 条断言）
- [x] T024 `type-check` + `build` + `test:logic` 全绿

## Phase 4: 真机验收与文档 ✅

- [x] T026 `curl` 九步冒烟（含三条错误路径）；`flyway_schema_history` 确认 V9 成功
- [x] T027 真实对话保存记忆 → ⏳ 真实模型不可用（已写明原因与补验动作，见 quickstart）
- [x] T028 浏览器五条路径实测（空状态/新增/编辑/删除/导航）+ 截图核对布局
- [x] T029 回填 quickstart；README 与 architecture 补阶段 8；修正 README 结构图中 `ai` 与 `memory` 的过期标注
- [x] T030 **提交**

---

## 完成记录

| 内容 | 结果 |
|---|---|
| 后端基线 | 181 → **203** 测试（+9 单测 +13 集成），BUILD SUCCESS |
| 前端基线 | 60 → **81** 断言全通过；`type-check` 0 退出；生产构建成功 |
| 数据库 | V9 一个迁移，只建一张表；`flyway_schema_history` 最新行 `success=t` |
| 真机 | REST 9 步 + 浏览器 5 条路径，控制台 0 错误 |
| 未验证 | M-15（知识库不注入，静态确认）、真实对话保存（无可用模型）—— 均写明原因与补验动作 |
| 工具总数 | 内置工具 14 → **15**（+`saveMemory`），README 已同步 |

## Notes

- **不截断单条记忆**（R-05）。超预算就整条不注入，绝不写半条 —— 残缺约束比缺失更危险。
- 请求级 `system()` 会**替换**默认系统提示，必须显式拼上 `AiProperties.systemPrompt()`（R-09）。
  只传记忆块会让助手"变了个样"且不报任何错。
- 删除/修改**不暴露给模型**，只有界面能发起。
- 注入顺序 = 列表顺序 = `updated_at` 倒序，三者必须一致，否则用户无法预期哪 30 条生效。
