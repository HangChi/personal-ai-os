# 规格与设计索引

本目录保存分阶段需求、设计、研究、任务、契约和验收证据。它解释“为什么这样设计”及当时的完成范围；quickstart、待办、测试数和“未来”措辞可能属于旧阶段，不能直接作为当前安装指南。当前行为见[文档中心](../docs/README.md)，双端状态见[版本说明](../docs/backend-parity.md)，评测见[报告索引](../docs/reports/README.md)。

## 阅读方式

- `spec.md`：当时的问题、需求与验收目标；提出过的功能不一定已经实现。
- `plan.md/research.md/data-model.md/contracts/`：技术决策、替代方案与数据/协议设计。
- `tasks.md/validation.md`：任务和实际执行的验证，保留未完成与失败。
- Go 迁移的固定 inventory/oracle/progress 与 release gates 见[033 入口](033-go-rewrite/README.md)；V1–V42 历史基线不可改写，当前共享迁移为 V1–V49。
- 同编号的两个 023 是原目录事实，索引保留各自名称，不重编号破坏链接。

## 特性目录

| 目录 | 原规格 |
| --- | --- |
| `001-project-skeleton` | [阶段 0 — 项目骨架初始化](001-project-skeleton/spec.md) |
| `002-ai-chat` | [阶段 1 — AI 对话与聊天历史](002-ai-chat/spec.md) |
| `003-task-agent` | [阶段 2 — 任务管理与 Agent 工具调用](003-task-agent/spec.md) |
| `004-knowledge-rag` | [阶段 3 — 个人知识库与 RAG](004-knowledge-rag/spec.md) |
| `005-calendar-reminders` | [阶段 4 — 日程与定时提醒](005-calendar-reminders/spec.md) |
| `006-mcp-client` | [阶段 6 — MCP 客户端接入](006-mcp-client/spec.md) |
| `007-chat-session-rename` | [阶段 7 — 会话重命名](007-chat-session-rename/spec.md) |
| `008-long-term-memory` | [阶段 8 — 长期记忆](008-long-term-memory/spec.md) |
| `009-reliable-reminders` | [阶段 9 — 可靠提醒](009-reliable-reminders/spec.md) |
| `010-rich-document-parsing` | [阶段 10 — 富文档解析](010-rich-document-parsing/spec.md) |
| `011-tool-audit-and-mcp-status` | [阶段 11 — 工具审计与 MCP 状态](011-tool-audit-and-mcp-status/spec.md) |
| `012-data-export` | [阶段 12 — 数据导出](012-data-export/spec.md) |
| `013-calendar-month-view` | [阶段 13 — 日历月视图](013-calendar-month-view/spec.md) |
| `014-task-tags` | [阶段 14 — 任务标签](014-task-tags/spec.md) |
| `015-single-user-auth` | [阶段 15 — 单用户认证](015-single-user-auth/spec.md) |
| `016-list-and-search` | [阶段 16 — 列表分页与站内搜索](016-list-and-search/spec.md) |
| `017-global-search` | [阶段 17 — 跨模块全局搜索](017-global-search/spec.md) |
| `018-ui-optimization` | [全站 UI 优化（设计系统 + 逐页重构）](018-ui-optimization/spec.md) |
| `019-knowledge-chat-settings` | [对话知识库增强 + 设置页](019-knowledge-chat-settings/spec.md) |
| `020-chat-ui-redesign` | [对话页 UI 重设计](020-chat-ui-redesign/spec.md) |
| `021-memory-layering` | [阶段 21 — 记忆分层与近重复消解](021-memory-layering/spec.md) |
| `022-agent-planning` | [阶段 22 — Agent 规划编排](022-agent-planning/spec.md) |
| `023-operational-optimization` | [运行可靠性与规模优化](023-operational-optimization/spec.md) |
| `023-plan-enforcement` | [阶段 23 — 计划强制（从记录层到控制层）](023-plan-enforcement/spec.md) |
| `024-window-probe-refine` | [阶段 24 — 上下文窗口识别修正与单模型查询](024-window-probe-refine/spec.md) |
| `025-production-security` | [生产安全加固](025-production-security/spec.md) |
| `026-durable-agent-execution` | [持久化 Agent 执行与可见活动](026-durable-agent-execution/spec.md) |
| `027-agent-user-interaction` | [Agent 澄清问题与执行中的跟进消息](027-agent-user-interaction/spec.md) |
| `028-runtime-performance` | [运行效率与后台任务](028-runtime-performance/spec.md) |
| `029-rag-infrastructure` | [基础设施扩展与可信混合 RAG](029-rag-infrastructure/spec.md) |
| `030-rag-local-rollout` | [本地发布与真实模型验收](030-rag-local-rollout/spec.md) |
| `033-go-rewrite` | [规格：Go 全量后端迁移](033-go-rewrite/spec.md) |
| `034-task-workspace` | [今日工作台与项目任务](034-task-workspace/spec.md) |
| `035-safe-automation` | [安全自动化与资料互通](035-safe-automation/spec.md) |
| `036-workspace-ui` | [工作区界面整合](036-workspace-ui/spec.md) |
| `037-conversational-knowledge` | [037 · 对话来源展示与知识增强上下文](037-conversational-knowledge/spec.md) |
| `038-task-create-layout` | [新建任务表单排版优化](038-task-create-layout/spec.md) |
| `039-evidence-aware-answers` | [039 · 按问题作答与来源分歧](039-evidence-aware-answers/spec.md) |

新增特性追加目录并更新本索引；实现变化同时维护当前文档。不要通过勾选旧任务、修改标注或回填报告来宣称新版本通过。
