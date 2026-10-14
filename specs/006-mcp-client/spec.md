# Feature Specification: 阶段 6 — MCP 客户端接入

**Feature Branch**: `006-mcp-client`

**Created**: 2026-10-14

**Status**: Draft

**Input**: 用户需求：「先开发更多的功能，暂不发布」，选定方向为 README 七项目标中唯一未做的
「通过 MCP 扩展外部工具」。开工前已确认四项边界决策：
① 范围 = **本地 stdio 最小闭环**（不含远程 server、不含前端管理界面）；② 依赖 = 引标准 starter
`spring-ai-starter-mcp-client`（由 Spring AI BOM 2.0.1 管理）；③ 验证 = **自动化测试不依赖网络** +
真实 server 联调列为人工验证项；④ 默认 = **零启用**，`.env.example` 给配置示例。

开工前实测校准（2026-10-14，写本规格的依据）：

| 校准项 | 实测结果 |
|---|---|
| starter 坐标 | `spring-ai-starter-mcp-client:2.0.1` 在 Maven Central 存在；传递依赖 `spring-ai-mcp` → MCP Java SDK `io.modelcontextprotocol.sdk:mcp-core:2.0.0` |
| SDK 传输能力 | 仅 stdio / HTTP SSE / Streamable，**无 in-memory 传输** → 测试方案须调整为 JVM 内自建 stdio server |
| 真实工具清单 | reference filesystem server（npx）实际暴露 **14 个工具：10 只读 + 4 变更类**（`write_file` / `edit_file` / `create_directory` / `move_file`） |
| server 沙箱 | filesystem server 的允许目录由**启动参数**决定；客户端不支持 Roots 时 server 打印降级提示并沿用参数 |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 配置并接入本地 MCP server（Priority: P1）

作为用户，我希望在 `.env` 里声明一个 MCP server（命令 + 参数 + 环境变量），重启后助手就多了一批工具，
像给操作系统装驱动一样简单。

**Why this priority**：这是整个特性的入口。没有它，其余用户故事都不存在。

**Independent Test**：配置一个 server 后启动应用，能在日志或接口中看到该 server 暴露的工具清单，
且清单与 server 实际暴露的一致。

**Acceptance Scenarios**：

1. **Given** 配置了一个 stdio server，**When** 应用启动，**Then** 连接成功，该 server 的工具全部进入装配清单（数量与 server 实际暴露一致）。
2. **Given** 配置的 server 命令不存在或启动即退出，**When** 应用启动，**Then** 应用**不因此崩溃**；该 server 被标记为不可用并有明确日志，其余功能（含其它 server）正常。
3. **Given** 未配置任何 server（默认状态），**When** 应用启动，**Then** 行为与本阶段之前**完全一致**：零 MCP 工具、既有测试全绿、无新增日志噪音。
4. **Given** 某 MCP 工具名与内置工具同名，**When** 应用启动，**Then** **启动失败**并列出全部冲突项（名称、来源 server、冲突的内置工具）。

---

### User Story 2 - 只读工具直接放行（Priority: P1）

作为用户，助手查文件、列目录这类只读操作应该直接执行；对只读操作弹确认是噪音，
会把用户训练成「无脑点确认」，反而稀释了确认机制的严肃性。

**Why this priority**：确认机制的价值在于「每次弹出都意味着风险」。策略分级失当，
要么危险操作被放行（安全事故），要么用户被确认轰炸（确认机制贬值）。两者都不可接受。

**Independent Test**：对一个策略为 AUTO 的 MCP 工具，对话中调用后直接执行并返回结果，
不产生任何待确认记录。

**Acceptance Scenarios**：

