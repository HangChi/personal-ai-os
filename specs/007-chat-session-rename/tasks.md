---
description: "Task list for 阶段 7 — 会话重命名"
---

# Tasks: 阶段 7 — 会话重命名

**Input**: Design documents from `/specs/007-chat-session-rename/`

> 状态说明：**已实现并验证完毕**（2026-10-25）。验收证据见 [quickstart.md](./quickstart.md)。
> `[P]` 标记可并行任务；实际执行时后端/前端连续完成，提交粒度见文末「完成记录」。

---

## Phase 1: 后端写路径与端点 ✅

- [x] T001 `chat/dto/RenameSessionRequest.java`：单字段 record（`String title`），**不加** `@Size`（research R-02）
- [x] T002 `ChatSessionService.rename(conversationId, title)`：`validateConversationId` → `Texts.requireNotBlank` → `requireSession` → 写 `title`（**不动 `updatedAt`**）→ 返回带 `messageCount` 的 DTO
- [x] T003 `ChatSessionService.create` 收敛到 `Texts.trimToNull` + `Texts.requireNotBlank`，删除模块内 `TITLE_MAX_LENGTH` 私有常量（research R-08）
- [x] T004 `CreateSessionRequest` 去掉 `@Size(max = 200)`（口径统一，见 R-08）
- [x] T005 `ChatSessionController` 新增 `@PatchMapping("/sessions/{conversationId}")`，只做解析与包装
- [x] T006 集成测试：10 个用例覆盖契约 C-1~C-11（含"原始 230 / 归一后 190"与"顺序不变"），落在既有 `ChatApiIntegrationTest`
- [x] T007 单测：7 个用例补进 `ChatSessionServiceTest`（含"空/超长时不查库"的顺序断言）
- [x] T008 `./mvnw -B verify` 全绿：**191 tests / 0 failures**

## Phase 2: 前端入口 ✅

- [x] T009 `api/chat.ts` 新增 `renameSession(conversationId, title)`
- [x] T010 `stores/chat.ts` 新增 `renameSession`：成功**就地替换** `sessions` 项（不调 `loadSessions`，research R-05）；失败只设 `error`
- [x] T011 `ChatView.vue` 会话项增加「重命名」按钮 → `ElMessageBox.prompt` 预填当前标题 + 非空/200 字校验（research R-06）
- [x] T012 `frontend/test/chat-store-rename.test.mjs`：本地替换、不重拉列表、失败不动列表、顺序不变、重复提交一致（13 条断言）
- [x] T013 `type-check` + `build` + `test:logic` 全绿：**60 通过 / 0 失败**
- [x] T014 真机截图后修正操作区布局（悬停浮现 + 绝对定位；触摸设备回退常显）

## Phase 3: 真机验收与文档回填 ✅

- [x] T015 `curl` 冒烟：PATCH 改名 → 200；空标题/非法 id/不存在三条错误路径均符合契约
- [x] T016 浏览器四条路径实测（点击 / 刷新持久 / 取消 / 空值拦截），控制台 0 错误
- [x] T017 回填 quickstart 全部 ✅ 状态，并记录两处实现偏差
- [x] T018 README 与 architecture 路线图补上阶段 7（两个文件各一行，保持一致）
- [x] T019 **提交**

---

## 完成记录

| 内容 | 结果 |
|---|---|
| 后端基线 | 174 → **191** 测试（+7 单测 +10 集成），`./mvnw -B verify` BUILD SUCCESS |
| 前端基线 | 47 → **60** 断言全通过；`type-check` 0 退出；生产构建成功 |
| 真机 | REST 6 条路径 + 浏览器 4 条路径，全部实测通过 |
| 数据库 | **零迁移**、零列变更（`git status` 无 `.sql`） |
| 偏差 | ① 操作区布局按真机截图改为悬停浮现；② 三次 Phase 提交合并为「实现 + 文档回填」两次 |

## Notes

- 贯穿本阶段的硬约束：**改名不改 `updatedAt`**（FR-004）。任何"顺手 `session.setUpdatedAt(now)`"都会让 SC-002 失败。
- 校验只有一处：`Texts.requireNotBlank`。若发现自己在 DTO 上再加长度注解，先读 research R-02。
- 判定顺序是契约：格式 → 空 → 长度 → 存在性。空标题必须**不触发查库**。
- 不新增迁移。若本阶段出现任何 `.sql` 文件，说明走偏了。
