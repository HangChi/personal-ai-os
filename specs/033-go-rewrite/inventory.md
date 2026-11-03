# Java → Go 迁移基线清单

本文件由 `scripts/migration/go_inventory.py generate` 生成，请勿手改。

- 基线提交：`cb5d694d62fa93742b5789b97273e76d931ce335`
- 跟踪文件：1093；注解 HTTP 路由：104；工具：27；测试声明：1119。
- 每项默认 `not_started`；状态与证据单独写入 `progress.json`。

类别 | 文件数
--- | ---
documentation | 41
frontend_build | 20
frontend_source | 130
frontend_test | 77
java_build | 6
java_resource | 8
java_source | 391
java_test | 154
java_test_resource | 1
operations_and_repository | 70
specification | 153
sql_migration | 42

## documentation

基线文件 | SHA-256（前 12 位） | 阶段 | Go 去向 / 复用要求
--- | --- | --- | ---
[.claude/skills/speckit-analyze/SKILL.md](../../.claude/skills/speckit-analyze/SKILL.md) | `e1f8323285ed` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[.claude/skills/speckit-checklist/SKILL.md](../../.claude/skills/speckit-checklist/SKILL.md) | `34c8c681f547` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[.claude/skills/speckit-clarify/SKILL.md](../../.claude/skills/speckit-clarify/SKILL.md) | `f4b3f2c95087` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[.claude/skills/speckit-constitution/SKILL.md](../../.claude/skills/speckit-constitution/SKILL.md) | `a93047917a5f` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[.claude/skills/speckit-converge/SKILL.md](../../.claude/skills/speckit-converge/SKILL.md) | `65ea8b863835` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[.claude/skills/speckit-implement/SKILL.md](../../.claude/skills/speckit-implement/SKILL.md) | `51bd89322e02` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[.claude/skills/speckit-plan/SKILL.md](../../.claude/skills/speckit-plan/SKILL.md) | `2fe3f96886e9` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[.claude/skills/speckit-specify/SKILL.md](../../.claude/skills/speckit-specify/SKILL.md) | `42fe016b9183` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[.claude/skills/speckit-tasks/SKILL.md](../../.claude/skills/speckit-tasks/SKILL.md) | `597853362a0a` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[.claude/skills/speckit-taskstoissues/SKILL.md](../../.claude/skills/speckit-taskstoissues/SKILL.md) | `76a6ec1fcc2d` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[.specify/memory/constitution.md](../../.specify/memory/constitution.md) | `da397eb6e8b7` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/templates/checklist-template.md](../../.specify/templates/checklist-template.md) | `856532b3cb66` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/templates/constitution-template.md](../../.specify/templates/constitution-template.md) | `ce7549540fa4` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/templates/plan-template.md](../../.specify/templates/plan-template.md) | `7e637502d41e` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/templates/spec-template.md](../../.specify/templates/spec-template.md) | `3945437fc35c` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/templates/tasks-template.md](../../.specify/templates/tasks-template.md) | `fc29a233f6f5` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[CONTRIBUTING.md](../../CONTRIBUTING.md) | `527241c6bf98` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[README.md](../../README.md) | `570f0bba7523` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[SECURITY.md](../../SECURITY.md) | `917269dc48eb` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[docs/agent-reliability.md](../../docs/agent-reliability.md) | `ff9fa90a5faa` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[docs/api.md](../../docs/api.md) | `df65642c5187` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[docs/architecture.md](../../docs/architecture.md) | `d3590c65b95a` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[docs/development.md](../../docs/development.md) | `34265e72696d` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[docs/distributed-workers.md](../../docs/distributed-workers.md) | `c905fbecf0bf` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[docs/e2e-report-2026-10-29.md](../../docs/e2e-report-2026-10-29.md) | `9778e2aa8910` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[docs/mcp.md](../../docs/mcp.md) | `5fb0a1174bba` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[docs/operations.md](../../docs/operations.md) | `cea7f5b3911f` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[docs/redis-extensions.md](../../docs/redis-extensions.md) | `8c76f7c1074a` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[docs/reminder-stream-and-queue.md](../../docs/reminder-stream-and-queue.md) | `2284c0971ab2` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[docs/review-2026-10-27.md](../../docs/review-2026-10-27.md) | `8589efbd9ed6` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[scripts/e2e/reports/e2e-20261029-013844.md](../../scripts/e2e/reports/e2e-20261029-013844.md) | `3fe1929ee337` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-013930.md](../../scripts/e2e/reports/e2e-20261029-013930.md) | `0eb96e9444c5` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-014133.md](../../scripts/e2e/reports/e2e-20261029-014133.md) | `1c1f7503876e` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-014805.md](../../scripts/e2e/reports/e2e-20261029-014805.md) | `2bc139292211` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-015051.md](../../scripts/e2e/reports/e2e-20261029-015051.md) | `c45e0fadf210` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-015102.md](../../scripts/e2e/reports/e2e-20261029-015102.md) | `fe8f370a068e` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-015110.md](../../scripts/e2e/reports/e2e-20261029-015110.md) | `2d556d55761b` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-015256.md](../../scripts/e2e/reports/e2e-20261029-015256.md) | `5e7f576dd257` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-104637.md](../../scripts/e2e/reports/e2e-20261029-104637.md) | `6ed8d121c587` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-105644.md](../../scripts/e2e/reports/e2e-20261029-105644.md) | `58d00a811115` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/README.md](../../scripts/eval/README.md) | `81b7035560d1` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认

## frontend_build

基线文件 | SHA-256（前 12 位） | 阶段 | Go 去向 / 复用要求
--- | --- | --- | ---
[frontend/.dockerignore](../../frontend/.dockerignore) | `18df875feb9b` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/.env.example](../../frontend/.env.example) | `70951ebe7034` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/.gitignore](../../frontend/.gitignore) | `aad5e47c9692` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/.prettierignore](../../frontend/.prettierignore) | `000a9e624a37` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/.prettierrc.json](../../frontend/.prettierrc.json) | `fa5cbece25d4` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/Dockerfile](../../frontend/Dockerfile) | `eb5fa399aab3` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/auto-imports.d.ts](../../frontend/auto-imports.d.ts) | `9ff922b8d791` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/components.d.ts](../../frontend/components.d.ts) | `16aab8913c34` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/env.d.ts](../../frontend/env.d.ts) | `e2eb3826ebdb` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/eslint.config.js](../../frontend/eslint.config.js) | `b2fbf442e611` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/index.html](../../frontend/index.html) | `28cebc080948` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/nginx.conf](../../frontend/nginx.conf) | `a298093e020a` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/package-lock.json](../../frontend/package-lock.json) | `35cd0fe440b1` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/package.json](../../frontend/package.json) | `86a4fa0c8501` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/public/favicon.svg](../../frontend/public/favicon.svg) | `e262d2a97a52` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/public/sw.js](../../frontend/public/sw.js) | `1bfcaa7e1d02` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/tsconfig.json](../../frontend/tsconfig.json) | `34141fe1507a` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/tsconfig.node.json](../../frontend/tsconfig.node.json) | `73c0d59a4abb` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/vite.config.ts](../../frontend/vite.config.ts) | `776a2745a0a7` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/vitest.config.ts](../../frontend/vitest.config.ts) | `7eddb73e9cfe` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收

## frontend_source

基线文件 | SHA-256（前 12 位） | 阶段 | Go 去向 / 复用要求
--- | --- | --- | ---
[frontend/src/App.vue](../../frontend/src/App.vue) | `3d46f20fc1f3` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/activity.ts](../../frontend/src/api/activity.ts) | `8c14314732e2` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/auth.ts](../../frontend/src/api/auth.ts) | `e8a1740ee5f1` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/calendar.ts](../../frontend/src/api/calendar.ts) | `475127387c3a` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/chat-runs.ts](../../frontend/src/api/chat-runs.ts) | `7369cfd1fd22` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/chat.ts](../../frontend/src/api/chat.ts) | `e204529ad8f3` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/confirmations.ts](../../frontend/src/api/confirmations.ts) | `7c24e9ee5ed3` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/csrf.ts](../../frontend/src/api/csrf.ts) | `ac5c71d487bb` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/envelope.ts](../../frontend/src/api/envelope.ts) | `86d6e39d4be7` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/export.ts](../../frontend/src/api/export.ts) | `c94dae742da1` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/health.ts](../../frontend/src/api/health.ts) | `e9d2b3d4029a` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/http.ts](../../frontend/src/api/http.ts) | `5ec92a49ac39` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/knowledge-governance.ts](../../frontend/src/api/knowledge-governance.ts) | `eea8c92a318c` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/knowledge.ts](../../frontend/src/api/knowledge.ts) | `67ccfbeb1eff` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/mcp.ts](../../frontend/src/api/mcp.ts) | `0d9379799fbb` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/memory.ts](../../frontend/src/api/memory.ts) | `f6be4209bc99` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/model-profile.ts](../../frontend/src/api/model-profile.ts) | `a4ed8477065e` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/plan.ts](../../frontend/src/api/plan.ts) | `02a95dfb2f5c` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/reminders.ts](../../frontend/src/api/reminders.ts) | `b58e77672011` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/search.ts](../../frontend/src/api/search.ts) | `e909d8594bc3` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/tasks.ts](../../frontend/src/api/tasks.ts) | `cb2b77149e49` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/api/usage.ts](../../frontend/src/api/usage.ts) | `34a30fe8315a` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/AppLogo.vue](../../frontend/src/components/AppLogo.vue) | `2d8afd51b9b3` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/AppPagination.vue](../../frontend/src/components/AppPagination.vue) | `bd7b91acf940` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/ChatPlanPanel.vue](../../frontend/src/components/ChatPlanPanel.vue) | `b39085600d5b` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/EmptyState.vue](../../frontend/src/components/EmptyState.vue) | `ba987ce85703` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/FilterBar.vue](../../frontend/src/components/FilterBar.vue) | `37f79ceb4c88` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/MarkdownRenderer.vue](../../frontend/src/components/MarkdownRenderer.vue) | `8e1b27305046` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/PageHeader.vue](../../frontend/src/components/PageHeader.vue) | `d1436c07c9ce` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/StatusTag.vue](../../frontend/src/components/StatusTag.vue) | `8a0c79a7342c` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/ThemeToggle.vue](../../frontend/src/components/ThemeToggle.vue) | `b0c5659b0b3c` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/calendar/CalendarCreateForm.vue](../../frontend/src/components/calendar/CalendarCreateForm.vue) | `84732ac3fd73` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/calendar/CalendarDayList.vue](../../frontend/src/components/calendar/CalendarDayList.vue) | `851c7685056b` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/calendar/CalendarDueReminders.vue](../../frontend/src/components/calendar/CalendarDueReminders.vue) | `5b0d4184f557` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/calendar/CalendarEditDialog.vue](../../frontend/src/components/calendar/CalendarEditDialog.vue) | `1c04ea15e3c0` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/calendar/CalendarMonthGrid.vue](../../frontend/src/components/calendar/CalendarMonthGrid.vue) | `f5f9e47e3576` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/calendar/CalendarNotifyBanner.vue](../../frontend/src/components/calendar/CalendarNotifyBanner.vue) | `12bead359d25` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/calendar/ReminderNotice.vue](../../frontend/src/components/calendar/ReminderNotice.vue) | `483947240caa` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/calendar/ReminderNotifications.vue](../../frontend/src/components/calendar/ReminderNotifications.vue) | `2eb0db468148` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/chat/ChatComposer.vue](../../frontend/src/components/chat/ChatComposer.vue) | `f0efc62dfb18` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/chat/ChatConfirmationList.vue](../../frontend/src/components/chat/ChatConfirmationList.vue) | `f4e059a26324` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/chat/ChatExecutionTrail.vue](../../frontend/src/components/chat/ChatExecutionTrail.vue) | `d9b33e2f618c` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/chat/ChatFollowupQueue.vue](../../frontend/src/components/chat/ChatFollowupQueue.vue) | `3d587d79f5cb` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/chat/ChatMessageItem.vue](../../frontend/src/components/chat/ChatMessageItem.vue) | `627c7d3e01c4` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/chat/ChatModelPicker.vue](../../frontend/src/components/chat/ChatModelPicker.vue) | `05b4ec924013` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/chat/ChatSessionList.vue](../../frontend/src/components/chat/ChatSessionList.vue) | `3d0fa3f996aa` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/chat/ChatStreamingItem.vue](../../frontend/src/components/chat/ChatStreamingItem.vue) | `c271bd8b15c4` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/chat/ChatToolTrail.vue](../../frontend/src/components/chat/ChatToolTrail.vue) | `27b6068922ff` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/chat/ChatUserQuestion.vue](../../frontend/src/components/chat/ChatUserQuestion.vue) | `c56829683197` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/chat/ChatVoiceInput.vue](../../frontend/src/components/chat/ChatVoiceInput.vue) | `c52c343719cc` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/chat/ChatWelcome.vue](../../frontend/src/components/chat/ChatWelcome.vue) | `8f92a5a34ae1` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/chat/ContextMeter.vue](../../frontend/src/components/chat/ContextMeter.vue) | `1d80175a21e4` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/knowledge/KnowledgeAskTab.vue](../../frontend/src/components/knowledge/KnowledgeAskTab.vue) | `12da4ec0e9c0` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/knowledge/KnowledgeDocumentsTab.vue](../../frontend/src/components/knowledge/KnowledgeDocumentsTab.vue) | `50b3fb633b57` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/knowledge/KnowledgeGovernanceTab.vue](../../frontend/src/components/knowledge/KnowledgeGovernanceTab.vue) | `994348d93b13` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/knowledge/KnowledgeHitList.vue](../../frontend/src/components/knowledge/KnowledgeHitList.vue) | `90fe1d5a455e` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/knowledge/KnowledgeSearchTab.vue](../../frontend/src/components/knowledge/KnowledgeSearchTab.vue) | `7f24222423bb` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/knowledge/RagTrace.vue](../../frontend/src/components/knowledge/RagTrace.vue) | `8c38d75a6b12` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/settings/McpSettingsPanel.vue](../../frontend/src/components/settings/McpSettingsPanel.vue) | `685093525f60` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/settings/ModelProfileCard.vue](../../frontend/src/components/settings/ModelProfileCard.vue) | `bcbcfce1134b` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/settings/ModelProfileDialog.vue](../../frontend/src/components/settings/ModelProfileDialog.vue) | `4c33b7106b91` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/settings/ModelProfilesPanel.vue](../../frontend/src/components/settings/ModelProfilesPanel.vue) | `971675a8470f` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/settings/ProviderPresetPicker.vue](../../frontend/src/components/settings/ProviderPresetPicker.vue) | `fa6eb0ca2386` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/task/TaskBoardPanel.vue](../../frontend/src/components/task/TaskBoardPanel.vue) | `429e331afd53` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/task/TaskCreateForm.vue](../../frontend/src/components/task/TaskCreateForm.vue) | `020215836e50` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/task/TaskEditDialog.vue](../../frontend/src/components/task/TaskEditDialog.vue) | `a9685fcdc8c7` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/components/task/TaskTablePanel.vue](../../frontend/src/components/task/TaskTablePanel.vue) | `dc002817f1e6` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/board.ts](../../frontend/src/lib/board.ts) | `1920ba341b25` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/calendar-dates.ts](../../frontend/src/lib/calendar-dates.ts) | `4d7476a8184d` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/calendar-format.ts](../../frontend/src/lib/calendar-format.ts) | `afe6c11937c5` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/chat-scroll.ts](../../frontend/src/lib/chat-scroll.ts) | `ca484a702e85` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/chat-search.ts](../../frontend/src/lib/chat-search.ts) | `35181a66b0d2` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/chat-tool-history.ts](../../frontend/src/lib/chat-tool-history.ts) | `b5c10c3dd999` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/code-header.ts](../../frontend/src/lib/code-header.ts) | `eab0299832f6` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/confirm-box.ts](../../frontend/src/lib/confirm-box.ts) | `7c115afe170a` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/confirm.ts](../../frontend/src/lib/confirm.ts) | `bc8180d7f7e3` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/context-meter.ts](../../frontend/src/lib/context-meter.ts) | `1a5478f371c6` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/format.ts](../../frontend/src/lib/format.ts) | `cfb5a035e718` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/highlight-text.ts](../../frontend/src/lib/highlight-text.ts) | `50860e0dd709` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/model-profile.ts](../../frontend/src/lib/model-profile.ts) | `fdb03c38f527` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/provider-presets.ts](../../frontend/src/lib/provider-presets.ts) | `0af4f49f970d` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/relative-time.ts](../../frontend/src/lib/relative-time.ts) | `066c4e59e48f` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/search-routes.ts](../../frontend/src/lib/search-routes.ts) | `ddfdd92fa9ee` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/session-group.ts](../../frontend/src/lib/session-group.ts) | `a5c3fad2be67` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/settings.ts](../../frontend/src/lib/settings.ts) | `131e36b651bf` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/sidebar.ts](../../frontend/src/lib/sidebar.ts) | `90bf8b86a535` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/task-meta.ts](../../frontend/src/lib/task-meta.ts) | `33e9f2c87c78` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/theme.ts](../../frontend/src/lib/theme.ts) | `67d64a1edd29` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/throttle.ts](../../frontend/src/lib/throttle.ts) | `60ead8184579` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/tool-invocation-status.ts](../../frontend/src/lib/tool-invocation-status.ts) | `5e2c2792b049` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/usage-chart.ts](../../frontend/src/lib/usage-chart.ts) | `ab33a6c4d66c` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/lib/voice-input.ts](../../frontend/src/lib/voice-input.ts) | `3ed9dd2400a7` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/main.ts](../../frontend/src/main.ts) | `62c68356b055` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/notifications/permission.ts](../../frontend/src/notifications/permission.ts) | `112f91775bb2` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/notifications/reminder-notifier.ts](../../frontend/src/notifications/reminder-notifier.ts) | `ced1fc39b282` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/notifications/reminder-stream.ts](../../frontend/src/notifications/reminder-stream.ts) | `b4959970e0c7` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/notifications/web-push.ts](../../frontend/src/notifications/web-push.ts) | `3e3ac8ef18a4` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/router/index.ts](../../frontend/src/router/index.ts) | `06a3d24b3ea8` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/stores/activity.ts](../../frontend/src/stores/activity.ts) | `9fc1c1599d1d` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/stores/app.ts](../../frontend/src/stores/app.ts) | `619990ff4b6f` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/stores/auth.ts](../../frontend/src/stores/auth.ts) | `5e7d9da6e49a` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/stores/calendar.ts](../../frontend/src/stores/calendar.ts) | `fbb051a4061f` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/stores/chat.ts](../../frontend/src/stores/chat.ts) | `c24e1e331377` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/stores/knowledge.ts](../../frontend/src/stores/knowledge.ts) | `49f969e8ccc7` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/stores/mcp.ts](../../frontend/src/stores/mcp.ts) | `40f9db611d28` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/stores/memory.ts](../../frontend/src/stores/memory.ts) | `6f02afbcef52` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/stores/model-profile.ts](../../frontend/src/stores/model-profile.ts) | `58442a99a9fa` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/stores/search.ts](../../frontend/src/stores/search.ts) | `0062ba24db52` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/stores/settings.ts](../../frontend/src/stores/settings.ts) | `6b9b21469b45` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/stores/task.ts](../../frontend/src/stores/task.ts) | `c95b6799f248` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/stores/ui.ts](../../frontend/src/stores/ui.ts) | `897c92e5fe93` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/stores/usage.ts](../../frontend/src/stores/usage.ts) | `c423c8ea7eb2` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/styles/hljs-dark.css](../../frontend/src/styles/hljs-dark.css) | `1cf2faae08d1` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/styles/main.css](../../frontend/src/styles/main.css) | `ed71ff078031` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/styles/theme.css](../../frontend/src/styles/theme.css) | `8927ac1d1c32` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/styles/tokens.css](../../frontend/src/styles/tokens.css) | `1ce2e06544f3` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/types/api.ts](../../frontend/src/types/api.ts) | `30214fee203c` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/types/usage.ts](../../frontend/src/types/usage.ts) | `848d17b41fcb` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/views/ActivityView.vue](../../frontend/src/views/ActivityView.vue) | `26cec62c1ade` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/views/CalendarView.vue](../../frontend/src/views/CalendarView.vue) | `5f93963e3766` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/views/ChatView.vue](../../frontend/src/views/ChatView.vue) | `96116adc1684` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/views/HomeView.vue](../../frontend/src/views/HomeView.vue) | `d10d9069a607` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/views/KnowledgeView.vue](../../frontend/src/views/KnowledgeView.vue) | `12eda767994e` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/views/LoginView.vue](../../frontend/src/views/LoginView.vue) | `49e4b5fc11fb` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/views/MemoryView.vue](../../frontend/src/views/MemoryView.vue) | `98639b0a788f` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/views/NotFoundView.vue](../../frontend/src/views/NotFoundView.vue) | `22000fa530f1` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/views/SearchView.vue](../../frontend/src/views/SearchView.vue) | `3c7247e73b40` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/views/SettingsView.vue](../../frontend/src/views/SettingsView.vue) | `d8559460e97d` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/views/TasksView.vue](../../frontend/src/views/TasksView.vue) | `f8aab82a6767` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/src/views/UsageView.vue](../../frontend/src/views/UsageView.vue) | `07209fea25be` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收