1. **Given** 某 MCP 工具策略为 AUTO，**When** 助手调用它，**Then** 直接执行，结果进入对话，**不产生**待确认记录。
2. **Given** 某 MCP 工具未显式配置策略，**When** 助手调用它，**Then** 按**变更类（CONFIRM）**处理（fail-closed，默认从严）。
3. **Given** 用户在配置中把某工具显式标为 AUTO，**When** 助手调用它，**Then** 直接执行——配置即意图，服务端不猜。

---

### User Story 3 - 变更类工具走人工确认（Priority: P1）

作为用户，MCP server 里能写文件、移动文件的工具，必须和「删除任务」一样经我确认——
外部工具不是法外之地。我把目录交给 server，不等于把写权限交给模型。

**Why this priority**：宪法原则 II（NON-NEGOTIABLE）。MCP 把副作用面从「我的数据库」扩展到
「外部系统」，确认机制必须覆盖，否则原则 II 出现缺口。

**Independent Test**：对话中让助手调用一个 CONFIRM 策略的 MCP 工具：生成待确认项且**未执行**；
用户确认后真正执行且落库可查；错误/过期/已消费的令牌一律拒绝。

**Acceptance Scenarios**：

1. **Given** 某 MCP 工具策略为 CONFIRM，**When** 助手调用它，**Then** **不执行**，生成待确认项；摘要由服务端依据参数生成，含 server 名、工具名与关键参数。
2. **Given** 存在 MCP 待确认项，**When** 用户确认，**Then** 真正调用该 MCP 工具，执行结果与确认记录关联可查。
3. **Given** 存在 MCP 待确认项，**When** 使用错误、过期或已消费的令牌确认，**Then** 拒绝，且**不执行**工具（复用 `ConfirmationService` 既有语义）。
4. **Given** 待确认项已过期或被拒绝，**When** 用户再次尝试，**Then** 得到明确的状态提示，无悬挂执行、无静默重试。
5. **Given** 用户在对话中要求「直接写，不用确认」，**When** 模型试图绕过，**Then** 服务端策略不因对话内容而改变——策略只来自配置（结构性防线，不靠保密）。

---

### User Story 4 - 工具调用可观测（Priority: P2）

作为用户，我希望看到助手调用了哪些 MCP 工具、成功还是失败、耗时多少——和内置工具同一个标准。
看不见的调用等于没有的调用。

**Why this priority**：阶段 2 已建立 `ToolInvocation` 审计。MCP 工具不接入审计，
就等于在审计面挖了一块盲区，而 MCP 工具的副作用恰好落在我自己的文件系统上。

**Independent Test**：各调用一次 MCP 工具（成功与失败各一），审计记录中出现对应条目，
字段与内置工具一致且能区分来源。

**Acceptance Scenarios**：

1. **Given** MCP 工具执行完成（无论成功失败），**When** 查看工具调用记录，**Then** 有一条记录，含 server 名、工具名、耗时与结果状态。
2. **Given** server 已不可用，**When** 助手仍尝试调用其工具，**Then** 返回明确错误（而非静默失败或空回复），该错误同样进入审计。
3. **Given** 内置工具与 MCP 工具的调用，**When** 查看审计列表，**Then** 能按来源区分（内置 / MCP + server 名）。

---

### User Story 5 - 在对话中自然使用（Priority: P2）

作为用户，我直接说「看看 sandbox 目录下有什么」，助手就调用 MCP 工具如实回答；
我说「把总结写进 notes.md」，它弹确认而不是偷偷写。

**Why this priority**：与阶段 1–4 一致的自然语言入口，是特性的最终价值出口；但 REST 与管理面
已能满足核心需求，故列 P2。

**Independent Test**：配置 filesystem server（限定目录）后，对话中询问目录内容，
回复基于真实工具结果；要求写文件时走确认流程。

**Acceptance Scenarios**：

