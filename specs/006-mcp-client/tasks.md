---
description: "Task list for 阶段 6 — MCP 客户端接入"
---

# Tasks: 阶段 6 — MCP 客户端接入

**Input**: Design documents from `/specs/006-mcp-client/`

> 状态说明：本清单为**规格阶段**产物，实现待用户确认规格后开始。
> 每个 Phase 一次提交；`[P]` 标记可并行任务；完成后在方框内记 `x` 并在 Phase 标题记 ✅。

---

## Phase 1: 配置与 server 生命周期

- [ ] T001 `pom.xml` 引 `spring-ai-starter-mcp-client`（版本随 BOM 2.0.1，不单独锁）；确认传递依赖后 `./mvnw -B verify` 基线仍全绿（零回归第一验）
- [ ] T002 [P] `mcp/config/McpProperties`（`@ConfigurationProperties(prefix = "mcp")`：enabled / servers map / autoTools / connectTimeout；env 直通 map）
- [ ] T003 [P] `mcp/config/McpConfigValidator`（启动期：`MCP_SERVERS` 每个名字都有配置、command 非空——配置错误即启动失败，契约 C3/C4）
- [ ] T004 `mcp/client/McpClientRegistry`：按配置建 stdio 客户端（超时可控）、列举工具、记录 server 状态
- [ ] T005 `mcp/client/McpServerStatus`（可用/不可用 + 失败原因；进程内事实，不落库）
- [ ] T006 生命周期：实现 `SmartLifecycle`（或 `DisposableBean`），应用关闭时逐 server 销毁；**不得依赖 server 检测 stdin 自退**（契约 L3）
- [ ] T007 `.env.example` 增加 `MCP_*` 段：filesystem 示例（**限定目录** `./mcp-sandbox`）+ GitHub 示例（token 走 `env` 引用 `${GITHUB_TOKEN}`）
- [ ] T008 集成测试（JVM 内自建 stdio server）：装配成功列举工具；命令不存在 → 降级不拖垮启动；未配置 → 零初始化（SC-03 的第一道自动化证据）
- [ ] T009 **Phase 1 提交**：`feat(mcp): MCP 配置与 server 生命周期`——正文写清「为什么零启用默认」与「配置错误 vs 运行故障的区别对待」

## Phase 2: 工具适配层（命名 / 策略 / 装饰）

- [ ] T010 `mcp/tool/McpToolNaming`（纯函数）：`mcp_<server>_<tool>` 命名 + 与内置工具名的冲突判定；单测覆盖跨 server 同名、空名、非法字符
- [ ] T011 `mcp/tool/McpToolPolicy`（纯函数）：策略解析顺序（autoTools 命中 → AUTO；否则 CONFIRM）；单测覆盖默认值、大小写、空清单
- [ ] T012 `mcp/tool/McpToolCallback`：装饰 `ToolCallback`——调用前查策略：AUTO 直行 / CONFIRM 生成待确认并返回提示语（**不触达 server**）；调用后记审计（含耗时与结果）
- [ ] T013 `mcp/tool/McpToolCallbackProvider`：把装饰后的回调以 Spring AI `ToolCallbackProvider` 形态暴露
- [ ] T014 工具 schema 自检：required 齐全、类型可解析；异常工具剔除 + 告警（Edge Cases）
- [ ] T015 单元测试：装饰层的策略分支（AUTO 不产生确认记录、CONFIRM 零触达 server——用桩回调证明「没被调用」）
- [ ] T016 **Phase 2 提交**：`feat(mcp): 工具适配层（命名/策略/审计）`

## Phase 3: 确认机制扩展