## frontend_test

基线文件 | SHA-256（前 12 位） | 阶段 | Go 去向 / 复用要求
--- | --- | --- | ---
[frontend/test/activity-store.test.mjs](../../frontend/test/activity-store.test.mjs) | `b23a745a418b` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/app-store.test.mjs](../../frontend/test/app-store.test.mjs) | `3cadd030e8f6` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/assert.mjs](../../frontend/test/assert.mjs) | `e5c8abea4e50` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/auth-store.test.mjs](../../frontend/test/auth-store.test.mjs) | `702e408bc4cf` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/board.test.mjs](../../frontend/test/board.test.mjs) | `2d84bcd5cee0` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/calendar-dates.test.mjs](../../frontend/test/calendar-dates.test.mjs) | `07696ee61246` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/calendar-store.test.mjs](../../frontend/test/calendar-store.test.mjs) | `210cf6ebdd51` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/chat-context-store.test.mjs](../../frontend/test/chat-context-store.test.mjs) | `00ae0a600566` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/chat-run-events.test.mjs](../../frontend/test/chat-run-events.test.mjs) | `3c5fc9070d11` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/chat-scroll.test.mjs](../../frontend/test/chat-scroll.test.mjs) | `e7e5161bf8ff` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/chat-search.test.mjs](../../frontend/test/chat-search.test.mjs) | `d5e28b875f19` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/chat-send-retry.test.mjs](../../frontend/test/chat-send-retry.test.mjs) | `e6d8474e0449` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/chat-store-rename.test.mjs](../../frontend/test/chat-store-rename.test.mjs) | `87719a1834de` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/chat-stream.test.mjs](../../frontend/test/chat-stream.test.mjs) | `ff1951a274c9` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/chat-tool-history.test.mjs](../../frontend/test/chat-tool-history.test.mjs) | `5e941e52a314` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/a11y.test.ts](../../frontend/test/component/a11y.test.ts) | `a22d2eca8656` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/app-logo.test.ts](../../frontend/test/component/app-logo.test.ts) | `6d018589d8af` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/calendar-create-form.test.ts](../../frontend/test/component/calendar-create-form.test.ts) | `aac66d70cb10` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/calendar-month-grid.test.ts](../../frontend/test/component/calendar-month-grid.test.ts) | `40424a86a177` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/calendar-reminder-stream.test.ts](../../frontend/test/component/calendar-reminder-stream.test.ts) | `7ba23351ed13` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/chat-composer.test.ts](../../frontend/test/component/chat-composer.test.ts) | `24ad8a133b82` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/chat-confirmation-list.test.ts](../../frontend/test/component/chat-confirmation-list.test.ts) | `abb7d3312a7b` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/chat-execution-trail.test.ts](../../frontend/test/component/chat-execution-trail.test.ts) | `4e6f88222988` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/chat-followup-queue.test.ts](../../frontend/test/component/chat-followup-queue.test.ts) | `68c68c29ee75` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/chat-followup-settings.test.ts](../../frontend/test/component/chat-followup-settings.test.ts) | `8b14fa25f865` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/chat-message-edit.test.ts](../../frontend/test/component/chat-message-edit.test.ts) | `78331e9ae361` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/chat-message-item.test.ts](../../frontend/test/component/chat-message-item.test.ts) | `cd95bef7ab14` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/chat-model-switch.test.ts](../../frontend/test/component/chat-model-switch.test.ts) | `f12c44b4abbc` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/chat-runs.test.ts](../../frontend/test/component/chat-runs.test.ts) | `815a7e31507b` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/chat-session-collapse.test.ts](../../frontend/test/component/chat-session-collapse.test.ts) | `b2ddaaf4824a` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/chat-session-rename.test.ts](../../frontend/test/component/chat-session-rename.test.ts) | `8144eb7f08de` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/chat-streaming-item.test.ts](../../frontend/test/component/chat-streaming-item.test.ts) | `6f300bec5615` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/chat-tool-trail.test.ts](../../frontend/test/component/chat-tool-trail.test.ts) | `b3ee709728b8` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/chat-user-question.test.ts](../../frontend/test/component/chat-user-question.test.ts) | `d160bb693a0f` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/code-header.test.ts](../../frontend/test/component/code-header.test.ts) | `fb20b57b979a` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/context-meter.test.ts](../../frontend/test/component/context-meter.test.ts) | `9a7c71c9eabe` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/favicon.test.ts](../../frontend/test/component/favicon.test.ts) | `b0ebc2a502fc` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/knowledge-rag.test.ts](../../frontend/test/component/knowledge-rag.test.ts) | `dde64999b0d6` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/model-profile-window.test.ts](../../frontend/test/component/model-profile-window.test.ts) | `1caa8276d4de` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/reminder-notifications.test.ts](../../frontend/test/component/reminder-notifications.test.ts) | `1657a8873512` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/reminder-stream.test.ts](../../frontend/test/component/reminder-stream.test.ts) | `e468f68fbbf2` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/settings-navigation.test.ts](../../frontend/test/component/settings-navigation.test.ts) | `a5d0ce71be62` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/setup.ts](../../frontend/test/component/setup.ts) | `61c216c8e6ef` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/sidebar-nav.test.ts](../../frontend/test/component/sidebar-nav.test.ts) | `0944a1b31d3a` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/task-create-form.test.ts](../../frontend/test/component/task-create-form.test.ts) | `fcaaacee03ef` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/component/web-push.test.ts](../../frontend/test/component/web-push.test.ts) | `fe28e0b922bb` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/confirm.test.mjs](../../frontend/test/confirm.test.mjs) | `3685340e7a8f` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/context-meter-lib.test.mjs](../../frontend/test/context-meter-lib.test.mjs) | `20fbf827428d` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/envelope.test.mjs](../../frontend/test/envelope.test.mjs) | `c51d80dbb855` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/export-api.test.mjs](../../frontend/test/export-api.test.mjs) | `8b45fa8ed788` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/highlight-text.test.mjs](../../frontend/test/highlight-text.test.mjs) | `e1580ffc260e` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/knowledge-jobs.test.mjs](../../frontend/test/knowledge-jobs.test.mjs) | `1d4c0a992324` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/list-paging.test.mjs](../../frontend/test/list-paging.test.mjs) | `6208d672c546` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/loader.mjs](../../frontend/test/loader.mjs) | `8a54ed4d8729` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/mcp-store.test.mjs](../../frontend/test/mcp-store.test.mjs) | `0bcb998c9b10` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/memory-store.test.mjs](../../frontend/test/memory-store.test.mjs) | `89f9ee397857` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/model-profile-lib.test.mjs](../../frontend/test/model-profile-lib.test.mjs) | `1e60ee3fb69b` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/provider-presets.test.mjs](../../frontend/test/provider-presets.test.mjs) | `a0e314dc062b` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/relative-time.test.mjs](../../frontend/test/relative-time.test.mjs) | `25063f7ffcc5` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/reminder-notifier.test.mjs](../../frontend/test/reminder-notifier.test.mjs) | `da3022297c39` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/request-race.test.mjs](../../frontend/test/request-race.test.mjs) | `50dd91a2a344` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/resolve-hook.mjs](../../frontend/test/resolve-hook.mjs) | `e9f7ed5a6d08` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/run.mjs](../../frontend/test/run.mjs) | `d5751fa65990` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/search.test.mjs](../../frontend/test/search.test.mjs) | `49048bee6775` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/service-worker.test.mjs](../../frontend/test/service-worker.test.mjs) | `2edf8e1f4213` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/session-group.test.mjs](../../frontend/test/session-group.test.mjs) | `a7592bdefd77` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/settings.test.mjs](../../frontend/test/settings.test.mjs) | `bde4203be110` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/sidebar.test.mjs](../../frontend/test/sidebar.test.mjs) | `2167c45e15ec` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/stub-api.mjs](../../frontend/test/stub-api.mjs) | `dbb65dcfe08e` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/stub-csrf.mjs](../../frontend/test/stub-csrf.mjs) | `bccd5a884e1d` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/task-store.test.mjs](../../frontend/test/task-store.test.mjs) | `ff8f0bd49f5d` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/task-tags.test.mjs](../../frontend/test/task-tags.test.mjs) | `8f6d5cb654cf` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/theme.test.mjs](../../frontend/test/theme.test.mjs) | `06bd2fa6e4a6` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/throttle.test.mjs](../../frontend/test/throttle.test.mjs) | `e98b5bc9ab90` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/usage-chart.test.mjs](../../frontend/test/usage-chart.test.mjs) | `c112ccc5132d` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/visual-spec.test.mjs](../../frontend/test/visual-spec.test.mjs) | `957d31a1e405` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收
[frontend/test/voice-input.test.mjs](../../frontend/test/voice-input.test.mjs) | `b4e61c486058` | P8 | 复用现有前端；用 Go 后端执行契约与端到端验收

## java_build

基线文件 | SHA-256（前 12 位） | 阶段 | Go 去向 / 复用要求
--- | --- | --- | ---
[backend/.dockerignore](../../backend/.dockerignore) | `a414d70bf06d` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照
[backend/.mvn/wrapper/maven-wrapper.properties](../../backend/.mvn/wrapper/maven-wrapper.properties) | `488e1b3f2e64` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照
[backend/Dockerfile](../../backend/Dockerfile) | `dfac57bbdb08` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照
[backend/mvnw](../../backend/mvnw) | `cae96cef89eb` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照
[backend/mvnw.cmd](../../backend/mvnw.cmd) | `46eedb8419bd` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照
[backend/pom.xml](../../backend/pom.xml) | `9a507857d5da` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照

## java_resource

基线文件 | SHA-256（前 12 位） | 阶段 | Go 去向 / 复用要求
--- | --- | --- | ---
[backend/src/main/resources/META-INF/spring.factories](../../backend/src/main/resources/META-INF/spring.factories) | `4be5d340d294` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照
[backend/src/main/resources/application-monitoring.yml](../../backend/src/main/resources/application-monitoring.yml) | `faae22feba4c` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照
[backend/src/main/resources/application-prod.yml](../../backend/src/main/resources/application-prod.yml) | `5d82063a7736` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照
[backend/src/main/resources/application-runtime-api.yml](../../backend/src/main/resources/application-runtime-api.yml) | `909ee56a6cac` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照
[backend/src/main/resources/application-worker-delivery.yml](../../backend/src/main/resources/application-worker-delivery.yml) | `276677be13f4` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照
[backend/src/main/resources/application-worker-index.yml](../../backend/src/main/resources/application-worker-index.yml) | `20252ecfd193` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照
[backend/src/main/resources/application-worker-review.yml](../../backend/src/main/resources/application-worker-review.yml) | `a9761a80024e` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照
[backend/src/main/resources/application.yml](../../backend/src/main/resources/application.yml) | `068bc97f8ec2` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照

## java_source