1. **Given** 已接入 filesystem server 且限定了目录，**When** 对话中问「某目录下有什么」，**Then** 助手调用目录列举类工具，回复与真实目录一致。
2. **Given** 对话中要求写文件，**When** 助手调用变更类工具，**Then** 走确认流程而非直接写（对齐 User Story 3）。
3. **Given** 模型引用了不存在的工具或参数不符合 server 声明的 schema，**When** 调用发生，**Then** 返回可读错误，不崩溃、不静默。

---

### Edge Cases

- **MCP server 的沙箱是 server 自己的事**：filesystem server 只访问启动参数里声明的目录。应用层配置 MUST 显式声明这些参数，**不得默认开放 home 目录或整个磁盘**——「限定目录」是本特性安全模型的第一道闸。
- **stdio server 随应用生命周期**：server 进程由应用拉起；应用退出时 MUST 清理（不得留孤儿进程）。这条必须有测试守着。
- **server 启动慢或挂起**：连接 MUST 有超时；超时按不可用处理，**应用启动不得无限等待**。
- **工具 schema 异常**：server 声明的工具缺 required 或类型明显损坏时，该工具按不可用处理并告警，不静默暴露坏工具给模型。
- **跨 server 同名工具**：两个 server 暴露同名工具时，靠「server 名前缀」区分，MUST NOT 静默二选一（启动期冲突校验覆盖）。
- **无模型时**：未配置 ChatModel 时，MCP 客户端仍可启动并列举工具（可观测），只是没有对话能使用它——保持「无模型可启动」的既有承诺不被破坏。
- **参数里的敏感值**：server 需要的 API key 经环境变量传入 server 进程，MUST NOT 写入日志、仓库或错误响应。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持经配置接入**一个或多个**本地 stdio MCP server；每项配置含名称、启动命令、参数、环境变量。
- **FR-002**: 系统 MUST 在启动时连接已配置的 server 并列举其工具；单个 server 失败 MUST NOT 导致应用启动失败（故障隔离到 server 粒度）。
- **FR-003**: MCP 工具 MUST 经一个显式 Java 适配层进入装配链，与内置工具走同一条 `ChatClient` 装配路径（宪法原则 II：副作用必经显式 Java 接口暴露）。
- **FR-004**: 每个 MCP 工具 MUST 有策略分级：AUTO（只读，直接执行）/ CONFIRM（变更类，人工确认后执行）；**未显式配置的策略默认 CONFIRM**。
- **FR-005**: CONFIRM 策略的 MCP 工具 MUST 复用现有 `ConfirmationService` 两阶段机制（令牌 / TTL / 单次性 / CAS 占位），摘要 MUST 由服务端依据参数生成，MUST NOT 采信模型文本。
- **FR-006**: MCP 工具调用 MUST 记入现有工具调用审计，且记录 MUST 能区分来源（内置 / MCP + server 名）。
- **FR-007**: 系统 MUST 保证 MCP 工具名与内置工具不冲突；冲突时 MUST 启动失败并列出冲突项（对齐 `ConfirmationRegistry` 的启动期校验模式）。
- **FR-008**: 应用关闭时 MUST 清理其拉起的 stdio server 进程，MUST NOT 留孤儿进程。
- **FR-009**: 连接与调用 MUST 有可配置超时；超时或失败的 server 标记为不可用，其工具不进入装配，其余 server 不受影响。
- **FR-010**: 配置 MUST 只经环境变量注入（`MCP_*` 前缀），仓库中只提交 `.env.example` 模板；**默认不启用任何 server**。
- **FR-011**: server 需要的敏感信息（API key 等）MUST 经环境变量传给 server 进程，MUST NOT 进入仓库、日志或错误响应。
- **FR-012**: 现有确认界面 MUST 能正确展示 MCP 待确认项（摘要含 server 名、工具名、关键参数）；本轮 MUST NOT 新增前端管理界面。
- **FR-013**: 策略配置 MUST 只来自服务端配置，MUST NOT 因对话内容而改变（模型说「不用确认」不构成豁免）。
- **FR-014**: MCP 工具的调用 MUST 记录 server 侧的原始错误信息（脱敏后），便于区分「server 挂了」与「参数不对」。

