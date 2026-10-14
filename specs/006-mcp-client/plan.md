# Implementation Plan: 阶段 6 — MCP 客户端接入

**Branch**: `006-mcp-client` | **Date**: 2026-10-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-mcp-client/spec.md`

## Summary

新增 `mcp` 模块：经配置接入本地 stdio MCP server，把其工具经**显式 Java 装饰层**并入既有 `ChatClient`
装配链。装饰层做三件事：**确定性前缀命名**（`mcp_<server>_<tool>`）、**策略分级**（AUTO 直行 /
CONFIRM 走确认，默认 CONFIRM）、**审计接入**（既有 `ToolInvocation`）。变更类工具复用阶段 4 泛化后的
确认机制，经新动作 `MCP_TOOL_CALL`（payload 承载 server/工具/参数）桥接动态工具名与静态枚举。
单个 server 故障只降级自身，工具名冲突则启动失败。默认零启用，配置只经 `MCP_*` 环境变量。

## Technical Context

**Language/Version**: Java 21；TypeScript 5.9.3（前端仅小改确认卡片）

**Primary Dependencies**:
- 新增：`org.springframework.ai:spring-ai-starter-mcp-client`（版本随既有 BOM 2.0.1；已核实 Maven Central 存在 2.0.1）
- 传递：`spring-ai-mcp` → MCP Java SDK `mcp-core:2.0.0`（stdio 传输 + 服务端能力，测试自建 server 用）
- 复用：`ToolContributor` 装配点 `AiChatClientProvider`、`ConfirmationService`/`ConfirmationRegistry`/
  `ConfirmationActionHandler`、`ToolInvocation` 审计、Flyway

**Storage**：PostgreSQL 17；**零新表**；仅 `V8__extend_confirmation_action_mcp.sql` 扩展 `confirmation.action` 的 CHECK 约束

**Testing**：
- JUnit 5 单元测试：策略解析、命名与冲突判定、摘要生成
- `@SpringBootTest` + Testcontainers：JVM 内自建 stdio MCP server（SDK `StdioServerTransportProvider` + 管道对接客户端），
  覆盖装配、AUTO 直行、CONFIRM 拦截→确认执行、拒绝/过期、审计、server 故障隔离、关闭清理
- **不依赖 npx 与网络**（用户决策③）；真实 filesystem server 联调列为 quickstart 人工验证项

**Target Platform**：本地单用户自托管

**Project Type**：Web application（Monorepo：backend + frontend）

**Performance Goals**：MCP 工具调用开销 = 既有内置工具调用 + 一次本地管道往返；启动期每个 server 的连接
与列举 ≤ 可配超时（默认 10s），且不阻塞应用就绪（失败即降级）

**Constraints**：
- 未配置任何 server 时，启动行为与既有 115 个测试的结果逐字节一致（SC-003）
- 无 ChatModel 时 MCP 组件仍可初始化（可观测优先）
- server 子进程随应用启停，退出零残留（SC-004）

**Scale/Scope**：个人量级（1–5 个 server、数十个工具）；阶段 6 新增约 25 个文件

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先，拒绝过度设计 | ✅ 通过 | 只做 stdio；不引 RRULE 式外部库之外的任何重型依赖（一个 starter 由既有 BOM 管理）；不做管理界面；不自建协议客户端（用官方 starter 的自动配置） |
| II. 边界即安全 | ✅ 通过 | 本阶段是**加强**而非放松该原则：MCP 工具经显式 Java 装饰层收敛副作用路径；变更类工具默认走人工确认（fail-closed）；沙箱目录由配置显式声明；密钥只经环境变量进 server 子进程，不进日志与响应 |
| III. 分层与写路径收敛 | ✅ 通过 | 新增 `com.personalos.mcp` 独立分包（config/client/tool/confirmation/dto）；确认执行经 `ConfirmationActionHandler` SPI 注册；`AiChatClientProvider` 只多收集一类 Provider，不感知 MCP 细节；前端不加页面，只扩展现有确认卡片的展示 |
| IV. 数据变更可追溯 | ✅ 通过 | 唯一迁移 `V8` 只做一件事（扩展 CHECK 取值）；`ddl-auto=validate`；已应用的 V1–V7 零触碰 |
| V. 可验证的交付 | ✅ 通过 | SC-002 的完整策略链在 JVM 内自建 server 的自动化测试中验证；SC-003 以既有 115 测试零回归为证；SC-004 真机步骤写进 quickstart 并以 `ps` 断言收尾；三类故障均有可观测断言（SC-05） |

**结论**：无违例，Complexity Tracking 仅登记「为什么需要装饰层与通用确认动作」两处说明性条目。

## Project Structure

### Documentation (this feature)

```text
specs/006-mcp-client/
├── spec.md  ├── plan.md  ├── research.md  ├── data-model.md
├── quickstart.md  ├── tasks.md
├── contracts/mcp-integration.md
└── checklists/requirements.md
```

### Source Code (repository root)

```text
backend/src/main/java/com/personalos/
├── mcp/
│   ├── config/McpProperties.java               # @ConfigurationProperties(prefix = "mcp")
│   ├── client/McpClientRegistry.java           # server 生命周期：连接/列举/超时/销毁（SmartLifecycle）
│   ├── client/McpServerStatus.java             # 可用/不可用 + 失败原因（进程内事实，不落库）
│   ├── tool/McpToolCallback.java               # 装饰 ToolCallback：前缀 + 策略 + 审计
│   ├── tool/McpToolCallbackProvider.java       # 把装饰后的回调以 ToolCallbackProvider 形态暴露
│   ├── tool/McpToolPolicy.java                 # AUTO / CONFIRM 枚举
│   ├── tool/McpToolNaming.java                 # 命名与冲突判定（纯函数）
│   ├── confirmation/McpConfirmationHandler.java# ConfirmationActionHandler：确认后真正调用 MCP 工具
│   ├── confirmation/McpConfirmationSummary.java# 服务端生成摘要（纯函数，截断规则见 contracts）
│   └── dto/{McpToolDto, McpServerStatusDto}.java
├── common/confirmation/ConfirmationAction.java # 新增 MCP_TOOL_CALL
├── common/ai/AiChatClientProvider.java         # 并列收集 ToolCallbackProvider，与原 @Tool 路径合并
└── common/ai/audit/（既有 ToolInvocation 记录处）# 增加来源维度 MCP

