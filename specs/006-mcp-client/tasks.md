---
description: "Task list for 阶段 6 — MCP 客户端接入"
---

# Tasks: 阶段 6 — MCP 客户端接入

**Input**: Design documents from `/specs/006-mcp-client/`

> 状态说明：本清单为**规格阶段**产物，实现待用户确认规格后开始。
> 每个 Phase 一次提交；`[P]` 标记可并行任务；完成后在方框内记 `x` 并在 Phase 标题记 ✅。

---

## Phase 1: 配置与 server 生命周期 ✅（提交 `1ec39b2`）

- [x] T001 `pom.xml` 引 `spring-ai-starter-mcp-client`（版本随 BOM 2.0.1，不单独锁）；确认传递依赖后 `./mvnw -B verify` 基线仍全绿（零回归第一验）
- [x] T002 [P] `mcp/config/McpProperties`（`@ConfigurationProperties(prefix = "mcp")`：enabled / servers map / autoTools / connectTimeout；env 直通 map）
- [x] T003 [P] `mcp/config/McpConfigValidator`（启动期：缺 command 即启动失败——配置错误不降级）
- [x] T004 `mcp/client/McpClientRegistry`：按配置建 stdio 客户端（超时可控）、列举工具、记录 server 状态
- [x] T005 `mcp/client/McpServerStatus`（可用/不可用 + 失败原因；进程内事实，不落库）
- [x] T006 生命周期：`@PostConstruct` 连接、`DisposableBean.destroy()` 关闭；**不依赖 server 检测 stdin 自退**（契约 L3）
- [x] T007 `.env.example` 增加 `MCP_*` 段：filesystem 示例（**限定目录**）+ GitHub 示例（token 走 `env` 引用 `${GITHUB_TOKEN}`）
- [x] T008 集成测试（JVM 内自建 stdio server）：装配成功列举工具；命令不存在 → 降级不拖垮启动；未配置 → 零初始化
- [x] T009 **Phase 1 提交**：`feat(mcp): 阶段 6 Phase 1 配置与 server 生命周期（T001-T009）`

> **实现偏差（同提交回改上游文档）**：① 环境变量名单数改复数 `MCP_SERVERS_<NAME>_*`——
> 单数写法经 relaxed binding 归一到 `mcp.server.*`，与字段 `servers` 对不上且**静默绑不上**；
> ② 删除 `MCP_SERVERS` 列表属性（映射键即清单）；③ 测试 server 子进程日志曾污染协议流
> （logback 默认写 stdout），helper 已把 System.out 改道 stderr。

## Phase 2: 工具适配层（命名 / 策略 / 装饰）✅（提交 `7b0e2d3`）

- [x] T010 `mcp/tool/McpToolNaming`（纯函数）：`mcp_<server>_<tool>` 命名 + 清洗 + 长度上限 + 冲突判定；单测覆盖跨 server 同名、空名、非法字符
- [x] T011 `mcp/tool/McpToolPolicy`（纯函数）：策略解析顺序（autoTools 命中 → AUTO；否则 CONFIRM）；单测覆盖默认值、空清单、按原始名匹配
- [x] T012 `mcp/tool/McpToolCallback`：装饰 `ToolCallback`——AUTO 直行 / CONFIRM 生成待确认并返回提示语（**不触达 server**）；两条路径都经 `ToolExecutor` 记审计
- [x] T013 `mcp/tool/McpToolCallbackProvider`：以 Spring AI `ToolCallbackProvider` 形态暴露装饰后的回调
- [x] T014 工具 schema 自检：`type: object` 且 `properties` 为对象；异常工具剔除 + 告警
- [x] T015 单元测试：装饰层策略分支（AUTO 无确认记录、CONFIRM 零触达 server——副作用文件作证）
- [x] T016 **Phase 2 提交**（与 Phase 3 合并提交，见下）

## Phase 3: 确认机制扩展 ✅（提交 `7b0e2d3`）

- [x] T017 `V8__extend_confirmation_action_mcp.sql`：扩展 `ck_confirmation_action` 加入 `MCP_TOOL_CALL`（只追加，不改写 V1–V7）
- [x] T018 `ConfirmationAction` 新增 `MCP_TOOL_CALL`（编译器立即拦下 TaskConfirmationHandler 的穷尽 switch——不写 default 的价值再次兑现）
- [x] T019 `mcp/confirmation/McpConfirmationSummary`（纯函数）：确定性摘要（键排序、值截断、最多 3 参）；单测覆盖截断与缺参
- [x] T020 `mcp/confirmation/McpConfirmationHandler`（`ConfirmationActionHandler`）：确认后真正调用 MCP 工具；**无条件注册**（否则 MCP 关闭时「枚举有值无处理器」会把应用挡在启动期）
- [x] T021 启动期校验自然覆盖：`ConfirmationRegistry` 校验新动作有处理器
- [x] T022 端到端集成测试：拦截（**副作用文件不存在**）→ 摘要含 server/工具/参数 → 确认执行（文件落盘）→ 重复确认拒绝 → 审计留痕
- [x] T023 **Phase 2+3 提交**：`feat(mcp): 阶段 6 Phase 2+3 工具适配层与确认机制（T010-T023）`

## Phase 4: 装配与前端确认展示 ✅

- [x] T024 `AiChatClientProvider`：并列收集 `ToolCallbackProvider`，与既有 `@Tool` 路径合并挂载；`toolContributors` 路径零改动
- [x] T025 装配期冲突校验：`McpToolConflictValidator`（mcp 模块，方向正确——common 不依赖 mcp）：MCP 装配名 × 内置工具名有交集 → **启动失败**并列出冲突项
- [x] T026 启动日志：每个 server 状态与工具数（registry）、逐工具装配名与策略（provider）、总数与冲突结论（validator）
- [x] T027 前端确认卡片：**无需改动**——既有卡片直接渲染 `confirmation.summary`，而 MCP 摘要已含 server 名与工具名（实测确认渲染路径）
- [x] T028 对话链路端到端测试（`McpChatToolCallingIntegrationTest`）：桩模型编排工具调用，断言 AUTO 工具结果回到模型下一轮上下文、CONFIRM 工具拦截且确认后落盘；前端类型检查 + 构建通过
- [x] **Phase 4 提交**：`feat(mcp): 阶段 6 Phase 4 并入 ChatClient 装配与冲突校验`

> **实现偏差**：T027 原计划「扩展确认卡片展示 server 名 + 工具名」，实测发现**无需改动**——
> 卡片渲染的就是服务端生成的 summary，而 MCP 摘要本身就含这两项。前端零改动，符合
> 「不加管理界面」的范围决策。

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