基线文件 | SHA-256（前 12 位） | 阶段 | Go 去向 / 复用要求
--- | --- | --- | ---
[backend/src/main/java/com/personalos/PersonalOsApplication.java](../../backend/src/main/java/com/personalos/PersonalOsApplication.java) | `f64ff310cd12` | P1 | backend-go/internal/app/
[backend/src/main/java/com/personalos/agent/audit/ToolInvocationController.java](../../backend/src/main/java/com/personalos/agent/audit/ToolInvocationController.java) | `f428b309498e` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/audit/ToolInvocationDto.java](../../backend/src/main/java/com/personalos/agent/audit/ToolInvocationDto.java) | `2f582a362922` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/audit/ToolInvocationQuery.java](../../backend/src/main/java/com/personalos/agent/audit/ToolInvocationQuery.java) | `4406d88a2162` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/audit/ToolInvocationService.java](../../backend/src/main/java/com/personalos/agent/audit/ToolInvocationService.java) | `1619fe327b3e` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/interaction/dto/AgentFollowup.java](../../backend/src/main/java/com/personalos/agent/interaction/dto/AgentFollowup.java) | `1b231787a4de` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/interaction/dto/AgentQuestion.java](../../backend/src/main/java/com/personalos/agent/interaction/dto/AgentQuestion.java) | `5db4e4a8c8ea` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/interaction/repository/AgentInteractionRepository.java](../../backend/src/main/java/com/personalos/agent/interaction/repository/AgentInteractionRepository.java) | `93b90415d4fe` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/interaction/service/AgentInteractionService.java](../../backend/src/main/java/com/personalos/agent/interaction/service/AgentInteractionService.java) | `3e658f6253db` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/interaction/tool/AgentUserTools.java](../../backend/src/main/java/com/personalos/agent/interaction/tool/AgentUserTools.java) | `44f202b87401` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/package-info.java](../../backend/src/main/java/com/personalos/agent/package-info.java) | `fc1ef0d20ab2` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/PlanPromptContributor.java](../../backend/src/main/java/com/personalos/agent/plan/PlanPromptContributor.java) | `f8ba9b493545` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/PlanProperties.java](../../backend/src/main/java/com/personalos/agent/plan/PlanProperties.java) | `0436c17befcd` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/controller/AgentPlanController.java](../../backend/src/main/java/com/personalos/agent/plan/controller/AgentPlanController.java) | `edb46dae93d5` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/dto/PlanDto.java](../../backend/src/main/java/com/personalos/agent/plan/dto/PlanDto.java) | `a2cde60c28c8` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/dto/PlanStepDto.java](../../backend/src/main/java/com/personalos/agent/plan/dto/PlanStepDto.java) | `362801b3d2e0` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/entity/AgentPlanEntity.java](../../backend/src/main/java/com/personalos/agent/plan/entity/AgentPlanEntity.java) | `a32944b84f35` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/entity/AgentPlanStepEntity.java](../../backend/src/main/java/com/personalos/agent/plan/entity/AgentPlanStepEntity.java) | `bb15c3699aaa` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/entity/PlanStatus.java](../../backend/src/main/java/com/personalos/agent/plan/entity/PlanStatus.java) | `1c4b40f37424` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/entity/StepStatus.java](../../backend/src/main/java/com/personalos/agent/plan/entity/StepStatus.java) | `2eeeb75ece18` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/repository/AgentPlanRepository.java](../../backend/src/main/java/com/personalos/agent/plan/repository/AgentPlanRepository.java) | `0e6e88512c04` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/repository/AgentPlanStepRepository.java](../../backend/src/main/java/com/personalos/agent/plan/repository/AgentPlanStepRepository.java) | `fb314251b144` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/service/AgentPlanService.java](../../backend/src/main/java/com/personalos/agent/plan/service/AgentPlanService.java) | `ed33bd43e537` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/tool/AgentPlanToolContributor.java](../../backend/src/main/java/com/personalos/agent/plan/tool/AgentPlanToolContributor.java) | `264847664f2d` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/tool/AgentPlanTools.java](../../backend/src/main/java/com/personalos/agent/plan/tool/AgentPlanTools.java) | `3b9a46c3d843` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/plan/tool/PlanToolCallRecorder.java](../../backend/src/main/java/com/personalos/agent/plan/tool/PlanToolCallRecorder.java) | `f69177ec1d62` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/tool/TaskToolContributor.java](../../backend/src/main/java/com/personalos/agent/tool/TaskToolContributor.java) | `dc4c159abb4e` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/agent/tool/TaskTools.java](../../backend/src/main/java/com/personalos/agent/tool/TaskTools.java) | `3ef60f9beb7a` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/calendar/CalendarExportContributor.java](../../backend/src/main/java/com/personalos/calendar/CalendarExportContributor.java) | `a8f71c656530` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/CalendarSearchContributor.java](../../backend/src/main/java/com/personalos/calendar/CalendarSearchContributor.java) | `5698dbba1ed2` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/config/CalendarConfig.java](../../backend/src/main/java/com/personalos/calendar/config/CalendarConfig.java) | `1e4a4cf3f2ed` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/config/CalendarProperties.java](../../backend/src/main/java/com/personalos/calendar/config/CalendarProperties.java) | `2f1d82fe30a0` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/confirmation/CalendarConfirmationHandler.java](../../backend/src/main/java/com/personalos/calendar/confirmation/CalendarConfirmationHandler.java) | `bd14ed98d264` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/confirmation/CalendarConfirmationPayloads.java](../../backend/src/main/java/com/personalos/calendar/confirmation/CalendarConfirmationPayloads.java) | `4fb56f7eca34` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/confirmation/CalendarConfirmationService.java](../../backend/src/main/java/com/personalos/calendar/confirmation/CalendarConfirmationService.java) | `354341ed0bb7` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/controller/CalendarController.java](../../backend/src/main/java/com/personalos/calendar/controller/CalendarController.java) | `7c2011cd6920` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/controller/ReminderController.java](../../backend/src/main/java/com/personalos/calendar/controller/ReminderController.java) | `4f1da023df82` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/dto/CalendarEventDeletedDto.java](../../backend/src/main/java/com/personalos/calendar/dto/CalendarEventDeletedDto.java) | `65bb5c76d734` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/dto/CalendarEventDto.java](../../backend/src/main/java/com/personalos/calendar/dto/CalendarEventDto.java) | `22d091e00789` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/dto/CalendarOccurrenceDto.java](../../backend/src/main/java/com/personalos/calendar/dto/CalendarOccurrenceDto.java) | `72758e00cc26` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/dto/CreateEventRequest.java](../../backend/src/main/java/com/personalos/calendar/dto/CreateEventRequest.java) | `287f8dcd4b37` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/dto/ReminderDto.java](../../backend/src/main/java/com/personalos/calendar/dto/ReminderDto.java) | `fd6c04d66fb0` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/dto/ReminderScanResult.java](../../backend/src/main/java/com/personalos/calendar/dto/ReminderScanResult.java) | `8989370ed08e` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/dto/UpdateEventRequest.java](../../backend/src/main/java/com/personalos/calendar/dto/UpdateEventRequest.java) | `ec85d94bc111` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/entity/CalendarEventEntity.java](../../backend/src/main/java/com/personalos/calendar/entity/CalendarEventEntity.java) | `ca5d0c632ed3` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/entity/RecurrenceType.java](../../backend/src/main/java/com/personalos/calendar/entity/RecurrenceType.java) | `1257980bb0ba` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/entity/ReminderEntity.java](../../backend/src/main/java/com/personalos/calendar/entity/ReminderEntity.java) | `4a2666ff7cbf` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/entity/ReminderSource.java](../../backend/src/main/java/com/personalos/calendar/entity/ReminderSource.java) | `a5d026dfc152` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/entity/ReminderStatus.java](../../backend/src/main/java/com/personalos/calendar/entity/ReminderStatus.java) | `9d421d70d406` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/messaging/ReminderBroadcast.java](../../backend/src/main/java/com/personalos/calendar/messaging/ReminderBroadcast.java) | `6090f797326a` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/package-info.java](../../backend/src/main/java/com/personalos/calendar/package-info.java) | `88123357eda4` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/push/PushTransport.java](../../backend/src/main/java/com/personalos/calendar/push/PushTransport.java) | `d8eea993a339` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/push/WebPushConfiguration.java](../../backend/src/main/java/com/personalos/calendar/push/WebPushConfiguration.java) | `704f43568f61` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/push/WebPushController.java](../../backend/src/main/java/com/personalos/calendar/push/WebPushController.java) | `739ed671396e` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/push/WebPushRepository.java](../../backend/src/main/java/com/personalos/calendar/push/WebPushRepository.java) | `9bf12dac0004` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/push/WebPushScheduler.java](../../backend/src/main/java/com/personalos/calendar/push/WebPushScheduler.java) | `02929992f6ba` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/push/WebPushService.java](../../backend/src/main/java/com/personalos/calendar/push/WebPushService.java) | `25833201ac73` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/push/WebPushSettings.java](../../backend/src/main/java/com/personalos/calendar/push/WebPushSettings.java) | `3ed98e0b28a2` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/push/WebPushTransport.java](../../backend/src/main/java/com/personalos/calendar/push/WebPushTransport.java) | `0e438f52db54` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/recurrence/RecurrenceCalculator.java](../../backend/src/main/java/com/personalos/calendar/recurrence/RecurrenceCalculator.java) | `600e6511c41c` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/repository/CalendarEventRepository.java](../../backend/src/main/java/com/personalos/calendar/repository/CalendarEventRepository.java) | `514a15cee529` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/repository/ReminderRepository.java](../../backend/src/main/java/com/personalos/calendar/repository/ReminderRepository.java) | `eaa1805d17b6` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/scheduler/ReminderScheduler.java](../../backend/src/main/java/com/personalos/calendar/scheduler/ReminderScheduler.java) | `0872293f05cd` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/service/CalendarEventService.java](../../backend/src/main/java/com/personalos/calendar/service/CalendarEventService.java) | `767af14353fc` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/service/ReminderScanner.java](../../backend/src/main/java/com/personalos/calendar/service/ReminderScanner.java) | `f3304b7f565c` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/service/ReminderService.java](../../backend/src/main/java/com/personalos/calendar/service/ReminderService.java) | `3b83a9323f57` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/service/ReminderStreamDelivery.java](../../backend/src/main/java/com/personalos/calendar/service/ReminderStreamDelivery.java) | `640ff99ed1a7` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/tool/CalendarToolContributor.java](../../backend/src/main/java/com/personalos/calendar/tool/CalendarToolContributor.java) | `13e0ae01be67` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/tool/CalendarTools.java](../../backend/src/main/java/com/personalos/calendar/tool/CalendarTools.java) | `36905e0f4602` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/calendar/webhook/ReminderWebhookWorker.java](../../backend/src/main/java/com/personalos/calendar/webhook/ReminderWebhookWorker.java) | `3d8a8ea3da12` | P5 | backend-go/internal/calendar/
[backend/src/main/java/com/personalos/chat/ChatExportContributor.java](../../backend/src/main/java/com/personalos/chat/ChatExportContributor.java) | `23fbedbcb413` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/ChatSearchContributor.java](../../backend/src/main/java/com/personalos/chat/ChatSearchContributor.java) | `e0a4831cdd2f` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/controller/AudioTranscriptionController.java](../../backend/src/main/java/com/personalos/chat/controller/AudioTranscriptionController.java) | `15d2d581e010` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/controller/ChatAttachmentController.java](../../backend/src/main/java/com/personalos/chat/controller/ChatAttachmentController.java) | `2b99fcd52668` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/controller/ChatRunController.java](../../backend/src/main/java/com/personalos/chat/controller/ChatRunController.java) | `aaa74e7abb63` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java](../../backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java) | `4136408eb3c0` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/controller/UsageController.java](../../backend/src/main/java/com/personalos/chat/controller/UsageController.java) | `5f5f88bf7ecb` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/dto/ChatAttachmentDto.java](../../backend/src/main/java/com/personalos/chat/dto/ChatAttachmentDto.java) | `fcbeb809bf9a` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/dto/ChatCitationDto.java](../../backend/src/main/java/com/personalos/chat/dto/ChatCitationDto.java) | `57f3bf6b8e55` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/dto/ChatContextUsageDto.java](../../backend/src/main/java/com/personalos/chat/dto/ChatContextUsageDto.java) | `ae8bd3787d13` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/dto/ChatMessageDto.java](../../backend/src/main/java/com/personalos/chat/dto/ChatMessageDto.java) | `8fd48c3b05f2` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/dto/ChatRunDto.java](../../backend/src/main/java/com/personalos/chat/dto/ChatRunDto.java) | `13b1dcb0e989` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/dto/ChatSessionDto.java](../../backend/src/main/java/com/personalos/chat/dto/ChatSessionDto.java) | `7b06bf609d70` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/dto/ChatSessionQuery.java](../../backend/src/main/java/com/personalos/chat/dto/ChatSessionQuery.java) | `3944b8f86637` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/dto/ChatStreamEvents.java](../../backend/src/main/java/com/personalos/chat/dto/ChatStreamEvents.java) | `ddbba9108b3c` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/dto/CreateSessionRequest.java](../../backend/src/main/java/com/personalos/chat/dto/CreateSessionRequest.java) | `29a08e765e73` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/dto/RenameSessionRequest.java](../../backend/src/main/java/com/personalos/chat/dto/RenameSessionRequest.java) | `59bcd24106b2` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/dto/SetSessionModelRequest.java](../../backend/src/main/java/com/personalos/chat/dto/SetSessionModelRequest.java) | `48e3c66555e5` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/dto/StreamChatRequest.java](../../backend/src/main/java/com/personalos/chat/dto/StreamChatRequest.java) | `a2dafd099d4b` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/dto/ThinkingLevel.java](../../backend/src/main/java/com/personalos/chat/dto/ThinkingLevel.java) | `cede6c3c1ba8` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/dto/UsageStatsDto.java](../../backend/src/main/java/com/personalos/chat/dto/UsageStatsDto.java) | `9277ec97433c` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/entity/ChatMessageCitationEntity.java](../../backend/src/main/java/com/personalos/chat/entity/ChatMessageCitationEntity.java) | `9c59355d3655` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/entity/ChatMessageEntity.java](../../backend/src/main/java/com/personalos/chat/entity/ChatMessageEntity.java) | `610d550f21b7` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/entity/ChatSessionEntity.java](../../backend/src/main/java/com/personalos/chat/entity/ChatSessionEntity.java) | `58f20fe09829` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/entity/MessageRole.java](../../backend/src/main/java/com/personalos/chat/entity/MessageRole.java) | `e446181f5e7c` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/entity/TokenUsageEntity.java](../../backend/src/main/java/com/personalos/chat/entity/TokenUsageEntity.java) | `fa4c13b68c27` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/entity/TokenUsageSource.java](../../backend/src/main/java/com/personalos/chat/entity/TokenUsageSource.java) | `35df0a54691b` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/memory/JpaChatMemory.java](../../backend/src/main/java/com/personalos/chat/memory/JpaChatMemory.java) | `bdb6fcea2f76` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/metrics/ChatStreamMetrics.java](../../backend/src/main/java/com/personalos/chat/metrics/ChatStreamMetrics.java) | `0ddf986bb3bd` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/package-info.java](../../backend/src/main/java/com/personalos/chat/package-info.java) | `25d1548e634c` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/repository/ChatMessageCitationRepository.java](../../backend/src/main/java/com/personalos/chat/repository/ChatMessageCitationRepository.java) | `3172bd32665d` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/repository/ChatMessageRepository.java](../../backend/src/main/java/com/personalos/chat/repository/ChatMessageRepository.java) | `28d682d8b8d8` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/repository/ChatRunRepository.java](../../backend/src/main/java/com/personalos/chat/repository/ChatRunRepository.java) | `a7ac41d08c1e` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/repository/ChatSessionRepository.java](../../backend/src/main/java/com/personalos/chat/repository/ChatSessionRepository.java) | `04b96f994483` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/repository/TokenUsageRepository.java](../../backend/src/main/java/com/personalos/chat/repository/TokenUsageRepository.java) | `311d3007fcea` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/ChatAttachmentContext.java](../../backend/src/main/java/com/personalos/chat/service/ChatAttachmentContext.java) | `96691006535d` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/ChatAttachmentContextBuilder.java](../../backend/src/main/java/com/personalos/chat/service/ChatAttachmentContextBuilder.java) | `b7cf48df954b` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/ChatAttachmentJanitor.java](../../backend/src/main/java/com/personalos/chat/service/ChatAttachmentJanitor.java) | `ad2ce017c2cc` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/ChatAttachmentService.java](../../backend/src/main/java/com/personalos/chat/service/ChatAttachmentService.java) | `75fe9a399498` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/ChatCitationService.java](../../backend/src/main/java/com/personalos/chat/service/ChatCitationService.java) | `ce53f963db8e` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/ChatCompletionService.java](../../backend/src/main/java/com/personalos/chat/service/ChatCompletionService.java) | `400368d26cd5` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/ChatMarkdownExporter.java](../../backend/src/main/java/com/personalos/chat/service/ChatMarkdownExporter.java) | `507da43bd310` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/ChatMessageService.java](../../backend/src/main/java/com/personalos/chat/service/ChatMessageService.java) | `a823fa37fb6c` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/ChatPromptAssembler.java](../../backend/src/main/java/com/personalos/chat/service/ChatPromptAssembler.java) | `c49b681434b4` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/ChatRunService.java](../../backend/src/main/java/com/personalos/chat/service/ChatRunService.java) | `0302e1107a4d` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/ChatSessionService.java](../../backend/src/main/java/com/personalos/chat/service/ChatSessionService.java) | `e0ccc3c178ea` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/ChatSummaryService.java](../../backend/src/main/java/com/personalos/chat/service/ChatSummaryService.java) | `8d31c32671ba` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/ChatTurnFinisher.java](../../backend/src/main/java/com/personalos/chat/service/ChatTurnFinisher.java) | `e51d45bb02ab` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/RunEmitter.java](../../backend/src/main/java/com/personalos/chat/service/RunEmitter.java) | `f8b0984079a2` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/RunEventBuffer.java](../../backend/src/main/java/com/personalos/chat/service/RunEventBuffer.java) | `80665ad9b8d1` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/RunEventDelivery.java](../../backend/src/main/java/com/personalos/chat/service/RunEventDelivery.java) | `18b57cc096b5` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/RunEventHub.java](../../backend/src/main/java/com/personalos/chat/service/RunEventHub.java) | `250c31e1d02e` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/SummarySnapshot.java](../../backend/src/main/java/com/personalos/chat/service/SummarySnapshot.java) | `de4b16fa96b5` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/TokenUsageRecorder.java](../../backend/src/main/java/com/personalos/chat/service/TokenUsageRecorder.java) | `e4246c21392a` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/service/UsageStatsService.java](../../backend/src/main/java/com/personalos/chat/service/UsageStatsService.java) | `0a14929d4c09` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/storage/AttachmentObjectStore.java](../../backend/src/main/java/com/personalos/chat/storage/AttachmentObjectStore.java) | `42b1d05eb8fe` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/storage/S3AttachmentStore.java](../../backend/src/main/java/com/personalos/chat/storage/S3AttachmentStore.java) | `a497cb643b0e` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/storage/S3StorageConfig.java](../../backend/src/main/java/com/personalos/chat/storage/S3StorageConfig.java) | `8b48e0a2197c` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/support/ChatResponseInspection.java](../../backend/src/main/java/com/personalos/chat/support/ChatResponseInspection.java) | `0e6748c98223` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/support/SseHeartbeatScheduler.java](../../backend/src/main/java/com/personalos/chat/support/SseHeartbeatScheduler.java) | `3dc4523ad6ac` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/chat/support/StreamUsage.java](../../backend/src/main/java/com/personalos/chat/support/StreamUsage.java) | `b3a98e49b9ef` | P4 | backend-go/internal/chat/
[backend/src/main/java/com/personalos/common/ai/AgentControlSignal.java](../../backend/src/main/java/com/personalos/common/ai/AgentControlSignal.java) | `dc284951b554` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/AgentExecutionProperties.java](../../backend/src/main/java/com/personalos/common/ai/AgentExecutionProperties.java) | `c9b4cfc769d6` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/AgentToolCallingManager.java](../../backend/src/main/java/com/personalos/common/ai/AgentToolCallingManager.java) | `8f44641d059a` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/AgentTurnBudget.java](../../backend/src/main/java/com/personalos/common/ai/AgentTurnBudget.java) | `3a0da43408ce` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/AiChatClientProvider.java](../../backend/src/main/java/com/personalos/common/ai/AiChatClientProvider.java) | `a8d8ede02d39` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/AiProperties.java](../../backend/src/main/java/com/personalos/common/ai/AiProperties.java) | `a3955dd16afa` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/CalendarWriteGuardChatModel.java](../../backend/src/main/java/com/personalos/common/ai/CalendarWriteGuardChatModel.java) | `4b16926f0673` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/ContextBudgetPreferenceRepository.java](../../backend/src/main/java/com/personalos/common/ai/ContextBudgetPreferenceRepository.java) | `0f4667598b7e` | P4 | backend-go/internal/context/
[backend/src/main/java/com/personalos/common/ai/ContextBudgetService.java](../../backend/src/main/java/com/personalos/common/ai/ContextBudgetService.java) | `737a7f6c2b31` | P4 | backend-go/internal/context/
[backend/src/main/java/com/personalos/common/ai/ContextGuardChatModel.java](../../backend/src/main/java/com/personalos/common/ai/ContextGuardChatModel.java) | `8fc587ec1063` | P4 | backend-go/internal/context/
[backend/src/main/java/com/personalos/common/ai/ContextProperties.java](../../backend/src/main/java/com/personalos/common/ai/ContextProperties.java) | `cb04ec5342d7` | P4 | backend-go/internal/context/
[backend/src/main/java/com/personalos/common/ai/ContextProtectionProperties.java](../../backend/src/main/java/com/personalos/common/ai/ContextProtectionProperties.java) | `6a4521e1a8eb` | P4 | backend-go/internal/context/
[backend/src/main/java/com/personalos/common/ai/ContextWindowEvidence.java](../../backend/src/main/java/com/personalos/common/ai/ContextWindowEvidence.java) | `01985f7e810b` | P4 | backend-go/internal/context/
[backend/src/main/java/com/personalos/common/ai/CurrentTimePromptContributor.java](../../backend/src/main/java/com/personalos/common/ai/CurrentTimePromptContributor.java) | `7c75f743dde4` | P4 | backend-go/internal/context/
[backend/src/main/java/com/personalos/common/ai/ModelContextWindows.java](../../backend/src/main/java/com/personalos/common/ai/ModelContextWindows.java) | `832939f68382` | P4 | backend-go/internal/context/
[backend/src/main/java/com/personalos/common/ai/ModelWindowReferences.java](../../backend/src/main/java/com/personalos/common/ai/ModelWindowReferences.java) | `767795aba03c` | P4 | backend-go/internal/context/
[backend/src/main/java/com/personalos/common/ai/PostTurnContributor.java](../../backend/src/main/java/com/personalos/common/ai/PostTurnContributor.java) | `36915ae7a8c4` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/PromptContributor.java](../../backend/src/main/java/com/personalos/common/ai/PromptContributor.java) | `2d8cf3bcf25a` | P4 | backend-go/internal/context/
[backend/src/main/java/com/personalos/common/ai/PromptTokenCounter.java](../../backend/src/main/java/com/personalos/common/ai/PromptTokenCounter.java) | `f6fca4af9007` | P4 | backend-go/internal/context/
[backend/src/main/java/com/personalos/common/ai/RuntimeChatModelResolver.java](../../backend/src/main/java/com/personalos/common/ai/RuntimeChatModelResolver.java) | `c2766a779617` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/TokenEstimation.java](../../backend/src/main/java/com/personalos/common/ai/TokenEstimation.java) | `40b7cf8711aa` | P4 | backend-go/internal/context/
[backend/src/main/java/com/personalos/common/ai/ToolContextKeys.java](../../backend/src/main/java/com/personalos/common/ai/ToolContextKeys.java) | `6eee180da5ff` | P4 | backend-go/internal/context/
[backend/src/main/java/com/personalos/common/ai/ToolContributor.java](../../backend/src/main/java/com/personalos/common/ai/ToolContributor.java) | `f33f24250283` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/ToolResultPromptContributor.java](../../backend/src/main/java/com/personalos/common/ai/ToolResultPromptContributor.java) | `1ba3c253a372` | P4 | backend-go/internal/context/
[backend/src/main/java/com/personalos/common/ai/ToolSchemaOverhead.java](../../backend/src/main/java/com/personalos/common/ai/ToolSchemaOverhead.java) | `874e7b35445d` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/cache/CachedEmbeddingModel.java](../../backend/src/main/java/com/personalos/common/ai/cache/CachedEmbeddingModel.java) | `fd2c4b652cd3` | P3 | backend-go/internal/platform/cache/
[backend/src/main/java/com/personalos/common/ai/cache/EmbeddingCacheConfig.java](../../backend/src/main/java/com/personalos/common/ai/cache/EmbeddingCacheConfig.java) | `422c4f1caf8d` | P3 | backend-go/internal/platform/cache/
[backend/src/main/java/com/personalos/common/ai/cache/RedisCacheConfig.java](../../backend/src/main/java/com/personalos/common/ai/cache/RedisCacheConfig.java) | `862f2b5eb56e` | P3 | backend-go/internal/platform/cache/
[backend/src/main/java/com/personalos/common/ai/cache/RedisEmbeddingCache.java](../../backend/src/main/java/com/personalos/common/ai/cache/RedisEmbeddingCache.java) | `36234f62fe2c` | P3 | backend-go/internal/platform/cache/
[backend/src/main/java/com/personalos/common/ai/cache/SharedEmbeddingCache.java](../../backend/src/main/java/com/personalos/common/ai/cache/SharedEmbeddingCache.java) | `7fc13dcde43a` | P3 | backend-go/internal/platform/cache/
[backend/src/main/java/com/personalos/common/ai/config/AiModelConfigEntity.java](../../backend/src/main/java/com/personalos/common/ai/config/AiModelConfigEntity.java) | `2178b422c232` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/AiModelConfigRepository.java](../../backend/src/main/java/com/personalos/common/ai/config/AiModelConfigRepository.java) | `838f5efc4a6b` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/AiModelProfileEntity.java](../../backend/src/main/java/com/personalos/common/ai/config/AiModelProfileEntity.java) | `4492ea659851` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/AiModelProfileModelEntity.java](../../backend/src/main/java/com/personalos/common/ai/config/AiModelProfileModelEntity.java) | `69c56a9d51e1` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/AiModelProfileModelRepository.java](../../backend/src/main/java/com/personalos/common/ai/config/AiModelProfileModelRepository.java) | `f86ce6b91fdf` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/AiModelProfileRepository.java](../../backend/src/main/java/com/personalos/common/ai/config/AiModelProfileRepository.java) | `81b84b915865` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/AudioTranscriptionService.java](../../backend/src/main/java/com/personalos/common/ai/config/AudioTranscriptionService.java) | `ad337f51db78` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/ContextWindowRegistrar.java](../../backend/src/main/java/com/personalos/common/ai/config/ContextWindowRegistrar.java) | `d5a7fea30423` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/ModelConfigController.java](../../backend/src/main/java/com/personalos/common/ai/config/ModelConfigController.java) | `3f37c013aaea` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/ModelConfigService.java](../../backend/src/main/java/com/personalos/common/ai/config/ModelConfigService.java) | `5cedd32b895d` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/ModelConfigStartupApplier.java](../../backend/src/main/java/com/personalos/common/ai/config/ModelConfigStartupApplier.java) | `f17c22805c1c` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/ModelConnectionTestService.java](../../backend/src/main/java/com/personalos/common/ai/config/ModelConnectionTestService.java) | `e9495503b434` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/ModelKeyConverter.java](../../backend/src/main/java/com/personalos/common/ai/config/ModelKeyConverter.java) | `e9b6df5c77ae` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/ModelListProbeService.java](../../backend/src/main/java/com/personalos/common/ai/config/ModelListProbeService.java) | `bc3abf6908e0` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/ModelProfileController.java](../../backend/src/main/java/com/personalos/common/ai/config/ModelProfileController.java) | `adc00a21cc1c` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/ModelProfileService.java](../../backend/src/main/java/com/personalos/common/ai/config/ModelProfileService.java) | `71a1c9e83706` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/ModelProtocol.java](../../backend/src/main/java/com/personalos/common/ai/config/ModelProtocol.java) | `65582dd85316` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/OpenAiChatModelFactory.java](../../backend/src/main/java/com/personalos/common/ai/config/OpenAiChatModelFactory.java) | `889652f9747c` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/dto/ModelConfigDto.java](../../backend/src/main/java/com/personalos/common/ai/config/dto/ModelConfigDto.java) | `089f207c7bb2` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/dto/ModelConnectionTestResult.java](../../backend/src/main/java/com/personalos/common/ai/config/dto/ModelConnectionTestResult.java) | `1837403acb61` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/dto/ModelListResult.java](../../backend/src/main/java/com/personalos/common/ai/config/dto/ModelListResult.java) | `64ed7388a881` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/dto/ModelProfileDto.java](../../backend/src/main/java/com/personalos/common/ai/config/dto/ModelProfileDto.java) | `9c873f3de582` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/dto/ModelProfileListDto.java](../../backend/src/main/java/com/personalos/common/ai/config/dto/ModelProfileListDto.java) | `54b89ca21f29` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/dto/ProbeModelsRequest.java](../../backend/src/main/java/com/personalos/common/ai/config/dto/ProbeModelsRequest.java) | `59dfc897e920` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/dto/ProbeWindowRequest.java](../../backend/src/main/java/com/personalos/common/ai/config/dto/ProbeWindowRequest.java) | `1ed3272a68cc` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/dto/ReorderModelProfilesRequest.java](../../backend/src/main/java/com/personalos/common/ai/config/dto/ReorderModelProfilesRequest.java) | `0f34b5a5d5c7` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/dto/SaveModelConfigRequest.java](../../backend/src/main/java/com/personalos/common/ai/config/dto/SaveModelConfigRequest.java) | `5b1499fb30ec` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/dto/SaveModelProfileRequest.java](../../backend/src/main/java/com/personalos/common/ai/config/dto/SaveModelProfileRequest.java) | `b5a591d6ff4b` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/config/dto/TranscriptionResult.java](../../backend/src/main/java/com/personalos/common/ai/config/dto/TranscriptionResult.java) | `47d5d16b542a` | P3 | backend-go/internal/model/
[backend/src/main/java/com/personalos/common/ai/tool/ToolArguments.java](../../backend/src/main/java/com/personalos/common/ai/tool/ToolArguments.java) | `1c3f2a2e892a` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/tool/ToolCallRecorder.java](../../backend/src/main/java/com/personalos/common/ai/tool/ToolCallRecorder.java) | `77cfb28f07f0` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/tool/ToolExecutionResult.java](../../backend/src/main/java/com/personalos/common/ai/tool/ToolExecutionResult.java) | `7267d94ea657` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/tool/ToolExecutor.java](../../backend/src/main/java/com/personalos/common/ai/tool/ToolExecutor.java) | `782ef3b3ffc7` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/ai/tool/ToolProgress.java](../../backend/src/main/java/com/personalos/common/ai/tool/ToolProgress.java) | `50c9901eefb9` | P4 | backend-go/internal/agent/
[backend/src/main/java/com/personalos/common/api/ApiResponse.java](../../backend/src/main/java/com/personalos/common/api/ApiResponse.java) | `28667b149d0a` | P1 | backend-go/internal/platform/api/
[backend/src/main/java/com/personalos/common/api/ErrorCode.java](../../backend/src/main/java/com/personalos/common/api/ErrorCode.java) | `6307f7c9616c` | P1 | backend-go/internal/platform/api/
[backend/src/main/java/com/personalos/common/api/PageDto.java](../../backend/src/main/java/com/personalos/common/api/PageDto.java) | `ea81103590a6` | P1 | backend-go/internal/platform/api/
[backend/src/main/java/com/personalos/common/auth/AuthController.java](../../backend/src/main/java/com/personalos/common/auth/AuthController.java) | `37022789e630` | P1 | backend-go/internal/platform/auth/
[backend/src/main/java/com/personalos/common/auth/AuthProperties.java](../../backend/src/main/java/com/personalos/common/auth/AuthProperties.java) | `e8f9177f681e` | P1 | backend-go/internal/platform/auth/
[backend/src/main/java/com/personalos/common/auth/AuthService.java](../../backend/src/main/java/com/personalos/common/auth/AuthService.java) | `3eaf451c4528` | P1 | backend-go/internal/platform/auth/
[backend/src/main/java/com/personalos/common/auth/CsrfController.java](../../backend/src/main/java/com/personalos/common/auth/CsrfController.java) | `6e58606282df` | P1 | backend-go/internal/platform/auth/
[backend/src/main/java/com/personalos/common/auth/CurrentUserDto.java](../../backend/src/main/java/com/personalos/common/auth/CurrentUserDto.java) | `94cac4ba8b5f` | P1 | backend-go/internal/platform/auth/
[backend/src/main/java/com/personalos/common/auth/LoginRequest.java](../../backend/src/main/java/com/personalos/common/auth/LoginRequest.java) | `3822d3036e22` | P1 | backend-go/internal/platform/auth/
[backend/src/main/java/com/personalos/common/auth/MonitoringSecurityConfig.java](../../backend/src/main/java/com/personalos/common/auth/MonitoringSecurityConfig.java) | `958cbcea10dd` | P1 | backend-go/internal/platform/auth/
[backend/src/main/java/com/personalos/common/auth/SecurityConfig.java](../../backend/src/main/java/com/personalos/common/auth/SecurityConfig.java) | `5d33cf7f6dc0` | P1 | backend-go/internal/platform/auth/
[backend/src/main/java/com/personalos/common/auth/SharedSessionConfig.java](../../backend/src/main/java/com/personalos/common/auth/SharedSessionConfig.java) | `712b78acc554` | P1 | backend-go/internal/platform/auth/
[backend/src/main/java/com/personalos/common/config/AppProperties.java](../../backend/src/main/java/com/personalos/common/config/AppProperties.java) | `55b75e93c883` | P1 | backend-go/internal/platform/config/
[backend/src/main/java/com/personalos/common/config/ContextTaskConfig.java](../../backend/src/main/java/com/personalos/common/config/ContextTaskConfig.java) | `d772495dc006` | P1 | backend-go/internal/platform/config/
[backend/src/main/java/com/personalos/common/config/DotEnvEnvironmentPostProcessor.java](../../backend/src/main/java/com/personalos/common/config/DotEnvEnvironmentPostProcessor.java) | `165fea69776a` | P1 | backend-go/internal/platform/config/
[backend/src/main/java/com/personalos/common/config/ProductionSecurityValidator.java](../../backend/src/main/java/com/personalos/common/config/ProductionSecurityValidator.java) | `a20f3b51107d` | P1 | backend-go/internal/platform/config/
[backend/src/main/java/com/personalos/common/config/RateLimitProperties.java](../../backend/src/main/java/com/personalos/common/config/RateLimitProperties.java) | `50327e4e19e3` | P1 | backend-go/internal/platform/config/
[backend/src/main/java/com/personalos/common/config/RuntimeRole.java](../../backend/src/main/java/com/personalos/common/config/RuntimeRole.java) | `a11f56661ba5` | P1 | backend-go/internal/platform/config/
[backend/src/main/java/com/personalos/common/confirmation/ConfirmationAction.java](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationAction.java) | `447d19005ee0` | P2 | backend-go/internal/platform/confirmation/
[backend/src/main/java/com/personalos/common/confirmation/ConfirmationActionHandler.java](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationActionHandler.java) | `24004fd06723` | P2 | backend-go/internal/platform/confirmation/
[backend/src/main/java/com/personalos/common/confirmation/ConfirmationController.java](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationController.java) | `02482982cdf7` | P2 | backend-go/internal/platform/confirmation/
[backend/src/main/java/com/personalos/common/confirmation/ConfirmationDto.java](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationDto.java) | `84bf9baaa1a8` | P2 | backend-go/internal/platform/confirmation/
[backend/src/main/java/com/personalos/common/confirmation/ConfirmationEntity.java](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationEntity.java) | `f54e57dd67fd` | P2 | backend-go/internal/platform/confirmation/
[backend/src/main/java/com/personalos/common/confirmation/ConfirmationExecution.java](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationExecution.java) | `1e310dda5707` | P2 | backend-go/internal/platform/confirmation/
[backend/src/main/java/com/personalos/common/confirmation/ConfirmationPromptContributor.java](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationPromptContributor.java) | `75a31942322d` | P2 | backend-go/internal/platform/confirmation/
[backend/src/main/java/com/personalos/common/confirmation/ConfirmationRegistry.java](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationRegistry.java) | `4d93edc10d33` | P2 | backend-go/internal/platform/confirmation/
[backend/src/main/java/com/personalos/common/confirmation/ConfirmationRepository.java](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationRepository.java) | `1d24ba95ee4a` | P2 | backend-go/internal/platform/confirmation/
[backend/src/main/java/com/personalos/common/confirmation/ConfirmationService.java](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationService.java) | `4761b4edf27a` | P2 | backend-go/internal/platform/confirmation/
[backend/src/main/java/com/personalos/common/confirmation/ConfirmationStatus.java](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationStatus.java) | `5ecd3a9b7386` | P2 | backend-go/internal/platform/confirmation/
[backend/src/main/java/com/personalos/common/confirmation/ConfirmationTokenRequest.java](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationTokenRequest.java) | `0f69025183d8` | P2 | backend-go/internal/platform/confirmation/
[backend/src/main/java/com/personalos/common/document/PlainTextExtractor.java](../../backend/src/main/java/com/personalos/common/document/PlainTextExtractor.java) | `fc83064d32b7` | P1 | backend-go/internal/platform/document/
[backend/src/main/java/com/personalos/common/exception/ApiErrorController.java](../../backend/src/main/java/com/personalos/common/exception/ApiErrorController.java) | `4f5befc245ed` | P1 | backend-go/internal/platform/exception/
[backend/src/main/java/com/personalos/common/exception/BusinessException.java](../../backend/src/main/java/com/personalos/common/exception/BusinessException.java) | `68a7f80359c4` | P1 | backend-go/internal/platform/exception/
[backend/src/main/java/com/personalos/common/exception/GlobalExceptionHandler.java](../../backend/src/main/java/com/personalos/common/exception/GlobalExceptionHandler.java) | `4d5c909f1872` | P1 | backend-go/internal/platform/exception/
[backend/src/main/java/com/personalos/common/exception/ResourceNotFoundException.java](../../backend/src/main/java/com/personalos/common/exception/ResourceNotFoundException.java) | `1cfbb9609378` | P1 | backend-go/internal/platform/exception/
[backend/src/main/java/com/personalos/common/health/HealthController.java](../../backend/src/main/java/com/personalos/common/health/HealthController.java) | `c9666093628a` | P1 | backend-go/internal/platform/health/
[backend/src/main/java/com/personalos/common/health/HealthService.java](../../backend/src/main/java/com/personalos/common/health/HealthService.java) | `ecf4650c82d6` | P1 | backend-go/internal/platform/health/
[backend/src/main/java/com/personalos/common/health/HealthStatusDto.java](../../backend/src/main/java/com/personalos/common/health/HealthStatusDto.java) | `7c8d62cda258` | P1 | backend-go/internal/platform/health/
[backend/src/main/java/com/personalos/common/idempotency/IdempotencyOutcome.java](../../backend/src/main/java/com/personalos/common/idempotency/IdempotencyOutcome.java) | `6e3b52728168` | P2 | backend-go/internal/platform/idempotency/
[backend/src/main/java/com/personalos/common/idempotency/IdempotencyRequest.java](../../backend/src/main/java/com/personalos/common/idempotency/IdempotencyRequest.java) | `407b4cc46863` | P2 | backend-go/internal/platform/idempotency/
[backend/src/main/java/com/personalos/common/idempotency/IdempotencyService.java](../../backend/src/main/java/com/personalos/common/idempotency/IdempotencyService.java) | `31baf9ea926b` | P2 | backend-go/internal/platform/idempotency/
[backend/src/main/java/com/personalos/common/idempotency/IdempotentResult.java](../../backend/src/main/java/com/personalos/common/idempotency/IdempotentResult.java) | `b37b1c7fadae` | P2 | backend-go/internal/platform/idempotency/
[backend/src/main/java/com/personalos/common/idempotency/OperationRecordEntity.java](../../backend/src/main/java/com/personalos/common/idempotency/OperationRecordEntity.java) | `023a27e93bd1` | P2 | backend-go/internal/platform/idempotency/
[backend/src/main/java/com/personalos/common/idempotency/OperationRecordRepository.java](../../backend/src/main/java/com/personalos/common/idempotency/OperationRecordRepository.java) | `bf6564c6c192` | P2 | backend-go/internal/platform/idempotency/
[backend/src/main/java/com/personalos/common/idempotency/OperationSource.java](../../backend/src/main/java/com/personalos/common/idempotency/OperationSource.java) | `9b30a40742b3` | P2 | backend-go/internal/platform/idempotency/
[backend/src/main/java/com/personalos/common/json/JsonCodec.java](../../backend/src/main/java/com/personalos/common/json/JsonCodec.java) | `22022d0932e7` | P1 | backend-go/internal/platform/json/
[backend/src/main/java/com/personalos/common/maintenance/DataRetentionService.java](../../backend/src/main/java/com/personalos/common/maintenance/DataRetentionService.java) | `cd4dacb0ac9e` | P8 | backend-go/internal/platform/maintenance/
[backend/src/main/java/com/personalos/common/package-info.java](../../backend/src/main/java/com/personalos/common/package-info.java) | `b3175220c81e` | P1 | backend-go/internal/platform/package-info.java/
[backend/src/main/java/com/personalos/common/search/GlobalSearchResult.java](../../backend/src/main/java/com/personalos/common/search/GlobalSearchResult.java) | `c46f89562028` | P7 | backend-go/internal/search/
[backend/src/main/java/com/personalos/common/search/GlobalSearchService.java](../../backend/src/main/java/com/personalos/common/search/GlobalSearchService.java) | `f7a9dde41a12` | P7 | backend-go/internal/search/
[backend/src/main/java/com/personalos/common/search/SearchContributor.java](../../backend/src/main/java/com/personalos/common/search/SearchContributor.java) | `f6d3498eedc4` | P7 | backend-go/internal/search/
[backend/src/main/java/com/personalos/common/search/SearchController.java](../../backend/src/main/java/com/personalos/common/search/SearchController.java) | `60d183006565` | P7 | backend-go/internal/search/
[backend/src/main/java/com/personalos/common/search/SearchGroup.java](../../backend/src/main/java/com/personalos/common/search/SearchGroup.java) | `7a84a67ee27f` | P7 | backend-go/internal/search/
[backend/src/main/java/com/personalos/common/search/SearchHit.java](../../backend/src/main/java/com/personalos/common/search/SearchHit.java) | `d732f9d27053` | P7 | backend-go/internal/search/
[backend/src/main/java/com/personalos/common/search/SearchPatterns.java](../../backend/src/main/java/com/personalos/common/search/SearchPatterns.java) | `5bedb5bd7938` | P7 | backend-go/internal/search/
[backend/src/main/java/com/personalos/common/text/Texts.java](../../backend/src/main/java/com/personalos/common/text/Texts.java) | `615cd8eebee1` | P1 | backend-go/internal/platform/text/
[backend/src/main/java/com/personalos/common/web/DatabaseCoordination.java](../../backend/src/main/java/com/personalos/common/web/DatabaseCoordination.java) | `6d7e47734d02` | P1 | backend-go/internal/platform/web/
[backend/src/main/java/com/personalos/common/web/MdcPropagatingExecutor.java](../../backend/src/main/java/com/personalos/common/web/MdcPropagatingExecutor.java) | `68c9dc90259c` | P1 | backend-go/internal/platform/web/
[backend/src/main/java/com/personalos/common/web/MdcSchedulerHook.java](../../backend/src/main/java/com/personalos/common/web/MdcSchedulerHook.java) | `19157273f36e` | P1 | backend-go/internal/platform/web/
[backend/src/main/java/com/personalos/common/web/ModelConcurrencyFilter.java](../../backend/src/main/java/com/personalos/common/web/ModelConcurrencyFilter.java) | `f9714ccb8ff9` | P1 | backend-go/internal/platform/web/
[backend/src/main/java/com/personalos/common/web/ModelConcurrencyGate.java](../../backend/src/main/java/com/personalos/common/web/ModelConcurrencyGate.java) | `80630062fada` | P1 | backend-go/internal/platform/web/
[backend/src/main/java/com/personalos/common/web/ModelRequestPaths.java](../../backend/src/main/java/com/personalos/common/web/ModelRequestPaths.java) | `327671aa2d92` | P1 | backend-go/internal/platform/web/
[backend/src/main/java/com/personalos/common/web/RateLimitFilter.java](../../backend/src/main/java/com/personalos/common/web/RateLimitFilter.java) | `efbe375bbe1d` | P1 | backend-go/internal/platform/web/
[backend/src/main/java/com/personalos/common/web/RedisRateBucket.java](../../backend/src/main/java/com/personalos/common/web/RedisRateBucket.java) | `da8b2203cead` | P1 | backend-go/internal/platform/web/
[backend/src/main/java/com/personalos/common/web/RequestIdFilter.java](../../backend/src/main/java/com/personalos/common/web/RequestIdFilter.java) | `cf3cff34f705` | P1 | backend-go/internal/platform/web/
[backend/src/main/java/com/personalos/common/web/TokenBucketRateLimiter.java](../../backend/src/main/java/com/personalos/common/web/TokenBucketRateLimiter.java) | `52c69e4827ee` | P1 | backend-go/internal/platform/web/
[backend/src/main/java/com/personalos/common/web/WorkerIsolationFilter.java](../../backend/src/main/java/com/personalos/common/web/WorkerIsolationFilter.java) | `72d293c76121` | P1 | backend-go/internal/platform/web/
[backend/src/main/java/com/personalos/export/DataExportController.java](../../backend/src/main/java/com/personalos/export/DataExportController.java) | `ad7599fa95c5` | P7 | backend-go/internal/transfer/
[backend/src/main/java/com/personalos/export/DataExportService.java](../../backend/src/main/java/com/personalos/export/DataExportService.java) | `bb4b0bda48e4` | P7 | backend-go/internal/transfer/
[backend/src/main/java/com/personalos/export/DataImportController.java](../../backend/src/main/java/com/personalos/export/DataImportController.java) | `6177ef272064` | P7 | backend-go/internal/transfer/
[backend/src/main/java/com/personalos/export/DataImportService.java](../../backend/src/main/java/com/personalos/export/DataImportService.java) | `131e98c17a09` | P7 | backend-go/internal/transfer/
[backend/src/main/java/com/personalos/export/ExportContributor.java](../../backend/src/main/java/com/personalos/export/ExportContributor.java) | `c86eb987c62a` | P7 | backend-go/internal/transfer/
[backend/src/main/java/com/personalos/export/ExportWriter.java](../../backend/src/main/java/com/personalos/export/ExportWriter.java) | `3c934f1b0cca` | P7 | backend-go/internal/transfer/
[backend/src/main/java/com/personalos/export/ImportPayload.java](../../backend/src/main/java/com/personalos/export/ImportPayload.java) | `9bd7e140359d` | P7 | backend-go/internal/transfer/
[backend/src/main/java/com/personalos/knowledge/KnowledgeExportContributor.java](../../backend/src/main/java/com/personalos/knowledge/KnowledgeExportContributor.java) | `d957c79e0ab2` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/KnowledgeMetadata.java](../../backend/src/main/java/com/personalos/knowledge/KnowledgeMetadata.java) | `5ebbf8318ee4` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/KnowledgeSearchContributor.java](../../backend/src/main/java/com/personalos/knowledge/KnowledgeSearchContributor.java) | `0d23c2463d20` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/config/KnowledgeProperties.java](../../backend/src/main/java/com/personalos/knowledge/config/KnowledgeProperties.java) | `8918e7b94d4e` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/controller/KnowledgeController.java](../../backend/src/main/java/com/personalos/knowledge/controller/KnowledgeController.java) | `10843600a0ad` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/dto/CitationDto.java](../../backend/src/main/java/com/personalos/knowledge/dto/CitationDto.java) | `a5493fa58a38` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/dto/KnowledgeAskRequest.java](../../backend/src/main/java/com/personalos/knowledge/dto/KnowledgeAskRequest.java) | `a4924b46db6b` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/dto/KnowledgeAskResponse.java](../../backend/src/main/java/com/personalos/knowledge/dto/KnowledgeAskResponse.java) | `aa82642eb1cc` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/dto/KnowledgeDocumentDeletedDto.java](../../backend/src/main/java/com/personalos/knowledge/dto/KnowledgeDocumentDeletedDto.java) | `f44b32cb7dcc` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/dto/KnowledgeDocumentDto.java](../../backend/src/main/java/com/personalos/knowledge/dto/KnowledgeDocumentDto.java) | `648e82a46fac` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/dto/KnowledgeDocumentQuery.java](../../backend/src/main/java/com/personalos/knowledge/dto/KnowledgeDocumentQuery.java) | `ed39d16dd720` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/dto/KnowledgeHitDto.java](../../backend/src/main/java/com/personalos/knowledge/dto/KnowledgeHitDto.java) | `003f1c12da26` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/dto/KnowledgeSearchRequest.java](../../backend/src/main/java/com/personalos/knowledge/dto/KnowledgeSearchRequest.java) | `3c30e6f5c2ed` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/dto/KnowledgeSearchResponse.java](../../backend/src/main/java/com/personalos/knowledge/dto/KnowledgeSearchResponse.java) | `6c13653d9dc5` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/entity/KnowledgeDocumentEntity.java](../../backend/src/main/java/com/personalos/knowledge/entity/KnowledgeDocumentEntity.java) | `3b3ec15b32b6` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/governance/GovernanceController.java](../../backend/src/main/java/com/personalos/knowledge/governance/GovernanceController.java) | `e992a669b0e4` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/governance/IngestionReviewWorker.java](../../backend/src/main/java/com/personalos/knowledge/governance/IngestionReviewWorker.java) | `ca028adb3ddf` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/governance/KnowledgeGovernance.java](../../backend/src/main/java/com/personalos/knowledge/governance/KnowledgeGovernance.java) | `3a1df0ff883a` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/governance/SourceInfo.java](../../backend/src/main/java/com/personalos/knowledge/governance/SourceInfo.java) | `c07216d5a3b7` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeIndexJobs.java](../../backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeIndexJobs.java) | `15519d6b024b` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeJobController.java](../../backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeJobController.java) | `170c8eac1087` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeJobNotifier.java](../../backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeJobNotifier.java) | `79b814bbcc55` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeJobRepository.java](../../backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeJobRepository.java) | `b74fe848daa3` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/jobs/messaging/RabbitJobNotifier.java](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/RabbitJobNotifier.java) | `72ba4897e373` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/jobs/messaging/RabbitJobsConfig.java](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/RabbitJobsConfig.java) | `92f210f0235e` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/jobs/messaging/WorkSignalRelay.java](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/WorkSignalRelay.java) | `59b48f44f4d5` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/jobs/messaging/WorkerWakeupsConfig.java](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/WorkerWakeupsConfig.java) | `72a6b503d043` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/package-info.java](../../backend/src/main/java/com/personalos/knowledge/package-info.java) | `b4adae2e6a1f` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/rag/CrossEncoderReranker.java](../../backend/src/main/java/com/personalos/knowledge/rag/CrossEncoderReranker.java) | `c15d9711b5ca` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/rag/EvidenceReview.java](../../backend/src/main/java/com/personalos/knowledge/rag/EvidenceReview.java) | `d37a6ee7fc65` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/rag/RagModelGateway.java](../../backend/src/main/java/com/personalos/knowledge/rag/RagModelGateway.java) | `465274816d08` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/rag/RagResult.java](../../backend/src/main/java/com/personalos/knowledge/rag/RagResult.java) | `9e46a92193e3` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/rag/RagRetrievalPipeline.java](../../backend/src/main/java/com/personalos/knowledge/rag/RagRetrievalPipeline.java) | `eec047083a8a` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/reader/ChatClientVisionTextExtractor.java](../../backend/src/main/java/com/personalos/knowledge/reader/ChatClientVisionTextExtractor.java) | `62a01f1b35ee` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/reader/DocumentReaderFactory.java](../../backend/src/main/java/com/personalos/knowledge/reader/DocumentReaderFactory.java) | `5a63f5a9858f` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/reader/DocxDocumentReader.java](../../backend/src/main/java/com/personalos/knowledge/reader/DocxDocumentReader.java) | `e088a22b78af` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/reader/ImageDocumentReader.java](../../backend/src/main/java/com/personalos/knowledge/reader/ImageDocumentReader.java) | `eeadb6f73ada` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/reader/KnowledgeDocumentReader.java](../../backend/src/main/java/com/personalos/knowledge/reader/KnowledgeDocumentReader.java) | `dc0ac59d6d23` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/reader/PdfDocumentReader.java](../../backend/src/main/java/com/personalos/knowledge/reader/PdfDocumentReader.java) | `f6b224bb538c` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/reader/ReaderSupport.java](../../backend/src/main/java/com/personalos/knowledge/reader/ReaderSupport.java) | `3acef3c01df4` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/reader/VisionTextExtractor.java](../../backend/src/main/java/com/personalos/knowledge/reader/VisionTextExtractor.java) | `7635bf92d061` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/repository/KnowledgeDocumentRepository.java](../../backend/src/main/java/com/personalos/knowledge/repository/KnowledgeDocumentRepository.java) | `518179abe3bb` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/search/OpenSearchGateway.java](../../backend/src/main/java/com/personalos/knowledge/search/OpenSearchGateway.java) | `f209de696f3b` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/search/SearchAdminController.java](../../backend/src/main/java/com/personalos/knowledge/search/SearchAdminController.java) | `f2cfddcff68a` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/search/SearchConfig.java](../../backend/src/main/java/com/personalos/knowledge/search/SearchConfig.java) | `0dc19e885aa2` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/search/SearchProjection.java](../../backend/src/main/java/com/personalos/knowledge/search/SearchProjection.java) | `fdbcb78e0cab` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/service/DocumentFilenameSupport.java](../../backend/src/main/java/com/personalos/knowledge/service/DocumentFilenameSupport.java) | `960b1bbf84bc` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/service/DocumentWriteLock.java](../../backend/src/main/java/com/personalos/knowledge/service/DocumentWriteLock.java) | `c7f7e504dcbb` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/service/KeywordExtractor.java](../../backend/src/main/java/com/personalos/knowledge/service/KeywordExtractor.java) | `df3c1bc8bdab` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/service/KnowledgeDocumentService.java](../../backend/src/main/java/com/personalos/knowledge/service/KnowledgeDocumentService.java) | `0035b1bd41b9` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/service/KnowledgeQaService.java](../../backend/src/main/java/com/personalos/knowledge/service/KnowledgeQaService.java) | `1ed3f51f227b` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/service/KnowledgeRetrievalService.java](../../backend/src/main/java/com/personalos/knowledge/service/KnowledgeRetrievalService.java) | `544d3e9db4f2` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/service/RedisCandidateCache.java](../../backend/src/main/java/com/personalos/knowledge/service/RedisCandidateCache.java) | `a5206d6fa8f1` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/service/VectorStoreInspector.java](../../backend/src/main/java/com/personalos/knowledge/service/VectorStoreInspector.java) | `bd0e9e2d9a06` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/spi/KnowledgeSearchPort.java](../../backend/src/main/java/com/personalos/knowledge/spi/KnowledgeSearchPort.java) | `64a0258f80d8` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/splitter/MarkdownStructureTextSplitter.java](../../backend/src/main/java/com/personalos/knowledge/splitter/MarkdownStructureTextSplitter.java) | `e90d9ca71b33` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/startup/VectorDimensionValidator.java](../../backend/src/main/java/com/personalos/knowledge/startup/VectorDimensionValidator.java) | `3111680ef2be` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/tool/KnowledgeToolContributor.java](../../backend/src/main/java/com/personalos/knowledge/tool/KnowledgeToolContributor.java) | `ec704ba67c89` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/knowledge/tool/KnowledgeTools.java](../../backend/src/main/java/com/personalos/knowledge/tool/KnowledgeTools.java) | `70eb72e84e8b` | P6 | backend-go/internal/knowledge/
[backend/src/main/java/com/personalos/mcp/client/McpClientRegistry.java](../../backend/src/main/java/com/personalos/mcp/client/McpClientRegistry.java) | `e2606d539d35` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/client/McpServerStatus.java](../../backend/src/main/java/com/personalos/mcp/client/McpServerStatus.java) | `564fd4a5ac09` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/config/McpConfigValidator.java](../../backend/src/main/java/com/personalos/mcp/config/McpConfigValidator.java) | `5d8c83655183` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/config/McpProperties.java](../../backend/src/main/java/com/personalos/mcp/config/McpProperties.java) | `ed7f139e92a7` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/config/McpToolConfig.java](../../backend/src/main/java/com/personalos/mcp/config/McpToolConfig.java) | `c6bae100c169` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/config/McpToolConflictValidator.java](../../backend/src/main/java/com/personalos/mcp/config/McpToolConflictValidator.java) | `00bd01b234f2` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/confirmation/McpConfirmationHandler.java](../../backend/src/main/java/com/personalos/mcp/confirmation/McpConfirmationHandler.java) | `68e46b91a1a5` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/confirmation/McpConfirmationService.java](../../backend/src/main/java/com/personalos/mcp/confirmation/McpConfirmationService.java) | `9da6b471d674` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/confirmation/McpConfirmationSummary.java](../../backend/src/main/java/com/personalos/mcp/confirmation/McpConfirmationSummary.java) | `aee75a269adb` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/confirmation/McpToolCall.java](../../backend/src/main/java/com/personalos/mcp/confirmation/McpToolCall.java) | `134409888419` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/package-info.java](../../backend/src/main/java/com/personalos/mcp/package-info.java) | `89bf0419b16f` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/server/PersonalMcpTools.java](../../backend/src/main/java/com/personalos/mcp/server/PersonalMcpTools.java) | `ec565e44ebef` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/status/McpServerStatusDto.java](../../backend/src/main/java/com/personalos/mcp/status/McpServerStatusDto.java) | `f261f8a798a5` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/status/McpStatusController.java](../../backend/src/main/java/com/personalos/mcp/status/McpStatusController.java) | `e5b3221c8c5e` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/status/McpStatusDto.java](../../backend/src/main/java/com/personalos/mcp/status/McpStatusDto.java) | `5673dba064c6` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/status/McpStatusService.java](../../backend/src/main/java/com/personalos/mcp/status/McpStatusService.java) | `199403c4721f` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/status/PersonalMcpStatusDto.java](../../backend/src/main/java/com/personalos/mcp/status/PersonalMcpStatusDto.java) | `7c7a1cc483b5` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/status/PersonalMcpToolDto.java](../../backend/src/main/java/com/personalos/mcp/status/PersonalMcpToolDto.java) | `5fd8fa72f484` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/tool/McpToolCallback.java](../../backend/src/main/java/com/personalos/mcp/tool/McpToolCallback.java) | `975be9d00624` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/tool/McpToolCallbackProvider.java](../../backend/src/main/java/com/personalos/mcp/tool/McpToolCallbackProvider.java) | `345e44c927e8` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/tool/McpToolNaming.java](../../backend/src/main/java/com/personalos/mcp/tool/McpToolNaming.java) | `b7588c11b58c` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/mcp/tool/McpToolPolicy.java](../../backend/src/main/java/com/personalos/mcp/tool/McpToolPolicy.java) | `b4ba16b62274` | P7 | backend-go/internal/mcp/
[backend/src/main/java/com/personalos/memory/MemoryEmbeddingBackfill.java](../../backend/src/main/java/com/personalos/memory/MemoryEmbeddingBackfill.java) | `0b6131f2e0d3` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/MemoryExportContributor.java](../../backend/src/main/java/com/personalos/memory/MemoryExportContributor.java) | `6651388cbd92` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/MemoryPromptContributor.java](../../backend/src/main/java/com/personalos/memory/MemoryPromptContributor.java) | `af5bce4150d8` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/MemoryProperties.java](../../backend/src/main/java/com/personalos/memory/MemoryProperties.java) | `385e694f1f0c` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/MemorySearchContributor.java](../../backend/src/main/java/com/personalos/memory/MemorySearchContributor.java) | `6f7ddbb955b6` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/controller/MemoryController.java](../../backend/src/main/java/com/personalos/memory/controller/MemoryController.java) | `8a57a2980ef1` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/dto/CreateMemoryRequest.java](../../backend/src/main/java/com/personalos/memory/dto/CreateMemoryRequest.java) | `15f712ccbf81` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/dto/MemoryDto.java](../../backend/src/main/java/com/personalos/memory/dto/MemoryDto.java) | `4fe52c721e82` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/dto/PinMemoryRequest.java](../../backend/src/main/java/com/personalos/memory/dto/PinMemoryRequest.java) | `2a3161243fad` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/dto/UpdateMemoryRequest.java](../../backend/src/main/java/com/personalos/memory/dto/UpdateMemoryRequest.java) | `c85d3e7c5eca` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/entity/MemoryEntity.java](../../backend/src/main/java/com/personalos/memory/entity/MemoryEntity.java) | `c88eeb9d7403` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/entity/MemorySource.java](../../backend/src/main/java/com/personalos/memory/entity/MemorySource.java) | `9a9c7b207e87` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/package-info.java](../../backend/src/main/java/com/personalos/memory/package-info.java) | `6530802eeec1` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/repository/MemoryRepository.java](../../backend/src/main/java/com/personalos/memory/repository/MemoryRepository.java) | `27b6a218c4de` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/service/AutoMemoryService.java](../../backend/src/main/java/com/personalos/memory/service/AutoMemoryService.java) | `057fe500d228` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/service/MemoryEmbeddingService.java](../../backend/src/main/java/com/personalos/memory/service/MemoryEmbeddingService.java) | `45c0027b2f23` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/service/MemoryEmbeddingStore.java](../../backend/src/main/java/com/personalos/memory/service/MemoryEmbeddingStore.java) | `f729707fde39` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/service/MemoryFactParser.java](../../backend/src/main/java/com/personalos/memory/service/MemoryFactParser.java) | `5f461a5df984` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/service/MemoryRelevance.java](../../backend/src/main/java/com/personalos/memory/service/MemoryRelevance.java) | `f2f2872870dc` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/service/MemoryService.java](../../backend/src/main/java/com/personalos/memory/service/MemoryService.java) | `74856eedddb7` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/tool/MemoryToolContributor.java](../../backend/src/main/java/com/personalos/memory/tool/MemoryToolContributor.java) | `1d32db51e12e` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/memory/tool/MemoryTools.java](../../backend/src/main/java/com/personalos/memory/tool/MemoryTools.java) | `e6295dbf7234` | P5 | backend-go/internal/memory/
[backend/src/main/java/com/personalos/task/TaskExportContributor.java](../../backend/src/main/java/com/personalos/task/TaskExportContributor.java) | `6b201b9b1313` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/TaskSearchContributor.java](../../backend/src/main/java/com/personalos/task/TaskSearchContributor.java) | `d8710f0047dd` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/TaskTags.java](../../backend/src/main/java/com/personalos/task/TaskTags.java) | `c728265a969c` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/confirmation/TaskConfirmationHandler.java](../../backend/src/main/java/com/personalos/task/confirmation/TaskConfirmationHandler.java) | `428e4d0765e2` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/confirmation/TaskConfirmationPayloads.java](../../backend/src/main/java/com/personalos/task/confirmation/TaskConfirmationPayloads.java) | `017f24b8e690` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/confirmation/TaskConfirmationService.java](../../backend/src/main/java/com/personalos/task/confirmation/TaskConfirmationService.java) | `6ae91a98f624` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/controller/TaskController.java](../../backend/src/main/java/com/personalos/task/controller/TaskController.java) | `ab00ba3996f4` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/dto/CreateTaskRequest.java](../../backend/src/main/java/com/personalos/task/dto/CreateTaskRequest.java) | `809bba510613` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/dto/DeletionAck.java](../../backend/src/main/java/com/personalos/task/dto/DeletionAck.java) | `c86ce4077147` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/dto/TaskDto.java](../../backend/src/main/java/com/personalos/task/dto/TaskDto.java) | `74e38b285efd` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/dto/TaskQuery.java](../../backend/src/main/java/com/personalos/task/dto/TaskQuery.java) | `6239c408d116` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/dto/TaskStatsDto.java](../../backend/src/main/java/com/personalos/task/dto/TaskStatsDto.java) | `c18f2f189769` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/dto/UpdateTaskRequest.java](../../backend/src/main/java/com/personalos/task/dto/UpdateTaskRequest.java) | `7c3d27501bab` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/entity/TaskEntity.java](../../backend/src/main/java/com/personalos/task/entity/TaskEntity.java) | `1117a495a05c` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/entity/TaskPriority.java](../../backend/src/main/java/com/personalos/task/entity/TaskPriority.java) | `b286d2ebed2c` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/entity/TaskStatus.java](../../backend/src/main/java/com/personalos/task/entity/TaskStatus.java) | `e43a759f584e` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/package-info.java](../../backend/src/main/java/com/personalos/task/package-info.java) | `65c05f51dee0` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/repository/TaskRepository.java](../../backend/src/main/java/com/personalos/task/repository/TaskRepository.java) | `8a7c6c950b09` | P2 | backend-go/internal/task/
[backend/src/main/java/com/personalos/task/service/TaskService.java](../../backend/src/main/java/com/personalos/task/service/TaskService.java) | `e939d6ccf5b0` | P2 | backend-go/internal/task/