backend/src/main/resources/db/migration/V8__extend_confirmation_action_mcp.sql
backend/src/test/java/com/personalos/mcp/
├── tool/McpToolNamingTest.java                 # 纯单元：命名/冲突/摘要
├── tool/McpToolPolicyTest.java                 # 纯单元：策略解析顺序与默认值
├── McpServerIntegrationTest.java               # JVM 内自建 stdio server：装配/故障隔离/超时
├── McpToolExecutionIntegrationTest.java        # AUTO 直行 / CONFIRM 全链路 / 审计
└── McpLifecycleIntegrationTest.java            # 关闭清理（无残留子进程）

frontend/src/
├── api/confirmations.ts                        # 复用；DMMCP 来源标记
└── views/ChatView.vue                          # 确认卡片展示 server 名 + 工具名（既有组件扩展）

.env.example                                    # MCP_* 示例（filesystem，限定目录）
```

**Structure Decision**：沿用「单 Maven 模块 + 按包分模块」。`mcp` 成为第八个业务包，
与 `knowledge` 平级；它**不依赖**任何其它业务模块，只依赖 `common`（确认机制 + 审计 + 装配点）——
方向与阶段 3 把 `ToolExecutor` 上移时的判断一致：横切能力放 `common`，业务能力放自己的包。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| 每个 MCP 工具一个 Java 装饰 `ToolCallback` | 前缀、策略拦截、审计三件事都必须在调用前统一发生；没有这一层就只能改 Spring AI 内部行为或放弃其中一两项 | 直接挂 `ToolCallbackProvider`：前缀/策略/审计全丢，宪法原则 II 的「显式 Java 接口」落空 |
| 通用确认动作 `MCP_TOOL_CALL` + payload | MCP 工具名是运行期动态的；静态枚举 + DB CHECK 的既有机制无法为每个工具生成取值 | 每工具一枚举值：每接一个 server 就要发一次迁移；或绕过确认机制：两套令牌语义，用户分不清确认来源 |
| 测试在 JVM 内自建 stdio server | 自动化测试不得依赖网络与外部进程（决策③）；SDK 无 in-memory 传输（实测） | spawn npx：网络依赖、无法注入故障；纯 Mockito 桩：验证不到真实协议链路，恰是风险所在 |