### Key Entities *(include if feature involves data)*

- **McpServerConfig**（配置实体，非持久化）：server 名、启动命令、参数列表、环境变量、工具策略覆盖（AUTO 清单）、超时。
- **confirmation.action 新增取值 `MCP_TOOL_CALL`**：payload 承载 `{server, tool, arguments}`；配套 Flyway 迁移扩展 CHECK 约束。
- **既有表零结构变更**：除上述 CHECK 约束扩展外，不新增表、不改既有列。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 配置 reference filesystem server（限定目录）后启动，工具清单数量与 server 实际暴露一致（实测基准：**14 个 = 10 只读 + 4 变更类**）。
- **SC-002**: 完整策略链路（AUTO 直行 / CONFIRM 生成待确认 → 确认执行 → 拒绝与过期被拒）在**不依赖网络与外部进程**的自动化测试中全绿。
- **SC-003**: 未配置任何 server 时，`./mvnw -B verify` 与启动行为与本阶段之前完全一致——**既有 115 个测试零回归**。
- **SC-004**: 真机人工验证（quickstart 留步骤）：对话中列目录直接执行、写文件弹确认；应用退出后 `ps` 无残留 server 进程。
- **SC-005**: 三类故障（工具名冲突 / server 启动失败 / 连接超时）均有明确可观测表现：冲突 = 启动失败带清单；后两者 = 标记不可用 + 日志告警，应用整体保持可用。

## Assumptions

- 传输只做 stdio；远程 SSE / Streamable HTTP 不在本轮（用户范围决策）。
- 自动化测试的 MCP server 在测试 JVM 内自建（SDK stdio server + 管道对接客户端），不依赖 npx 与网络——SDK 2.0.0 无 in-memory 传输，此结论已实测（见 research.md R-08）。
- 依赖 `spring-ai-starter-mcp-client` 由既有 Spring AI BOM 2.0.1 管理，不单独锁版本（已核实 Maven Central 存在 2.0.1）。
- MCP 工具的确认复用现有机制，经一个通用动作取值 `MCP_TOOL_CALL` 桥接动态工具名与静态枚举，不为每个工具生成枚举值。
- 前端不新增 MCP 管理界面；配置经 `.env`，界面只消费既有确认卡片。
- 「限定目录」等沙箱参数由配置显式声明；本特性不代 server 做路径校验（server 自己的安全模型），但配置模板 MUST 给出安全示例。

## 明确不含（本轮）

| 项 | 依据 |
|---|---|
| 远程 MCP server（SSE / Streamable HTTP） | 用户范围决策①：本轮只做本地 stdio 最小闭环 |
| MCP Resources / Prompts / Sampling 原语 | 本轮只接 Tools；其余原语待 Tools 链路稳定后评估 |
| 前端 MCP 管理界面（启停 / 策略调整） | 用户范围决策①；配置走 `.env`，前端只消费确认卡片 |
| 按工具的细粒度权限模型（用户 / 角色） | 第一版仅本地单用户（宪法原则 II）；认证方案本身是未决项 |
| MCP server 的 OAuth 等远程认证 | 无远程 server 即无此需求 |

## Notes（实测校准记录）

1. 原计划「in-memory 传输做测试」被实测推翻：MCP Java SDK 2.0.0 无 in-memory 传输，测试 server 改为 JVM 内自建（详见 research.md R-08）。
2. reference filesystem server 的 14 个工具清单来自本次真实连接（非文档转抄）；`edit_file` 带 `dryRun` 参数说明「只读/变更」不能简单按工具名前缀推断，故策略采用**显式配置 + 默认 CONFIRM**，不做名称猜测。
3. server 降级行为（客户端不支持 Roots 时沿用参数目录）在实测输出中出现，已写入 Edge Cases 的沙箱条目。