## java_test

基线文件 | SHA-256（前 12 位） | 阶段 | Go 去向 / 复用要求
--- | --- | --- | ---
[backend/src/test/java/com/personalos/PersonalOsApplicationTests.java](../../backend/src/test/java/com/personalos/PersonalOsApplicationTests.java) | `70d1cd3ece41` | P1 | backend-go/internal/app/
[backend/src/test/java/com/personalos/agent/AgentToolCallingIntegrationTest.java](../../backend/src/test/java/com/personalos/agent/AgentToolCallingIntegrationTest.java) | `2d2390f454f8` | P4 | backend-go/internal/agent/
[backend/src/test/java/com/personalos/agent/audit/ToolInvocationAuditIntegrationTest.java](../../backend/src/test/java/com/personalos/agent/audit/ToolInvocationAuditIntegrationTest.java) | `533051a80ee6` | P4 | backend-go/internal/agent/
[backend/src/test/java/com/personalos/agent/plan/AgentPlanServiceTest.java](../../backend/src/test/java/com/personalos/agent/plan/AgentPlanServiceTest.java) | `5cf7f361254f` | P4 | backend-go/internal/agent/
[backend/src/test/java/com/personalos/agent/plan/AgentPlanToolsTest.java](../../backend/src/test/java/com/personalos/agent/plan/AgentPlanToolsTest.java) | `03b99d6f9059` | P4 | backend-go/internal/agent/
[backend/src/test/java/com/personalos/agent/plan/PlanPromptContributorTest.java](../../backend/src/test/java/com/personalos/agent/plan/PlanPromptContributorTest.java) | `3511b5fcf313` | P4 | backend-go/internal/agent/
[backend/src/test/java/com/personalos/agent/plan/PlanWiringTest.java](../../backend/src/test/java/com/personalos/agent/plan/PlanWiringTest.java) | `920b953d4af2` | P4 | backend-go/internal/agent/
[backend/src/test/java/com/personalos/calendar/CalendarReminderIntegrationTest.java](../../backend/src/test/java/com/personalos/calendar/CalendarReminderIntegrationTest.java) | `e87d5f6270a5` | P5 | backend-go/internal/calendar/
[backend/src/test/java/com/personalos/calendar/push/WebPushRepositoryIntegrationTest.java](../../backend/src/test/java/com/personalos/calendar/push/WebPushRepositoryIntegrationTest.java) | `490abf804a78` | P5 | backend-go/internal/calendar/
[backend/src/test/java/com/personalos/calendar/push/WebPushServiceTest.java](../../backend/src/test/java/com/personalos/calendar/push/WebPushServiceTest.java) | `2fb09b5398bd` | P5 | backend-go/internal/calendar/
[backend/src/test/java/com/personalos/calendar/push/WebPushTransportTest.java](../../backend/src/test/java/com/personalos/calendar/push/WebPushTransportTest.java) | `101c15673641` | P5 | backend-go/internal/calendar/
[backend/src/test/java/com/personalos/calendar/recurrence/RecurrenceCalculatorTest.java](../../backend/src/test/java/com/personalos/calendar/recurrence/RecurrenceCalculatorTest.java) | `67a652ac8bdb` | P5 | backend-go/internal/calendar/
[backend/src/test/java/com/personalos/calendar/service/ReminderStreamDeliveryTest.java](../../backend/src/test/java/com/personalos/calendar/service/ReminderStreamDeliveryTest.java) | `5a1c2ad99a69` | P5 | backend-go/internal/calendar/
[backend/src/test/java/com/personalos/chat/AgentInteractionIntegrationTest.java](../../backend/src/test/java/com/personalos/chat/AgentInteractionIntegrationTest.java) | `b655fcccf374` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/ChatApiIntegrationTest.java](../../backend/src/test/java/com/personalos/chat/ChatApiIntegrationTest.java) | `e1cafd4b0676` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/ChatKnowledgeIntegrationTest.java](../../backend/src/test/java/com/personalos/chat/ChatKnowledgeIntegrationTest.java) | `ea576133d6dc` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/ChatMessagePaginationIntegrationTest.java](../../backend/src/test/java/com/personalos/chat/ChatMessagePaginationIntegrationTest.java) | `7b112efdd86c` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/ChatRunIntegrationTest.java](../../backend/src/test/java/com/personalos/chat/ChatRunIntegrationTest.java) | `80e97e3664e7` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/ChatStreamTerminationIntegrationTest.java](../../backend/src/test/java/com/personalos/chat/ChatStreamTerminationIntegrationTest.java) | `6c21913a0ec4` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/RunEventBufferTest.java](../../backend/src/test/java/com/personalos/chat/RunEventBufferTest.java) | `a0723b606c95` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/TokenUsageIntegrationTest.java](../../backend/src/test/java/com/personalos/chat/TokenUsageIntegrationTest.java) | `c55da4eb754c` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/controller/ChatContextControllerTest.java](../../backend/src/test/java/com/personalos/chat/controller/ChatContextControllerTest.java) | `37dc030b6445` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/dto/ThinkingLevelTest.java](../../backend/src/test/java/com/personalos/chat/dto/ThinkingLevelTest.java) | `82c276c6a05f` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/memory/JpaChatMemoryTest.java](../../backend/src/test/java/com/personalos/chat/memory/JpaChatMemoryTest.java) | `4772bae7c074` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/metrics/ChatStreamMetricsTest.java](../../backend/src/test/java/com/personalos/chat/metrics/ChatStreamMetricsTest.java) | `c19838b8312d` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/service/ChatAttachmentContextBuilderTest.java](../../backend/src/test/java/com/personalos/chat/service/ChatAttachmentContextBuilderTest.java) | `753dc5edadbd` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/service/ChatAttachmentServiceTest.java](../../backend/src/test/java/com/personalos/chat/service/ChatAttachmentServiceTest.java) | `ce737d396447` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/service/ChatMarkdownExporterTest.java](../../backend/src/test/java/com/personalos/chat/service/ChatMarkdownExporterTest.java) | `78dbc6eef39c` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/service/ChatMessageServiceRewindTest.java](../../backend/src/test/java/com/personalos/chat/service/ChatMessageServiceRewindTest.java) | `41280d3640a1` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/service/ChatPromptAssemblerTest.java](../../backend/src/test/java/com/personalos/chat/service/ChatPromptAssemblerTest.java) | `166936cfb847` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/service/ChatSessionServiceTest.java](../../backend/src/test/java/com/personalos/chat/service/ChatSessionServiceTest.java) | `74be0c8e4dc6` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/service/ChatSummaryServiceTest.java](../../backend/src/test/java/com/personalos/chat/service/ChatSummaryServiceTest.java) | `3470b2c86bd8` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/service/ChatTurnFinisherTest.java](../../backend/src/test/java/com/personalos/chat/service/ChatTurnFinisherTest.java) | `499960fe64be` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/service/UsageStatsServiceTest.java](../../backend/src/test/java/com/personalos/chat/service/UsageStatsServiceTest.java) | `a46ad541145f` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/storage/S3AttachmentStoreIntegrationTest.java](../../backend/src/test/java/com/personalos/chat/storage/S3AttachmentStoreIntegrationTest.java) | `ba0d6c93ccb4` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/support/ChatResponseInspectionTest.java](../../backend/src/test/java/com/personalos/chat/support/ChatResponseInspectionTest.java) | `23e231795f0b` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/chat/support/SseHeartbeatSchedulerTest.java](../../backend/src/test/java/com/personalos/chat/support/SseHeartbeatSchedulerTest.java) | `a94839f1d752` | P4 | backend-go/internal/chat/
[backend/src/test/java/com/personalos/common/ai/AgentToolCallingManagerTest.java](../../backend/src/test/java/com/personalos/common/ai/AgentToolCallingManagerTest.java) | `41853149108e` | P4 | backend-go/internal/agent/
[backend/src/test/java/com/personalos/common/ai/AgentTurnBudgetTest.java](../../backend/src/test/java/com/personalos/common/ai/AgentTurnBudgetTest.java) | `064bb870e93d` | P4 | backend-go/internal/agent/
[backend/src/test/java/com/personalos/common/ai/AiChatClientProviderModelSelectionTest.java](../../backend/src/test/java/com/personalos/common/ai/AiChatClientProviderModelSelectionTest.java) | `4fbae7415a85` | P4 | backend-go/internal/agent/
[backend/src/test/java/com/personalos/common/ai/CalendarWriteGuardChatModelTest.java](../../backend/src/test/java/com/personalos/common/ai/CalendarWriteGuardChatModelTest.java) | `77381f1460ce` | P4 | backend-go/internal/agent/
[backend/src/test/java/com/personalos/common/ai/ContextBudgetPersistenceIntegrationTest.java](../../backend/src/test/java/com/personalos/common/ai/ContextBudgetPersistenceIntegrationTest.java) | `9777244173b9` | P4 | backend-go/internal/context/
[backend/src/test/java/com/personalos/common/ai/ContextBudgetServiceTest.java](../../backend/src/test/java/com/personalos/common/ai/ContextBudgetServiceTest.java) | `076ae1d1fde4` | P4 | backend-go/internal/context/
[backend/src/test/java/com/personalos/common/ai/ContextGuardChatModelTest.java](../../backend/src/test/java/com/personalos/common/ai/ContextGuardChatModelTest.java) | `6850f822779f` | P4 | backend-go/internal/context/
[backend/src/test/java/com/personalos/common/ai/ContextWindowEvidenceTest.java](../../backend/src/test/java/com/personalos/common/ai/ContextWindowEvidenceTest.java) | `63c6240b1f30` | P4 | backend-go/internal/context/
[backend/src/test/java/com/personalos/common/ai/CurrentTimePromptContributorTest.java](../../backend/src/test/java/com/personalos/common/ai/CurrentTimePromptContributorTest.java) | `0e506a7736f3` | P4 | backend-go/internal/context/
[backend/src/test/java/com/personalos/common/ai/ModelContextWindowsTest.java](../../backend/src/test/java/com/personalos/common/ai/ModelContextWindowsTest.java) | `67dd193d49a6` | P4 | backend-go/internal/context/
[backend/src/test/java/com/personalos/common/ai/ModelWindowReferencesTest.java](../../backend/src/test/java/com/personalos/common/ai/ModelWindowReferencesTest.java) | `ecf084cd6e03` | P4 | backend-go/internal/context/
[backend/src/test/java/com/personalos/common/ai/TokenEstimationTest.java](../../backend/src/test/java/com/personalos/common/ai/TokenEstimationTest.java) | `1371fedf3a1a` | P4 | backend-go/internal/context/
[backend/src/test/java/com/personalos/common/ai/ToolSchemaOverheadTest.java](../../backend/src/test/java/com/personalos/common/ai/ToolSchemaOverheadTest.java) | `f56bb551cc03` | P4 | backend-go/internal/agent/
[backend/src/test/java/com/personalos/common/ai/cache/CachedEmbeddingModelTest.java](../../backend/src/test/java/com/personalos/common/ai/cache/CachedEmbeddingModelTest.java) | `9b7ba752f991` | P3 | backend-go/internal/platform/cache/
[backend/src/test/java/com/personalos/common/ai/cache/RedisEmbeddingCacheIntegrationTest.java](../../backend/src/test/java/com/personalos/common/ai/cache/RedisEmbeddingCacheIntegrationTest.java) | `02c07497ad13` | P3 | backend-go/internal/platform/cache/
[backend/src/test/java/com/personalos/common/ai/config/AiModelConfigEntityTest.java](../../backend/src/test/java/com/personalos/common/ai/config/AiModelConfigEntityTest.java) | `78ae6db033d7` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/config/AiModelProfileEntityTest.java](../../backend/src/test/java/com/personalos/common/ai/config/AiModelProfileEntityTest.java) | `2ac6893ac21c` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/config/AiModelProfileModelEntityTest.java](../../backend/src/test/java/com/personalos/common/ai/config/AiModelProfileModelEntityTest.java) | `2a16e7b031bc` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/config/AudioTranscriptionServiceHttpTest.java](../../backend/src/test/java/com/personalos/common/ai/config/AudioTranscriptionServiceHttpTest.java) | `cdc01f2a15dc` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/config/AudioTranscriptionServiceTest.java](../../backend/src/test/java/com/personalos/common/ai/config/AudioTranscriptionServiceTest.java) | `55e79b098e79` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/config/ContextWindowReconcileTest.java](../../backend/src/test/java/com/personalos/common/ai/config/ContextWindowReconcileTest.java) | `e8da6afec284` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/config/ModelConfigServiceTest.java](../../backend/src/test/java/com/personalos/common/ai/config/ModelConfigServiceTest.java) | `5187bfeb73b2` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/config/ModelKeyConverterTest.java](../../backend/src/test/java/com/personalos/common/ai/config/ModelKeyConverterTest.java) | `0897a6aad1c0` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/config/ModelListProbeServiceHttpTest.java](../../backend/src/test/java/com/personalos/common/ai/config/ModelListProbeServiceHttpTest.java) | `04d402247fc0` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/config/ModelListProbeServiceTest.java](../../backend/src/test/java/com/personalos/common/ai/config/ModelListProbeServiceTest.java) | `d30edda8eb27` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/config/ModelListProbeServiceWindowTest.java](../../backend/src/test/java/com/personalos/common/ai/config/ModelListProbeServiceWindowTest.java) | `215db89ed50d` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/config/ModelProfileControllerTest.java](../../backend/src/test/java/com/personalos/common/ai/config/ModelProfileControllerTest.java) | `148ef8777a55` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/config/ModelProfileServiceTest.java](../../backend/src/test/java/com/personalos/common/ai/config/ModelProfileServiceTest.java) | `7b86e33afc8c` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/config/ModelWindowProbeHttpTest.java](../../backend/src/test/java/com/personalos/common/ai/config/ModelWindowProbeHttpTest.java) | `639f2d17c862` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/config/OpenAiChatModelFactoryTest.java](../../backend/src/test/java/com/personalos/common/ai/config/OpenAiChatModelFactoryTest.java) | `69f69c31038f` | P3 | backend-go/internal/model/
[backend/src/test/java/com/personalos/common/ai/tool/ToolArgumentsTest.java](../../backend/src/test/java/com/personalos/common/ai/tool/ToolArgumentsTest.java) | `cae4e779f4f2` | P4 | backend-go/internal/agent/
[backend/src/test/java/com/personalos/common/ai/tool/ToolExecutorTest.java](../../backend/src/test/java/com/personalos/common/ai/tool/ToolExecutorTest.java) | `59f69259a6da` | P4 | backend-go/internal/agent/
[backend/src/test/java/com/personalos/common/api/ApiResponseTest.java](../../backend/src/test/java/com/personalos/common/api/ApiResponseTest.java) | `a574c892c784` | P1 | backend-go/internal/platform/api/
[backend/src/test/java/com/personalos/common/api/PageDtoTest.java](../../backend/src/test/java/com/personalos/common/api/PageDtoTest.java) | `4ee6adef8958` | P1 | backend-go/internal/platform/api/
[backend/src/test/java/com/personalos/common/auth/AuthDisabledIntegrationTest.java](../../backend/src/test/java/com/personalos/common/auth/AuthDisabledIntegrationTest.java) | `ce79a0d9a267` | P1 | backend-go/internal/platform/auth/
[backend/src/test/java/com/personalos/common/auth/AuthIntegrationTest.java](../../backend/src/test/java/com/personalos/common/auth/AuthIntegrationTest.java) | `fa551fb37c15` | P1 | backend-go/internal/platform/auth/
[backend/src/test/java/com/personalos/common/auth/AuthLockoutIntegrationTest.java](../../backend/src/test/java/com/personalos/common/auth/AuthLockoutIntegrationTest.java) | `0e5899b97a70` | P1 | backend-go/internal/platform/auth/
[backend/src/test/java/com/personalos/common/auth/AuthServiceTest.java](../../backend/src/test/java/com/personalos/common/auth/AuthServiceTest.java) | `db9086489224` | P1 | backend-go/internal/platform/auth/
[backend/src/test/java/com/personalos/common/auth/SharedSessionIntegrationTest.java](../../backend/src/test/java/com/personalos/common/auth/SharedSessionIntegrationTest.java) | `09857ecb7f45` | P1 | backend-go/internal/platform/auth/
[backend/src/test/java/com/personalos/common/config/DotEnvParserTest.java](../../backend/src/test/java/com/personalos/common/config/DotEnvParserTest.java) | `44bf7811192a` | P1 | backend-go/internal/platform/config/
[backend/src/test/java/com/personalos/common/confirmation/ConfirmationPromptContributorTest.java](../../backend/src/test/java/com/personalos/common/confirmation/ConfirmationPromptContributorTest.java) | `e23f4642ae07` | P2 | backend-go/internal/platform/confirmation/
[backend/src/test/java/com/personalos/common/confirmation/ConfirmationTransactionTest.java](../../backend/src/test/java/com/personalos/common/confirmation/ConfirmationTransactionTest.java) | `e9716a36bde6` | P2 | backend-go/internal/platform/confirmation/
[backend/src/test/java/com/personalos/common/document/PlainTextExtractorTest.java](../../backend/src/test/java/com/personalos/common/document/PlainTextExtractorTest.java) | `5aeb58742693` | P1 | backend-go/internal/platform/document/
[backend/src/test/java/com/personalos/common/exception/ErrorEnvelopeIntegrationTest.java](../../backend/src/test/java/com/personalos/common/exception/ErrorEnvelopeIntegrationTest.java) | `4b41f336b07b` | P1 | backend-go/internal/platform/exception/
[backend/src/test/java/com/personalos/common/exception/GlobalExceptionHandlerLogTest.java](../../backend/src/test/java/com/personalos/common/exception/GlobalExceptionHandlerLogTest.java) | `ae597ee9c509` | P1 | backend-go/internal/platform/exception/
[backend/src/test/java/com/personalos/common/health/HealthControllerTest.java](../../backend/src/test/java/com/personalos/common/health/HealthControllerTest.java) | `ddd1c36b4fb2` | P1 | backend-go/internal/platform/health/
[backend/src/test/java/com/personalos/common/health/HealthServiceTest.java](../../backend/src/test/java/com/personalos/common/health/HealthServiceTest.java) | `f2ed2ffa5faf` | P1 | backend-go/internal/platform/health/
[backend/src/test/java/com/personalos/common/maintenance/DataRetentionServiceTest.java](../../backend/src/test/java/com/personalos/common/maintenance/DataRetentionServiceTest.java) | `6c2a4be006f1` | P8 | backend-go/internal/platform/maintenance/
[backend/src/test/java/com/personalos/common/monitoring/MonitoringIntegrationTest.java](../../backend/src/test/java/com/personalos/common/monitoring/MonitoringIntegrationTest.java) | `1a9cee602a8f` | P1 | backend-go/internal/platform/monitoring/
[backend/src/test/java/com/personalos/common/search/GlobalSearchIntegrationTest.java](../../backend/src/test/java/com/personalos/common/search/GlobalSearchIntegrationTest.java) | `bf621760e571` | P7 | backend-go/internal/search/
[backend/src/test/java/com/personalos/common/search/SearchPatternsTest.java](../../backend/src/test/java/com/personalos/common/search/SearchPatternsTest.java) | `1d9e098ada2b` | P7 | backend-go/internal/search/
[backend/src/test/java/com/personalos/common/text/TextsTest.java](../../backend/src/test/java/com/personalos/common/text/TextsTest.java) | `6d0731dc3a31` | P1 | backend-go/internal/platform/text/
[backend/src/test/java/com/personalos/common/web/DatabaseCoordinationIntegrationTest.java](../../backend/src/test/java/com/personalos/common/web/DatabaseCoordinationIntegrationTest.java) | `b607af4cf4a0` | P1 | backend-go/internal/platform/web/
[backend/src/test/java/com/personalos/common/web/MdcPropagatingExecutorTest.java](../../backend/src/test/java/com/personalos/common/web/MdcPropagatingExecutorTest.java) | `d7b378c1948b` | P1 | backend-go/internal/platform/web/
[backend/src/test/java/com/personalos/common/web/MdcSchedulerHookTest.java](../../backend/src/test/java/com/personalos/common/web/MdcSchedulerHookTest.java) | `c403edb73e4e` | P1 | backend-go/internal/platform/web/
[backend/src/test/java/com/personalos/common/web/ModelConcurrencyFilterTest.java](../../backend/src/test/java/com/personalos/common/web/ModelConcurrencyFilterTest.java) | `d17af350a8b2` | P1 | backend-go/internal/platform/web/
[backend/src/test/java/com/personalos/common/web/RateLimitFilterTest.java](../../backend/src/test/java/com/personalos/common/web/RateLimitFilterTest.java) | `7ad4d4d62df7` | P1 | backend-go/internal/platform/web/
[backend/src/test/java/com/personalos/common/web/RedisRateBucketIntegrationTest.java](../../backend/src/test/java/com/personalos/common/web/RedisRateBucketIntegrationTest.java) | `be1d45e3ce5d` | P1 | backend-go/internal/platform/web/
[backend/src/test/java/com/personalos/common/web/RequestIdFilterTest.java](../../backend/src/test/java/com/personalos/common/web/RequestIdFilterTest.java) | `f946f3d3b5e2` | P1 | backend-go/internal/platform/web/
[backend/src/test/java/com/personalos/common/web/TokenBucketRateLimiterTest.java](../../backend/src/test/java/com/personalos/common/web/TokenBucketRateLimiterTest.java) | `c4b8165d034f` | P1 | backend-go/internal/platform/web/
[backend/src/test/java/com/personalos/common/web/WorkerIsolationFilterTest.java](../../backend/src/test/java/com/personalos/common/web/WorkerIsolationFilterTest.java) | `a5b02caaf898` | P1 | backend-go/internal/platform/web/
[backend/src/test/java/com/personalos/export/DataExportIntegrationTest.java](../../backend/src/test/java/com/personalos/export/DataExportIntegrationTest.java) | `e39e2e65ace1` | P7 | backend-go/internal/transfer/
[backend/src/test/java/com/personalos/export/DataImportIntegrationTest.java](../../backend/src/test/java/com/personalos/export/DataImportIntegrationTest.java) | `193f63728896` | P7 | backend-go/internal/transfer/
[backend/src/test/java/com/personalos/knowledge/EmbeddingDimensionMismatchIntegrationTest.java](../../backend/src/test/java/com/personalos/knowledge/EmbeddingDimensionMismatchIntegrationTest.java) | `277b5add71f9` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/HybridSearchIntegrationTest.java](../../backend/src/test/java/com/personalos/knowledge/HybridSearchIntegrationTest.java) | `7605a3069724` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/KnowledgeDocumentPageOverflowIntegrationTest.java](../../backend/src/test/java/com/personalos/knowledge/KnowledgeDocumentPageOverflowIntegrationTest.java) | `3da68ec3e0d6` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/KnowledgeDocumentUpdateIntegrationTest.java](../../backend/src/test/java/com/personalos/knowledge/KnowledgeDocumentUpdateIntegrationTest.java) | `cb91d44a7043` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/KnowledgeIndexJobsIntegrationTest.java](../../backend/src/test/java/com/personalos/knowledge/KnowledgeIndexJobsIntegrationTest.java) | `dffbb97aec61` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/KnowledgeMinLengthIntegrationTest.java](../../backend/src/test/java/com/personalos/knowledge/KnowledgeMinLengthIntegrationTest.java) | `80f2ab6e62a7` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/KnowledgeRagIntegrationTest.java](../../backend/src/test/java/com/personalos/knowledge/KnowledgeRagIntegrationTest.java) | `96c901f62312` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/RabbitJobNotifierIntegrationTest.java](../../backend/src/test/java/com/personalos/knowledge/RabbitJobNotifierIntegrationTest.java) | `967a3fb22104` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/RichDocumentIngestIntegrationTest.java](../../backend/src/test/java/com/personalos/knowledge/RichDocumentIngestIntegrationTest.java) | `657b27e6c04b` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/VectorStoreIndexMigrationTest.java](../../backend/src/test/java/com/personalos/knowledge/VectorStoreIndexMigrationTest.java) | `3767fe6aa0e4` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/governance/KnowledgeGovernanceIntegrationTest.java](../../backend/src/test/java/com/personalos/knowledge/governance/KnowledgeGovernanceIntegrationTest.java) | `6b0635cb13ea` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/rag/AdvancedRagIntegrationTest.java](../../backend/src/test/java/com/personalos/knowledge/rag/AdvancedRagIntegrationTest.java) | `a90a491b3cdb` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/rag/CrossEncoderRerankerTest.java](../../backend/src/test/java/com/personalos/knowledge/rag/CrossEncoderRerankerTest.java) | `2cded049e9c5` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/rag/RagModelGatewayTest.java](../../backend/src/test/java/com/personalos/knowledge/rag/RagModelGatewayTest.java) | `ffda416b847c` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/rag/RagRetrievalPipelineTest.java](../../backend/src/test/java/com/personalos/knowledge/rag/RagRetrievalPipelineTest.java) | `c2d0d6ae9013` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/reader/DocxDocumentReaderTest.java](../../backend/src/test/java/com/personalos/knowledge/reader/DocxDocumentReaderTest.java) | `6cfa139ce350` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/reader/ImageDocumentReaderTest.java](../../backend/src/test/java/com/personalos/knowledge/reader/ImageDocumentReaderTest.java) | `f97f3a80d954` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/reader/PdfDocumentReaderTest.java](../../backend/src/test/java/com/personalos/knowledge/reader/PdfDocumentReaderTest.java) | `eac025201dd7` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/search/OpenSearchGatewayIntegrationTest.java](../../backend/src/test/java/com/personalos/knowledge/search/OpenSearchGatewayIntegrationTest.java) | `129fcd4a2826` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/service/DocumentFilenameSupportTest.java](../../backend/src/test/java/com/personalos/knowledge/service/DocumentFilenameSupportTest.java) | `80cf999c628b` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/service/DocumentWriteLockTest.java](../../backend/src/test/java/com/personalos/knowledge/service/DocumentWriteLockTest.java) | `db069d6e810c` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/service/KeywordExtractorTest.java](../../backend/src/test/java/com/personalos/knowledge/service/KeywordExtractorTest.java) | `0ab03729e2f8` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/service/VectorStoreInspectorPatternTest.java](../../backend/src/test/java/com/personalos/knowledge/service/VectorStoreInspectorPatternTest.java) | `c9e83dbd3ca9` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/knowledge/splitter/MarkdownStructureTextSplitterTest.java](../../backend/src/test/java/com/personalos/knowledge/splitter/MarkdownStructureTextSplitterTest.java) | `447e71daa1b8` | P6 | backend-go/internal/knowledge/
[backend/src/test/java/com/personalos/list/ListSearchIntegrationTest.java](../../backend/src/test/java/com/personalos/list/ListSearchIntegrationTest.java) | `56cdffb95182` | P7 | backend-go/internal/search/
[backend/src/test/java/com/personalos/mcp/McpChatToolCallingIntegrationTest.java](../../backend/src/test/java/com/personalos/mcp/McpChatToolCallingIntegrationTest.java) | `a346d4465a83` | P7 | backend-go/internal/mcp/
[backend/src/test/java/com/personalos/mcp/McpClientRegistryIntegrationTest.java](../../backend/src/test/java/com/personalos/mcp/McpClientRegistryIntegrationTest.java) | `7127be21b4cf` | P7 | backend-go/internal/mcp/
[backend/src/test/java/com/personalos/mcp/McpConfirmationIntegrationTest.java](../../backend/src/test/java/com/personalos/mcp/McpConfirmationIntegrationTest.java) | `cbc2ea8f51f5` | P7 | backend-go/internal/mcp/
[backend/src/test/java/com/personalos/mcp/McpDegradedStartupIntegrationTest.java](../../backend/src/test/java/com/personalos/mcp/McpDegradedStartupIntegrationTest.java) | `1f865a34ae81` | P7 | backend-go/internal/mcp/
[backend/src/test/java/com/personalos/mcp/TestMcpServerMain.java](../../backend/src/test/java/com/personalos/mcp/TestMcpServerMain.java) | `0836c1cdb85c` | P7 | backend-go/internal/mcp/
[backend/src/test/java/com/personalos/mcp/confirmation/McpConfirmationSummaryTest.java](../../backend/src/test/java/com/personalos/mcp/confirmation/McpConfirmationSummaryTest.java) | `5e2a5695ea22` | P7 | backend-go/internal/mcp/
[backend/src/test/java/com/personalos/mcp/server/PersonalMcpExposureTest.java](../../backend/src/test/java/com/personalos/mcp/server/PersonalMcpExposureTest.java) | `ca372b225eea` | P7 | backend-go/internal/mcp/
[backend/src/test/java/com/personalos/mcp/status/McpStatusServiceTest.java](../../backend/src/test/java/com/personalos/mcp/status/McpStatusServiceTest.java) | `07f0412ded2d` | P7 | backend-go/internal/mcp/
[backend/src/test/java/com/personalos/mcp/tool/McpToolCallbackProviderTest.java](../../backend/src/test/java/com/personalos/mcp/tool/McpToolCallbackProviderTest.java) | `5ef8038fc35d` | P7 | backend-go/internal/mcp/
[backend/src/test/java/com/personalos/mcp/tool/McpToolNamingTest.java](../../backend/src/test/java/com/personalos/mcp/tool/McpToolNamingTest.java) | `1379f38d8d69` | P7 | backend-go/internal/mcp/
[backend/src/test/java/com/personalos/mcp/tool/McpToolPolicyTest.java](../../backend/src/test/java/com/personalos/mcp/tool/McpToolPolicyTest.java) | `2041038871ac` | P7 | backend-go/internal/mcp/
[backend/src/test/java/com/personalos/memory/MemoryEmbeddingBackfillTest.java](../../backend/src/test/java/com/personalos/memory/MemoryEmbeddingBackfillTest.java) | `236a451fe911` | P5 | backend-go/internal/memory/
[backend/src/test/java/com/personalos/memory/MemoryIntegrationTest.java](../../backend/src/test/java/com/personalos/memory/MemoryIntegrationTest.java) | `cc79ae2851e6` | P5 | backend-go/internal/memory/
[backend/src/test/java/com/personalos/memory/MemoryPropertiesTest.java](../../backend/src/test/java/com/personalos/memory/MemoryPropertiesTest.java) | `4885f1577f3e` | P5 | backend-go/internal/memory/
[backend/src/test/java/com/personalos/memory/MemoryServiceTest.java](../../backend/src/test/java/com/personalos/memory/MemoryServiceTest.java) | `58077f8063de` | P5 | backend-go/internal/memory/
[backend/src/test/java/com/personalos/memory/service/MemoryEmbeddingServiceTest.java](../../backend/src/test/java/com/personalos/memory/service/MemoryEmbeddingServiceTest.java) | `3887af038135` | P5 | backend-go/internal/memory/
[backend/src/test/java/com/personalos/memory/service/MemoryEmbeddingStoreTest.java](../../backend/src/test/java/com/personalos/memory/service/MemoryEmbeddingStoreTest.java) | `2178c120c2b2` | P5 | backend-go/internal/memory/
[backend/src/test/java/com/personalos/memory/service/MemoryFactParserTest.java](../../backend/src/test/java/com/personalos/memory/service/MemoryFactParserTest.java) | `8e1a48107969` | P5 | backend-go/internal/memory/
[backend/src/test/java/com/personalos/memory/service/MemoryRelevanceTest.java](../../backend/src/test/java/com/personalos/memory/service/MemoryRelevanceTest.java) | `a64240a6ecdb` | P5 | backend-go/internal/memory/
[backend/src/test/java/com/personalos/support/DeterministicEmbeddingModel.java](../../backend/src/test/java/com/personalos/support/DeterministicEmbeddingModel.java) | `dc2c88c45f4e` | P1 | backend-go/internal/testsupport/
[backend/src/test/java/com/personalos/support/HttpTestClient.java](../../backend/src/test/java/com/personalos/support/HttpTestClient.java) | `8164c363eefd` | P1 | backend-go/internal/testsupport/
[backend/src/test/java/com/personalos/support/Instants.java](../../backend/src/test/java/com/personalos/support/Instants.java) | `26bbb98fecc2` | P1 | backend-go/internal/testsupport/
[backend/src/test/java/com/personalos/support/InstantsTest.java](../../backend/src/test/java/com/personalos/support/InstantsTest.java) | `611463972285` | P1 | backend-go/internal/testsupport/
[backend/src/test/java/com/personalos/support/ScriptedToolCallingChatModel.java](../../backend/src/test/java/com/personalos/support/ScriptedToolCallingChatModel.java) | `74ae8a88ccb4` | P1 | backend-go/internal/testsupport/
[backend/src/test/java/com/personalos/support/StaticObjectProvider.java](../../backend/src/test/java/com/personalos/support/StaticObjectProvider.java) | `65bb51f64f16` | P1 | backend-go/internal/testsupport/
[backend/src/test/java/com/personalos/task/TaskApiIntegrationTest.java](../../backend/src/test/java/com/personalos/task/TaskApiIntegrationTest.java) | `0e81da77b6fd` | P2 | backend-go/internal/task/
[backend/src/test/java/com/personalos/task/TaskTagsIntegrationTest.java](../../backend/src/test/java/com/personalos/task/TaskTagsIntegrationTest.java) | `569d29c94987` | P2 | backend-go/internal/task/
[backend/src/test/java/com/personalos/task/TaskTagsTest.java](../../backend/src/test/java/com/personalos/task/TaskTagsTest.java) | `29fb6707fd55` | P2 | backend-go/internal/task/
[backend/src/test/java/com/personalos/task/confirmation/ConfirmationIntegrationTest.java](../../backend/src/test/java/com/personalos/task/confirmation/ConfirmationIntegrationTest.java) | `139c6d50fa8c` | P2 | backend-go/internal/task/

