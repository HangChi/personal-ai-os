# Requirements Checklist: 阶段 6 — MCP 客户端接入

> 逐条核对 spec.md 的需求。状态：⬜ 未实现 / ✅ 已实现并验证 / ⏳ 已实现未验证。
> 「与宪法的对齐核对」一节在末尾。
>
> **2026-10-15 实现完成后逐条回填**：本清单是**实现前**写的核对表，状态列原为全 ⬜；
> 下列状态与证据来自实现期的实际测试与真机验证（不是计划值）。

## 用户故事核对

| # | 用户故事 | 独立可测 | 覆盖验收场景 | 状态 |
|---|---|---|---|---|
| US1 | 配置并接入本地 MCP server | ✅ 启动 + 工具清单断言 | 4/4 | ✅ |
| US2 | 只读工具直接放行 | ✅ AUTO 调用无待确认记录 | 3/3 | ✅ |
| US3 | 变更类工具走人工确认 | ✅ 拦截 → 确认执行 → 拒绝/过期 | 5/5 | ✅ |
| US4 | 工具调用可观测 | ✅ 审计记录含来源区分 | 3/3 | ✅ |
| US5 | 对话中自然使用 | ✅ 真机 filesystem 联调（quickstart） | 3/3 | ✅ |

## 功能需求核对（FR）

| # | 需求 | 怎么验 | 状态 |
|---|---|---|---|
| FR-001 | 配置接入多个 stdio server | 集成测试配 2 个 server 双双装配（`oneServerFailureDoesNotAffectOthers`、降级测试的 broken + test） | ✅ |
| FR-002 | 单 server 故障不拖垮启动 | `McpDegradedStartupIntegrationTest`：坏 server 在册不可用，上下文就绪 | ✅ |
| FR-003 | 显式 Java 适配层进入装配链 | 全部 MCP 测试都经 `McpToolCallback` 装饰回调执行；对话链路测试证明工具真进了 ChatClient options | ✅ |
| FR-004 | 策略分级 + 默认 CONFIRM | `McpToolPolicyTest`（4 例：命中/未命中/空或 null 清单/按原始名匹配） | ✅ |
| FR-005 | 复用 ConfirmationService 两阶段机制 | MCP 集成测试走 生成→确认执行→重复确认拒绝；**令牌/过期/取消/并发 CAS 由机制级 `ConfirmationIntegrationTest` 覆盖**（共享机制不重复测） | ✅ |
| FR-006 | 审计记入且来源可区分 | 断言 `operation_record.operation = mcp_test_record`（前缀即来源）、outcome=REQUIRES_CONFIRMATION | ✅ |
| FR-007 | 工具名冲突启动失败 | 冲突用例：刻意重名的内置工具 + 真实 provider → 抛错且信息含冲突项与原因 | ✅ |
| FR-008 | 关闭清理 server 进程 | 自动化：shutdown hook 关闭标记文件硬断言；真机：关闭前 `pgrep -f server-filesystem` 3 → 关闭后 0 | ✅ |
| FR-009 | 超时可配，失败不拖累他人 | `hangingServerTimesOut`（400ms 超时）+ `oneServerFailureDoesNotAffectOthers` | ✅ |
| FR-010 | 只经环境变量注入，默认零启用 | 未配置时不建 registry bean（`PersonalOsApplicationTests`）；全量 154 测试在零启用下同样全绿 | ✅ |
| FR-011 | 敏感值不进仓库/日志/响应 | 启动日志只打印 server 名与工具数，不打印 env；`.env.example` 用 `${VAR}` 引用；仓库 grep `AKID` / `sk-` / `ghp_` 无命中 | ✅ |
| FR-012 | 确认界面展示 server 名与工具名 | 浏览器真机实测：标签「调用外部工具」+ 摘要含 server 名 / 工具名 / 关键参数 | ✅ |
| FR-013 | 策略不因对话内容改变 | 由纯函数签名保证（`resolve(toolName, autoTools)` 不接收任何对话输入）；策略只来自配置 | ✅ |
| FR-014 | 记录 server 侧原始错误（脱敏） | `serverErrorSurfacesReadableMessage`：结果与审计含 server 原始错误文本 | ✅ |

## 成功标准核对（SC）

| # | 标准 | 判定方式 | 状态 |
|---|---|---|---|
| SC-001 | filesystem server 14 工具清单一致 | 真机启动日志逐一比对：14 个，10 AUTO / 4 CONFIRM | ✅ |
| SC-002 | 策略链路自动化全绿 | `./mvnw -B verify`：154 个测试全绿 | ✅ |
| SC-003 | 零回归：既有 115 测试全绿 | 同上（120 → 154，无回归） | ✅ |
| SC-004 | 真机：列目录直行 / 写文件确认 / 退出无残留 | quickstart §3/§4/§6 逐项完成（见 tasks.md T035 记录） | ✅ |
| SC-005 | 三类故障可观测 | 命令不存在 / 挂起超时 / 多 server 隔离均自动化覆盖；**schema 损坏一项实测不可达**（SDK 归一化 schema），改由单测直测自检函数（research R-11） | ✅ |

## 与宪法的对齐核对

| 宪法条 | 本阶段落点 | 结论 |
|---|---|---|
| I. 简单优先 | 一个 starter、一个装饰层、零新表；不做管理界面、不做远程传输 | ✅ |
| II. 边界即安全 | FR-003/004/005/010/011/013 全部服务该条；「默认 CONFIRM」是 fail-closed 的默认值 | ✅ |
| III. 分层与写路径收敛 | `mcp` 独立分包，只依赖 `common`；确认执行走 SPI；写操作全在 Service/handler | ✅ |
| IV. 数据变更可追溯 | 唯一迁移 V8 只扩 CHECK 取值；不改写 V1–V7 | ✅ |
| V. 可验证的交付 | SC-002/003 自动化；SC-001/004/005 已在真机完成并留证 | ✅ |
| 技术栈约束 | 依赖版本取自 Maven Central 稳定版（2.0.1 已核实）；不混 1.x/2.x | ✅ |

## Notes（实测校准与已知限制）

1. **实测推翻的原假设**：规格初稿计划用 MCP SDK 的 in-memory 传输做测试——下载 `mcp-core-2.0.0.jar`
   实测确认不存在该传输，已改为 JVM 内自建 stdio server（research.md R-08）。
2. **工具清单以实测为准**：filesystem server 的 14 工具（10 只读 + 4 变更）来自真实连接，
   不是文档转抄；`edit_file` 的 `dryRun` 参数证明不能按名称前缀推断只读性，故策略必须显式配置。
3. **前端改动被刻意压小**：FR-012 只扩展现有确认卡片展示，不加管理界面（范围决策①）。
   若后续加管理界面，需另开阶段。
4. ~~**审计的对话关联**：待验证 Spring AI 是否向自定义 ToolCallback 传递 ToolContext。~~
   **已解决（2026-10-15）**：`McpChatToolCallingIntegrationTest` 的 CONFIRM 用例按
   `conversationId` 过滤待确认记录并成功取到 → **ToolContext（会话/轮次）确实传到了装饰层**。
   审计记录因此带完整的会话维度，不存在「缺会话维度」这一已知限制。
5. **实测变更的两处口径**（已回改上游文档）：环境变量名单数改复数 `MCP_SERVERS_<NAME>_*`
   （relaxed binding 不做单复数转换，单数静默绑不上）；确认执行的审计归属与内置工具一致
   （拦截记 `operation_record`，执行流转 `confirmation` 表状态）。