- [ ] T017 `V8__extend_confirmation_action_mcp.sql`：扩展 `confirmation_action_check` 加入 `MCP_TOOL_CALL`（只追加，不改写 V1–V7；约束名沿用 V6 确立的名称）
- [ ] T018 `ConfirmationAction` 新增 `MCP_TOOL_CALL`
- [ ] T019 `mcp/confirmation/McpConfirmationSummary`（纯函数）：服务端生成摘要（server / 工具 / 关键参数截断规则见 contracts §3.3 P5）；单测覆盖截断与缺参
- [ ] T020 `mcp/confirmation/McpConfirmationHandler`（`ConfirmationActionHandler`）：确认后真正调用 MCP 工具；server 不可用时返回明确错误
- [ ] T021 启动期校验自然覆盖：`ConfirmationRegistry` 校验新动作有处理器（若漏配，启动失败——这是既有机制的礼物，加一条测试守住）
- [ ] T022 集成测试：CONFIRM 全链路——拦截（零执行）→ 摘要内容 → 确认执行（server 真被调用）→ 重复确认拒绝 → 过期拒绝 → 拒绝后无悬挂执行
- [ ] T023 **Phase 3 提交**：`feat(mcp): 变更类工具接入人工确认`

## Phase 4: 装配与前端确认展示

- [ ] T024 `AiChatClientProvider`：并列收集 `ToolCallbackProvider`，与既有 `@Tool` 路径合并挂载；`toolContributors` 路径零改动
- [ ] T025 装配期冲突校验落地：MCP 工具名 × 内置工具名有交集 → **启动失败**并列出三要素（工具名 / 来源 server / 冲突内置工具）
- [ ] T026 启动日志：每个 server 的状态与工具数量（含前缀示例），便于真机核对 SC-001
- [ ] T027 前端：确认卡片展示 server 名 + 工具名（`api/confirmations.ts` DTO 扩展 + `ChatView.vue` 既有组件）；不加管理界面
- [ ] T028 前端类型检查 + 构建通过；**Phase 4 提交**：`feat(mcp): 并入 ChatClient 装配与确认展示`

## Phase 5: 测试补全与文档

- [ ] T029 审计契约测试：AUTO 成功/失败、CONFIRM 拦截与执行各一条，来源可区分（BUILTIN / MCP + server 名）
- [ ] T030 生命周期测试：关闭后测试 server 无残留（管道/线程终止）；单 server 销毁失败不影响整体关闭
- [ ] T031 故障注入测试：挂起 server 超时降级且第二个 server 不受影响；schema 损坏工具被剔除
- [ ] T032 无模型场景测试：不配 ChatModel 时 MCP 组件仍初始化、工具可列举（可观测承诺）
- [ ] T033 全量验证：`./mvnw -B verify`（SC-02 + SC-03：新增测试全绿且既有 115 个零回归）；前端 type-check + build
- [ ] T034 文档：`docs/architecture.md` 补第 14 节（MCP 链路与三条约束）；README「当前状态」与工具总数更新（内置 14 + MCP 动态）
- [ ] T035 quickstart「验证记录」表逐项真机填写（SC-001/004/005）；**Phase 5 提交**：`test(mcp): 补全测试与文档`（若真机验证跨天，拆为两笔提交并如实标注）

---

## Notes（贯穿全清单的硬约束）

1. **已应用的迁移只追加**：V8 是全新文件；任何对 V1–V7 的「顺手修正」都禁止（阶段 4 的校验和教训）。
2. **默认 CONFIRM（fail-closed）**：策略解析漏配时错误方向必须指向「更安全」，不允许「猜只读」。
3. **正确性故障不降级**：工具名冲突 = 启动失败；server 连不上 = 降级。两类故障的区别对待是刻意设计（R-07），测试要分别守住。
4. **测试不依赖网络与 npx**：所有 MCP server 由测试 JVM 内自建（SDK stdio server + 管道）；真机 npx 联调只进 quickstart 人工项。
5. **无模型可启动**：任何 MCP 初始化路径都不得要求 ChatModel 存在。
6. **摘要不采信模型文本**：CONFIRM 的 summary 一律由 `McpConfirmationSummary` 依据 payload 生成。
7. **前端零新增页面**：只扩展现有确认卡片；发现需要管理界面时记录下来另开阶段，不得顺手做。
8. **提交信息避免 ASCII 双引号与 `<` `>`**（会破坏 shell 引用）；长信息写文件用 `git commit -F`。