## java_test_resource

基线文件 | SHA-256（前 12 位） | 阶段 | Go 去向 / 复用要求
--- | --- | --- | ---
[backend/src/test/resources/application.properties](../../backend/src/test/resources/application.properties) | `a94cf5b17b80` | P1 | Go 配置/构建/测试等效实现；保留 Java 对照

## operations_and_repository

基线文件 | SHA-256（前 12 位） | 阶段 | Go 去向 / 复用要求
--- | --- | --- | ---
[.editorconfig](../../.editorconfig) | `fa0ad32e713a` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[.env.example](../../.env.example) | `d611717a5658` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[.github/workflows/ci.yml](../../.github/workflows/ci.yml) | `f044786d5e5e` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[.gitignore](../../.gitignore) | `b56128b9bf8d` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[.specify/.gitignore](../../.specify/.gitignore) | `8c908410d177` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/init-options.json](../../.specify/init-options.json) | `4b28429f836d` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/integration.json](../../.specify/integration.json) | `b3a0206f6e72` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/integrations/claude.manifest.json](../../.specify/integrations/claude.manifest.json) | `d2022d2a2148` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/integrations/speckit.manifest.json](../../.specify/integrations/speckit.manifest.json) | `ba579a78a940` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/memory/.constitution-template.json](../../.specify/memory/.constitution-template.json) | `8fc4b39294ba` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/scripts/bash/check-prerequisites.sh](../../.specify/scripts/bash/check-prerequisites.sh) | `a7020699e6b6` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/scripts/bash/common.sh](../../.specify/scripts/bash/common.sh) | `de9a49210b1a` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/scripts/bash/create-new-feature.sh](../../.specify/scripts/bash/create-new-feature.sh) | `fe99ea8da184` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/scripts/bash/resolve-template.sh](../../.specify/scripts/bash/resolve-template.sh) | `829e227096ab` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/scripts/bash/setup-plan.sh](../../.specify/scripts/bash/setup-plan.sh) | `061cec0c6d71` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/scripts/bash/setup-tasks.sh](../../.specify/scripts/bash/setup-tasks.sh) | `4a33dd1e6c32` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/workflows/speckit/workflow.yml](../../.specify/workflows/speckit/workflow.yml) | `b9a2a3c3d1e5` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[.specify/workflows/workflow-registry.json](../../.specify/workflows/workflow-registry.json) | `cca3266e592a` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[LICENSE](../../LICENSE) | `c71d239df917` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[compose.integrations.yml](../../compose.integrations.yml) | `c162a8996e08` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[compose.local-web.yml](../../compose.local-web.yml) | `128eb2c255bc` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[compose.local.yml](../../compose.local.yml) | `0e39deedf5de` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[compose.monitoring.local.yml](../../compose.monitoring.local.yml) | `af5cfe30a8a9` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[compose.monitoring.yml](../../compose.monitoring.yml) | `3afbf215f1b8` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[compose.production.yml](../../compose.production.yml) | `b9357cb66623` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[compose.yml](../../compose.yml) | `bfab2deb6c3e` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[deploy/Caddyfile](../../deploy/Caddyfile) | `13e36ddf205c` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[deploy/Caddyfile.local](../../deploy/Caddyfile.local) | `76d1227ca527` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[deploy/monitoring/grafana/dashboards/runtime.json](../../deploy/monitoring/grafana/dashboards/runtime.json) | `c6b0da037cfb` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[deploy/monitoring/grafana/provisioning/dashboards/default.yml](../../deploy/monitoring/grafana/provisioning/dashboards/default.yml) | `7966a218d8f3` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[deploy/monitoring/grafana/provisioning/datasources/prometheus.yml](../../deploy/monitoring/grafana/provisioning/datasources/prometheus.yml) | `1a130ad1467d` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[deploy/monitoring/prometheus.local.yml](../../deploy/monitoring/prometheus.local.yml) | `5565edb53f6f` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[deploy/monitoring/prometheus.yml](../../deploy/monitoring/prometheus.yml) | `90c303e3f01a` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[deploy/monitoring/worker-targets/.gitkeep](../../deploy/monitoring/worker-targets/.gitkeep) | `e3b0c44298fc` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/dev/backup-db.sh](../../scripts/dev/backup-db.sh) | `e3167b5dd122` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/dev/backup.py](../../scripts/dev/backup.py) | `3664762c9ec9` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/dev/generate-push-keys.py](../../scripts/dev/generate-push-keys.py) | `22b270cc0504` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/dev/local-runtime.py](../../scripts/dev/local-runtime.py) | `da0e477fa556` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/dev/local-stack.sh](../../scripts/dev/local-stack.sh) | `2d94188fbcf3` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/dev/local-workers.py](../../scripts/dev/local-workers.py) | `6435f268b605` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/dev/mock-openai-server.py](../../scripts/dev/mock-openai-server.py) | `dbfc1c8bb4f3` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/dev/seed-demo-data.py](../../scripts/dev/seed-demo-data.py) | `d3e69ac22418` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/dev/test_backup.py](../../scripts/dev/test_backup.py) | `11fcd6b5edd7` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/dev/ui-audit.mjs](../../scripts/dev/ui-audit.mjs) | `cc3c799d956b` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/dev/verify-frontend.js](../../scripts/dev/verify-frontend.js) | `c771fd6f8cc1` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/e2e_suite.py](../../scripts/e2e/e2e_suite.py) | `de8391168448` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-013844.json](../../scripts/e2e/reports/e2e-20261029-013844.json) | `93e7ab3da745` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-013930.json](../../scripts/e2e/reports/e2e-20261029-013930.json) | `cac25d737634` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-014133.json](../../scripts/e2e/reports/e2e-20261029-014133.json) | `a9a5ec83d039` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-014805.json](../../scripts/e2e/reports/e2e-20261029-014805.json) | `2641b3d3d956` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-015051.json](../../scripts/e2e/reports/e2e-20261029-015051.json) | `2ae5e77e9d1e` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-015102.json](../../scripts/e2e/reports/e2e-20261029-015102.json) | `66927c3304d9` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-015110.json](../../scripts/e2e/reports/e2e-20261029-015110.json) | `10f4260feda9` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-015256.json](../../scripts/e2e/reports/e2e-20261029-015256.json) | `a664e7bf4d32` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-104637.json](../../scripts/e2e/reports/e2e-20261029-104637.json) | `71776f7d9664` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/e2e/reports/e2e-20261029-105644.json](../../scripts/e2e/reports/e2e-20261029-105644.json) | `fc3aed2494ac` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/agent-eval.py](../../scripts/eval/agent-eval.py) | `23f612801558` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/fetch-corpus-batch3.py](../../scripts/eval/fetch-corpus-batch3.py) | `13945bb73ddd` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/fetch-corpus-browser.js](../../scripts/eval/fetch-corpus-browser.js) | `f881b0aa515b` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/fetch-corpus-juejin.py](../../scripts/eval/fetch-corpus-juejin.py) | `6d75323dae96` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/noise-floor.py](../../scripts/eval/noise-floor.py) | `9d25cc07c58a` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/queries.py](../../scripts/eval/queries.py) | `b5aa3821c2ff` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/rag-live-cases.json](../../scripts/eval/rag-live-cases.json) | `87bb73e1f94f` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/rag-live-conflicts.py](../../scripts/eval/rag-live-conflicts.py) | `d42fdb10c2a5` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/rag-live-eval.py](../../scripts/eval/rag-live-eval.py) | `b6a0e638ba9c` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/rag-live-ingestion.py](../../scripts/eval/rag-live-ingestion.py) | `305d7afb606a` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/rebuild-corpus.py](../../scripts/eval/rebuild-corpus.py) | `4d28005af243` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/retrieval-eval.py](../../scripts/eval/retrieval-eval.py) | `ed7cbc6a7736` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/test_agent_eval.py](../../scripts/eval/test_agent_eval.py) | `8ed9f41c8c83` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认
[scripts/eval/to-markdown.py](../../scripts/eval/to-markdown.py) | `a7ddf67eaa69` | P8 | 复用或适配部署、运维、评估及仓库约定；验证后确认

