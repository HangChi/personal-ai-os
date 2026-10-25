---
description: "Task list for 阶段 7 — 会话重命名"
---

# Tasks: 阶段 7 — 会话重命名

**Input**: Design documents from `/specs/007-chat-session-rename/`

> 状态说明：本清单为**规格阶段**产物，实现待用户确认规格后开始。
> 每个 Phase 一次提交；`[P]` 标记可并行任务；完成后在方框内记 `x` 并在 Phase 标题记 ✅。

---

## Phase 1: 后端写路径与端点

- [ ] T001 `chat/dto/RenameSessionRequest.java`：单字段 record（`String title`），**不加** `@Size`（research R-02）
- [ ] T002 `ChatSessionService.rename(conversationId, title)`：`validateConversationId` → `requireSession` → `Texts.requireNotBlank(title, "标题", Texts.TITLE_MAX_LENGTH)` → 写 `title`（**不动 `updatedAt`**）→ 返回带 `messageCount` 的 DTO
- [ ] T003 `ChatSessionService.create` 收敛到 `Texts.trimToNull` + `Texts.requireNotBlank`，删除模块内 `TITLE_MAX_LENGTH` 私有常量（research R-08）
- [ ] T004 `CreateSessionRequest` 去掉 `@Size(max = 200)`（口径统一，见 R-08）
- [ ] T005 `ChatSessionController` 新增 `@PatchMapping("/sessions/{conversationId}")`，只做解析与包装
- [ ] T006 集成测试 `ChatSessionRenameIT`：覆盖契约用例 C-1~C-11（含"原始 230 / 归一后 190"与"顺序不变"）
- [ ] T007 `./mvnw -B verify` 全绿（既有 174 测试零回归 + 新增用例）
- [ ] T008 **Phase 1 提交**

## Phase 2: 前端入口

- [ ] T009 `api/chat.ts` 新增 `renameSession(conversationId, title)`
- [ ] T010 `stores/chat.ts` 新增 `renameSession`：成功**就地替换** `sessions` 项（不调 `loadSessions`，research R-05）；失败只设 `error`
- [ ] T011 `ChatView.vue` 会话项增加「重命名」按钮 → `ElMessageBox.prompt` 预填当前标题 + 非空/200 字校验（research R-06）
- [ ] T012 `frontend/test/chat-store-rename.test.mjs`：本地替换、不重拉列表、失败不动列表、顺序不变
- [ ] T013 `npm run type-check && npm run build && npm run test:logic` 全绿
- [ ] T014 **Phase 2 提交**

## Phase 3: 真机验收与文档回填

- [ ] T015 `curl` 冒烟：PATCH 改名 → 200；GET 列表核对新标题
- [ ] T016 浏览器点击/取消/空值拦截三条路径实测（条件不具备则保持 ⏳ 并写明原因）
- [ ] T017 回填 quickstart 全部 ✅/⏳ 状态；若有实现偏差，同提交回改 spec/research/contracts
- [ ] T018 更新 README 能力清单（会话管理：创建/改名/删除）与当前状态
- [ ] T019 **Phase 3 提交**

---

## Notes

- 贯穿本阶段的硬约束：**改名不改 `updatedAt`**（FR-004）。任何"顺手 `session.setUpdatedAt(now)`"都会让 SC-002 失败。
- 校验只有一处：`Texts.requireNotBlank`。若发现自己在 DTO 上再加长度注解，先读 research R-02。
- 判定顺序是契约：格式 → 空 → 长度 → 存在性。空标题必须**不触发查库**。
- 不新增迁移。若本阶段出现任何 `.sql` 文件，说明走偏了。