## specification

基线文件 | SHA-256（前 12 位） | 阶段 | Go 去向 / 复用要求
--- | --- | --- | ---
[specs/001-project-skeleton/checklists/requirements.md](../../specs/001-project-skeleton/checklists/requirements.md) | `12d91836c6ff` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/001-project-skeleton/contracts/http-api.md](../../specs/001-project-skeleton/contracts/http-api.md) | `c8ad678345cd` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/001-project-skeleton/data-model.md](../../specs/001-project-skeleton/data-model.md) | `e7f36312bc69` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/001-project-skeleton/plan.md](../../specs/001-project-skeleton/plan.md) | `9eaf8e2b87bb` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/001-project-skeleton/quickstart.md](../../specs/001-project-skeleton/quickstart.md) | `fd9415d26841` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/001-project-skeleton/research.md](../../specs/001-project-skeleton/research.md) | `30d6bb590992` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/001-project-skeleton/spec.md](../../specs/001-project-skeleton/spec.md) | `b0e37f491416` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/001-project-skeleton/tasks.md](../../specs/001-project-skeleton/tasks.md) | `ba278d02fb29` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/002-ai-chat/checklists/requirements.md](../../specs/002-ai-chat/checklists/requirements.md) | `2771e79cb3d2` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/002-ai-chat/contracts/chat-api.md](../../specs/002-ai-chat/contracts/chat-api.md) | `7bd39a761379` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/002-ai-chat/data-model.md](../../specs/002-ai-chat/data-model.md) | `c3916244c2cd` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/002-ai-chat/plan.md](../../specs/002-ai-chat/plan.md) | `8ad56706ee3a` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/002-ai-chat/quickstart.md](../../specs/002-ai-chat/quickstart.md) | `3ae1cbc7aeec` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/002-ai-chat/research.md](../../specs/002-ai-chat/research.md) | `2dd295935a21` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/002-ai-chat/spec.md](../../specs/002-ai-chat/spec.md) | `1e4c92954b95` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/002-ai-chat/tasks.md](../../specs/002-ai-chat/tasks.md) | `00b1ba546653` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/003-task-agent/checklists/requirements.md](../../specs/003-task-agent/checklists/requirements.md) | `2b67aa592b8c` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/003-task-agent/contracts/agent-tools.md](../../specs/003-task-agent/contracts/agent-tools.md) | `7e0b2373b6a5` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/003-task-agent/contracts/task-api.md](../../specs/003-task-agent/contracts/task-api.md) | `2f01f65d8511` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/003-task-agent/data-model.md](../../specs/003-task-agent/data-model.md) | `aad3a69df3ed` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/003-task-agent/plan.md](../../specs/003-task-agent/plan.md) | `3f13d91755a4` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/003-task-agent/quickstart.md](../../specs/003-task-agent/quickstart.md) | `5ad1e7c395ca` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/003-task-agent/research.md](../../specs/003-task-agent/research.md) | `5ee83c99d72b` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/003-task-agent/spec.md](../../specs/003-task-agent/spec.md) | `9dc03cb2344c` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/003-task-agent/tasks.md](../../specs/003-task-agent/tasks.md) | `255284cccf53` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/004-knowledge-rag/checklists/requirements.md](../../specs/004-knowledge-rag/checklists/requirements.md) | `baf21b94dded` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/004-knowledge-rag/contracts/knowledge-api.md](../../specs/004-knowledge-rag/contracts/knowledge-api.md) | `714956fa5538` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/004-knowledge-rag/data-model.md](../../specs/004-knowledge-rag/data-model.md) | `67fa82c63a00` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/004-knowledge-rag/plan.md](../../specs/004-knowledge-rag/plan.md) | `d4065492a38a` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/004-knowledge-rag/quickstart.md](../../specs/004-knowledge-rag/quickstart.md) | `37e355c83ed5` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/004-knowledge-rag/research.md](../../specs/004-knowledge-rag/research.md) | `b8d0888de49b` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/004-knowledge-rag/spec.md](../../specs/004-knowledge-rag/spec.md) | `73750f34803a` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/004-knowledge-rag/tasks.md](../../specs/004-knowledge-rag/tasks.md) | `a5847e631fbd` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/005-calendar-reminders/checklists/requirements.md](../../specs/005-calendar-reminders/checklists/requirements.md) | `9ec0da766f81` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/005-calendar-reminders/contracts/calendar-api.md](../../specs/005-calendar-reminders/contracts/calendar-api.md) | `3490dec2af58` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/005-calendar-reminders/data-model.md](../../specs/005-calendar-reminders/data-model.md) | `49463a72356f` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/005-calendar-reminders/plan.md](../../specs/005-calendar-reminders/plan.md) | `0ade1aa7394c` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/005-calendar-reminders/quickstart.md](../../specs/005-calendar-reminders/quickstart.md) | `5d2fc7a9dd70` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/005-calendar-reminders/research.md](../../specs/005-calendar-reminders/research.md) | `1662208aa7aa` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/005-calendar-reminders/spec.md](../../specs/005-calendar-reminders/spec.md) | `d3dc4613dc73` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/005-calendar-reminders/tasks.md](../../specs/005-calendar-reminders/tasks.md) | `103b8d291c71` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/006-mcp-client/checklists/requirements.md](../../specs/006-mcp-client/checklists/requirements.md) | `7799426158bd` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/006-mcp-client/contracts/mcp-integration.md](../../specs/006-mcp-client/contracts/mcp-integration.md) | `ba5081db3657` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/006-mcp-client/data-model.md](../../specs/006-mcp-client/data-model.md) | `5cbfd08cc885` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/006-mcp-client/plan.md](../../specs/006-mcp-client/plan.md) | `fe21be09969b` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/006-mcp-client/quickstart.md](../../specs/006-mcp-client/quickstart.md) | `0eddb4f881a9` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/006-mcp-client/research.md](../../specs/006-mcp-client/research.md) | `6fb7a86d08d7` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/006-mcp-client/spec.md](../../specs/006-mcp-client/spec.md) | `9bfcf91d28f4` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/006-mcp-client/tasks.md](../../specs/006-mcp-client/tasks.md) | `f7b31276b683` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/007-chat-session-rename/checklists/requirements.md](../../specs/007-chat-session-rename/checklists/requirements.md) | `6ffe7ec1ee56` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/007-chat-session-rename/contracts/session-rename-api.md](../../specs/007-chat-session-rename/contracts/session-rename-api.md) | `5a6383149482` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/007-chat-session-rename/data-model.md](../../specs/007-chat-session-rename/data-model.md) | `804d9a7819fa` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/007-chat-session-rename/plan.md](../../specs/007-chat-session-rename/plan.md) | `a0a72e98f79d` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/007-chat-session-rename/quickstart.md](../../specs/007-chat-session-rename/quickstart.md) | `d8be8a633a2d` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/007-chat-session-rename/research.md](../../specs/007-chat-session-rename/research.md) | `e1b1731bd4f4` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/007-chat-session-rename/spec.md](../../specs/007-chat-session-rename/spec.md) | `26059632baf9` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/007-chat-session-rename/tasks.md](../../specs/007-chat-session-rename/tasks.md) | `e9f4591ba310` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/008-long-term-memory/checklists/requirements.md](../../specs/008-long-term-memory/checklists/requirements.md) | `46a70266c7c8` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/008-long-term-memory/contracts/memory-api.md](../../specs/008-long-term-memory/contracts/memory-api.md) | `7db1c97e0a4a` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/008-long-term-memory/data-model.md](../../specs/008-long-term-memory/data-model.md) | `dd513075bc20` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/008-long-term-memory/plan.md](../../specs/008-long-term-memory/plan.md) | `a47fa88da22b` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/008-long-term-memory/quickstart.md](../../specs/008-long-term-memory/quickstart.md) | `e5a866962e7b` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/008-long-term-memory/research.md](../../specs/008-long-term-memory/research.md) | `08f6c7a6d5b9` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/008-long-term-memory/spec.md](../../specs/008-long-term-memory/spec.md) | `7b4ab88cfb38` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/008-long-term-memory/tasks.md](../../specs/008-long-term-memory/tasks.md) | `ee954b0c4824` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/009-reliable-reminders/checklists/requirements.md](../../specs/009-reliable-reminders/checklists/requirements.md) | `2ca3723ca52d` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/009-reliable-reminders/contracts/reminder-delivery.md](../../specs/009-reliable-reminders/contracts/reminder-delivery.md) | `9ff3da8d1341` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/009-reliable-reminders/data-model.md](../../specs/009-reliable-reminders/data-model.md) | `7499e1e6f536` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/009-reliable-reminders/plan.md](../../specs/009-reliable-reminders/plan.md) | `1f81f0257c64` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/009-reliable-reminders/quickstart.md](../../specs/009-reliable-reminders/quickstart.md) | `4d0e03f877f6` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/009-reliable-reminders/research.md](../../specs/009-reliable-reminders/research.md) | `afbcc00f5d64` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/009-reliable-reminders/spec.md](../../specs/009-reliable-reminders/spec.md) | `1093e967b71f` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/009-reliable-reminders/tasks.md](../../specs/009-reliable-reminders/tasks.md) | `dfe3b9b69b00` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/010-rich-document-parsing/checklists/requirements.md](../../specs/010-rich-document-parsing/checklists/requirements.md) | `aa2ac8c7f965` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/010-rich-document-parsing/contracts/document-ingest.md](../../specs/010-rich-document-parsing/contracts/document-ingest.md) | `33013260f172` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/010-rich-document-parsing/data-model.md](../../specs/010-rich-document-parsing/data-model.md) | `a728a49ff63b` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/010-rich-document-parsing/plan.md](../../specs/010-rich-document-parsing/plan.md) | `f3c356e20db1` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/010-rich-document-parsing/quickstart.md](../../specs/010-rich-document-parsing/quickstart.md) | `ad385c25bf96` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/010-rich-document-parsing/research.md](../../specs/010-rich-document-parsing/research.md) | `bbae314a7110` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/010-rich-document-parsing/spec.md](../../specs/010-rich-document-parsing/spec.md) | `b43c00008eee` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/010-rich-document-parsing/tasks.md](../../specs/010-rich-document-parsing/tasks.md) | `a2123e3155ef` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/011-tool-audit-and-mcp-status/plan.md](../../specs/011-tool-audit-and-mcp-status/plan.md) | `417a2c6a55e1` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/011-tool-audit-and-mcp-status/spec.md](../../specs/011-tool-audit-and-mcp-status/spec.md) | `a3d9646761a9` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/011-tool-audit-and-mcp-status/tasks.md](../../specs/011-tool-audit-and-mcp-status/tasks.md) | `c89608532822` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/012-data-export/plan.md](../../specs/012-data-export/plan.md) | `ebd0611f2e6c` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/012-data-export/spec.md](../../specs/012-data-export/spec.md) | `19c9f868ef5a` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/012-data-export/tasks.md](../../specs/012-data-export/tasks.md) | `05acd250653a` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/013-calendar-month-view/plan.md](../../specs/013-calendar-month-view/plan.md) | `05cbf99e1264` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/013-calendar-month-view/spec.md](../../specs/013-calendar-month-view/spec.md) | `dc12adb791b2` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/013-calendar-month-view/tasks.md](../../specs/013-calendar-month-view/tasks.md) | `8d2df6f1a58a` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/014-task-tags/plan.md](../../specs/014-task-tags/plan.md) | `3662c8275e0a` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/014-task-tags/spec.md](../../specs/014-task-tags/spec.md) | `d144947990c9` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/014-task-tags/tasks.md](../../specs/014-task-tags/tasks.md) | `b0f87ac80820` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/015-single-user-auth/plan.md](../../specs/015-single-user-auth/plan.md) | `4b9bf5a8214b` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/015-single-user-auth/spec.md](../../specs/015-single-user-auth/spec.md) | `ec81bd88c731` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/015-single-user-auth/tasks.md](../../specs/015-single-user-auth/tasks.md) | `2076457e23b6` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/016-list-and-search/plan.md](../../specs/016-list-and-search/plan.md) | `fa3c5d377392` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/016-list-and-search/spec.md](../../specs/016-list-and-search/spec.md) | `02dbeea023df` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/016-list-and-search/tasks.md](../../specs/016-list-and-search/tasks.md) | `e457811392c3` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/017-global-search/plan.md](../../specs/017-global-search/plan.md) | `ae7cbbbf00f8` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/017-global-search/spec.md](../../specs/017-global-search/spec.md) | `e14fe7005b70` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/017-global-search/tasks.md](../../specs/017-global-search/tasks.md) | `f2d4f7789a39` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/018-ui-optimization/checklists/requirements.md](../../specs/018-ui-optimization/checklists/requirements.md) | `f2b54b96d6b7` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/018-ui-optimization/contracts/ui-modules.md](../../specs/018-ui-optimization/contracts/ui-modules.md) | `592f563ad4b1` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/018-ui-optimization/data-model.md](../../specs/018-ui-optimization/data-model.md) | `181449f38d0c` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/018-ui-optimization/plan.md](../../specs/018-ui-optimization/plan.md) | `f2822dc379ac` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/018-ui-optimization/quickstart.md](../../specs/018-ui-optimization/quickstart.md) | `897f8371f811` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/018-ui-optimization/research.md](../../specs/018-ui-optimization/research.md) | `27478ec3eb46` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/018-ui-optimization/spec.md](../../specs/018-ui-optimization/spec.md) | `b54defc52e87` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/018-ui-optimization/tasks.md](../../specs/018-ui-optimization/tasks.md) | `827f420af898` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/019-knowledge-chat-settings/contracts/chat-knowledge-and-settings.md](../../specs/019-knowledge-chat-settings/contracts/chat-knowledge-and-settings.md) | `bc08acf9cc78` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/019-knowledge-chat-settings/data-model.md](../../specs/019-knowledge-chat-settings/data-model.md) | `bf6b3f2e98c9` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/019-knowledge-chat-settings/plan.md](../../specs/019-knowledge-chat-settings/plan.md) | `51398ac87381` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/019-knowledge-chat-settings/quickstart.md](../../specs/019-knowledge-chat-settings/quickstart.md) | `b292c1ba98a6` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/019-knowledge-chat-settings/spec.md](../../specs/019-knowledge-chat-settings/spec.md) | `b442a1641420` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/019-knowledge-chat-settings/tasks.md](../../specs/019-knowledge-chat-settings/tasks.md) | `1e16ab67d2fc` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/020-chat-ui-redesign/spec.md](../../specs/020-chat-ui-redesign/spec.md) | `f789f50fd593` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/020-chat-ui-redesign/tasks.md](../../specs/020-chat-ui-redesign/tasks.md) | `ff9a33af3080` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/021-memory-layering/spec.md](../../specs/021-memory-layering/spec.md) | `f16417d0b0c7` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/022-agent-planning/spec.md](../../specs/022-agent-planning/spec.md) | `e8e3a7e5c1a3` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/023-operational-optimization/plan.md](../../specs/023-operational-optimization/plan.md) | `008b53fcb17b` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/023-operational-optimization/research.md](../../specs/023-operational-optimization/research.md) | `a25680a2d055` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/023-operational-optimization/spec.md](../../specs/023-operational-optimization/spec.md) | `fa5f591c2be7` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/023-operational-optimization/tasks.md](../../specs/023-operational-optimization/tasks.md) | `4b1ed4a71014` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/023-operational-optimization/validation.md](../../specs/023-operational-optimization/validation.md) | `9aa887155d99` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/023-plan-enforcement/spec.md](../../specs/023-plan-enforcement/spec.md) | `fbea74f66838` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/024-window-probe-refine/spec.md](../../specs/024-window-probe-refine/spec.md) | `d4b37c085e3f` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/025-production-security/plan.md](../../specs/025-production-security/plan.md) | `e6188e2f2b6c` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/025-production-security/research.md](../../specs/025-production-security/research.md) | `fb8ab17b399d` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/025-production-security/spec.md](../../specs/025-production-security/spec.md) | `8ee298e6b83f` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/025-production-security/tasks.md](../../specs/025-production-security/tasks.md) | `d2460b0eb62d` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/025-production-security/validation.md](../../specs/025-production-security/validation.md) | `d8db7492487c` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/026-durable-agent-execution/plan.md](../../specs/026-durable-agent-execution/plan.md) | `29b2c33c30ba` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/026-durable-agent-execution/spec.md](../../specs/026-durable-agent-execution/spec.md) | `ee6a21edd563` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/026-durable-agent-execution/tasks.md](../../specs/026-durable-agent-execution/tasks.md) | `a0d63b2f4eed` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/027-agent-user-interaction/plan.md](../../specs/027-agent-user-interaction/plan.md) | `f17e049f62a8` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/027-agent-user-interaction/spec.md](../../specs/027-agent-user-interaction/spec.md) | `3913bb38b1ab` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/027-agent-user-interaction/tasks.md](../../specs/027-agent-user-interaction/tasks.md) | `bc2d3f3cd149` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/028-runtime-performance/plan.md](../../specs/028-runtime-performance/plan.md) | `f8b4b9891de6` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/028-runtime-performance/research.md](../../specs/028-runtime-performance/research.md) | `d86c557b9bc4` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/028-runtime-performance/spec.md](../../specs/028-runtime-performance/spec.md) | `b8ec6a347fdb` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/028-runtime-performance/tasks.md](../../specs/028-runtime-performance/tasks.md) | `212ae2ebf5a1` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/028-runtime-performance/validation.md](../../specs/028-runtime-performance/validation.md) | `25cb3bd90f79` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/029-rag-infrastructure/plan.md](../../specs/029-rag-infrastructure/plan.md) | `6f2f3514beff` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/029-rag-infrastructure/research.md](../../specs/029-rag-infrastructure/research.md) | `9d05d8bacb40` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/029-rag-infrastructure/spec.md](../../specs/029-rag-infrastructure/spec.md) | `a922c3124eeb` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/029-rag-infrastructure/tasks.md](../../specs/029-rag-infrastructure/tasks.md) | `47844cbd8ace` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/029-rag-infrastructure/validation.md](../../specs/029-rag-infrastructure/validation.md) | `68821922a79c` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/030-rag-local-rollout/evaluation.json](../../specs/030-rag-local-rollout/evaluation.json) | `2d3195890aa9` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/030-rag-local-rollout/report.md](../../specs/030-rag-local-rollout/report.md) | `2747e05a05f9` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/030-rag-local-rollout/spec.md](../../specs/030-rag-local-rollout/spec.md) | `53306c1d722b` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/030-rag-local-rollout/tasks.md](../../specs/030-rag-local-rollout/tasks.md) | `91b6579ac5aa` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异
[specs/032-distributed-workers/tasks.md](../../specs/032-distributed-workers/tasks.md) | `8e4ff7b81112` | P0-P8 | 保留业务规格；按 Go 实现核对并追加差异

## sql_migration

基线文件 | SHA-256（前 12 位） | 阶段 | Go 去向 / 复用要求
--- | --- | --- | ---
[backend/src/main/resources/db/migration/V10__create_task_tag.sql](../../backend/src/main/resources/db/migration/V10__create_task_tag.sql) | `e009b673fe42` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V11__create_chat_message_citation.sql](../../backend/src/main/resources/db/migration/V11__create_chat_message_citation.sql) | `36e073ef4e0c` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V12__add_chat_session_summary.sql](../../backend/src/main/resources/db/migration/V12__add_chat_session_summary.sql) | `76c84a88537b` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V13__add_memory_source.sql](../../backend/src/main/resources/db/migration/V13__add_memory_source.sql) | `07e96890d60d` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V14__add_message_attachments.sql](../../backend/src/main/resources/db/migration/V14__add_message_attachments.sql) | `828120724ced` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V15__create_token_usage.sql](../../backend/src/main/resources/db/migration/V15__create_token_usage.sql) | `da4ff6afacd1` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V16__add_confirmation_failed_status.sql](../../backend/src/main/resources/db/migration/V16__add_confirmation_failed_status.sql) | `a7449e5ca2b0` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V17__add_memory_content_hash.sql](../../backend/src/main/resources/db/migration/V17__add_memory_content_hash.sql) | `85b7451abbb7` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V18__add_task_previous_status.sql](../../backend/src/main/resources/db/migration/V18__add_task_previous_status.sql) | `fc76329950d9` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V19__index_vector_store_document_id.sql](../../backend/src/main/resources/db/migration/V19__index_vector_store_document_id.sql) | `0290f28fd14c` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V1__enable_extensions.sql](../../backend/src/main/resources/db/migration/V1__enable_extensions.sql) | `c66873b5fec2` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V20__create_ai_model_config.sql](../../backend/src/main/resources/db/migration/V20__create_ai_model_config.sql) | `79f7047d85da` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V21__create_ai_model_profile.sql](../../backend/src/main/resources/db/migration/V21__create_ai_model_profile.sql) | `cedb78f42a35` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V22__add_ai_model_profile_protocol.sql](../../backend/src/main/resources/db/migration/V22__add_ai_model_profile_protocol.sql) | `0df15d9a79e7` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V23__add_profile_models_and_session_model.sql](../../backend/src/main/resources/db/migration/V23__add_profile_models_and_session_model.sql) | `4a0fc22a65b3` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V24__add_profile_audio_model.sql](../../backend/src/main/resources/db/migration/V24__add_profile_audio_model.sql) | `e00c14946eb3` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V25__add_model_context_window.sql](../../backend/src/main/resources/db/migration/V25__add_model_context_window.sql) | `e8ba5d938448` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V26__memory_layering_and_dedup.sql](../../backend/src/main/resources/db/migration/V26__memory_layering_and_dedup.sql) | `e741b9479875` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V27__create_agent_plan.sql](../../backend/src/main/resources/db/migration/V27__create_agent_plan.sql) | `1f5ffc15d858` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V28__create_web_push.sql](../../backend/src/main/resources/db/migration/V28__create_web_push.sql) | `52f0931a6c63` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V29__expand_encrypted_model_key.sql](../../backend/src/main/resources/db/migration/V29__expand_encrypted_model_key.sql) | `e073be70935d` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V2__create_chat_tables.sql](../../backend/src/main/resources/db/migration/V2__create_chat_tables.sql) | `1e85d8fbe5ff` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V30__index_operation_record_retention.sql](../../backend/src/main/resources/db/migration/V30__index_operation_record_retention.sql) | `e32c2c5525d2` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V31__persist_chat_context_budget.sql](../../backend/src/main/resources/db/migration/V31__persist_chat_context_budget.sql) | `2e51dc3cc56b` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V32__durable_chat_runs.sql](../../backend/src/main/resources/db/migration/V32__durable_chat_runs.sql) | `a1cd6a766a8c` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V33__agent_questions_and_followups.sql](../../backend/src/main/resources/db/migration/V33__agent_questions_and_followups.sql) | `b6b993f1f362` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V34__knowledge_index_jobs.sql](../../backend/src/main/resources/db/migration/V34__knowledge_index_jobs.sql) | `d202cb2a2f1b` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V35__search_projection_outbox.sql](../../backend/src/main/resources/db/migration/V35__search_projection_outbox.sql) | `c7823eeabcf0` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V36__knowledge_provenance_conflicts.sql](../../backend/src/main/resources/db/migration/V36__knowledge_provenance_conflicts.sql) | `e1276ca0684c` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V37__runtime_coordination.sql](../../backend/src/main/resources/db/migration/V37__runtime_coordination.sql) | `8a0d0dcdea52` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V38__durable_worker_signals.sql](../../backend/src/main/resources/db/migration/V38__durable_worker_signals.sql) | `edc66e4e67f2` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V39__search_projection_leases.sql](../../backend/src/main/resources/db/migration/V39__search_projection_leases.sql) | `b306bcab338a` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V3__create_task_and_agent_tables.sql](../../backend/src/main/resources/db/migration/V3__create_task_and_agent_tables.sql) | `e0fe9ef64b4f` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V40__reminder_webhook_delivery.sql](../../backend/src/main/resources/db/migration/V40__reminder_webhook_delivery.sql) | `0b16e7f6a051` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V41__reminder_broadcast.sql](../../backend/src/main/resources/db/migration/V41__reminder_broadcast.sql) | `485d28039834` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V42__knowledge_candidate_cache_generation.sql](../../backend/src/main/resources/db/migration/V42__knowledge_candidate_cache_generation.sql) | `8ac841ec60c1` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V4__create_knowledge_tables.sql](../../backend/src/main/resources/db/migration/V4__create_knowledge_tables.sql) | `1bf51f438c01` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V5__generalize_confirmation_table.sql](../../backend/src/main/resources/db/migration/V5__generalize_confirmation_table.sql) | `fc096a97528f` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V6__rename_confirmation_primary_key.sql](../../backend/src/main/resources/db/migration/V6__rename_confirmation_primary_key.sql) | `a36559402ecd` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V7__create_calendar_tables.sql](../../backend/src/main/resources/db/migration/V7__create_calendar_tables.sql) | `9620865323e2` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V8__extend_confirmation_action_mcp.sql](../../backend/src/main/resources/db/migration/V8__extend_confirmation_action_mcp.sql) | `6acb3666ed8d` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证
[backend/src/main/resources/db/migration/V9__create_memory.sql](../../backend/src/main/resources/db/migration/V9__create_memory.sql) | `224d93db34f5` | P1 | 保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证

## 提取边界

- 词法索引不是 Java AST；文件哈希是完整性基准。
- 注解路由不含 Spring 自动装配的 MCP/Actuator/框架错误路径，另见 contracts.md。
- Test 注解数不等于参数化测试实际执行数。
- 清单完整不代表行为等价；verified 需要契约/集成/故障恢复证据。
