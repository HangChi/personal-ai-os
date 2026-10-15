# Research: 阶段 6 — MCP 客户端接入

> 编号规则：R-01 起。每条含**决策 / 依据（带实测数字）/ 被否决的方案及理由**。
> 被否决的方案比决策本身更有价值——它们记录了「为什么不是另一种看起来更简单的做法」。

## R-01 依赖选型：标准 starter，不引 webflux 变体

**决策**：引 `org.springframework.ai:spring-ai-starter-mcp-client`，版本由既有 Spring AI BOM 2.0.1 管理。

**依据**：
- 本轮范围只有 **stdio**（用户决策①）。标准 starter 的 stdio 传输基于 JDK HttpClient/进程管道，与现有
  Spring Web MVC 同步栈一致（MCP 客户端类型默认 SYNC，与本项目阻塞式架构匹配）。
- 官方文档对 webflux 变体的推荐语是「生产环境部署的 SSE / Streamable HTTP 场景」——本轮不涉及远程传输，
  引 webflux 变体会把 WebFlux 拖进一个纯 MVC 应用，属于为不存在的需求付成本。
- 官方示例仓库存在明确针对 **Spring Boot 4 / Spring AI 2.0** 的 MCP client starter 示例，兼容性有先例。

**被否决**：
- ~~`spring-ai-starter-mcp-client-webflux`~~：为远程 SSE 生产场景准备，本轮用不到，且引入第二套 Web 栈。
- ~~裸用 MCP Java SDK 自建客户端~~：放弃 Spring AI 的自动配置（连接管理、生命周期、ToolCallback 集成），
  这些恰好是本特性最需要的部分，自建等于把官方已做对的事重做一遍。

## R-02 版本核实：坐标与传递依赖

**决策**：starter 不单独锁版本，跟随 BOM 2.0.1。

**依据**（2026-10-14 实测 Maven Central）：
- `spring-ai-starter-mcp-client` 最新稳定版为 **2.0.1**（另有 2.1.0-M1 里程碑，不采用）；
- 其 POM 依赖 `spring-ai-autoconfigure-mcp-client-httpclient` + `spring-ai-mcp` + `spring-ai-mcp-annotations`；
- `spring-ai-mcp:2.0.1` 依赖 MCP Java SDK `io.modelcontextprotocol.sdk:mcp:2.0.0`（聚合 POM）→ 实际核心
  `mcp-core:2.0.0` + `mcp-json-jackson3`；
- 与宪法「MUST NOT 混用 Spring AI 1.x 与 2.x 的 API 或 artifact 命名」不冲突：本项目全程 2.x。

**被否决**：
- ~~锁 2.1.0-M1~~：里程碑版，违反「稳定版」约束。

## R-03 工具进入装配链的方式：经 ToolCallbackProvider 注入，不动 ToolContributor SPI

**决策**：MCP 工具以 Spring AI 的 `ToolCallbackProvider` 形态进入装配；
`AiChatClientProvider` 增加对该 Provider 的收集（与既有 `ToolContributor` 收集并列），
经装饰层统一加前缀 / 策略 / 审计后挂到 `ChatClient`。

**依据**：
- MCP 工具在 Spring AI 中的原生形态就是 `ToolCallbackProvider`（`spring.ai.mcp.client.toolcallback.enabled=true`
  时自动配置提供），不是 `@Tool` 对象。硬把它塞进 `ToolContributor.toolObjects()`（返回 `List<Object>`）
  需要包一层假 POJO，既不自然也会让 SPI 语义变浑。
- 既有 `ToolContributor`  SPI 服务「业务模块贡献 @Tool 对象」这一场景，阶段 1–4 已稳定；为 MCP 改它的签名
  会波及所有现存贡献者。并列收集对存量零影响。

**被否决**：
- ~~扩展 `ToolContributor` 接口支持 ToolCallback~~：改公共 SPI 波及四个阶段的既有实现，收益为零。
- ~~绕过适配层直接把 MCP Provider 挂上 ChatClient~~：前缀、策略、审计三件事都必须在调用前拦截，
  没有装饰层就只能改 Spring AI 内部行为，不可控。

## R-04 宪法原则 II 的落地：显式适配层 + 策略分级（AUTO / CONFIRM，默认 CONFIRM）

**决策**：每个 MCP 工具经一个 Java 装饰 `ToolCallback` 进入装配；工具按配置分两级——
AUTO（只读，直接执行）/ CONFIRM（变更类，生成待确认后由人确认执行）。**未配置的工具默认 CONFIRM。**

**依据**：
- 宪法原则 II：「模型 MUST NOT 直接访问数据库、文件系统或执行任意代码；所有副作用 MUST 经由显式
  Java 接口暴露的 Agent 工具」。MCP 工具由外部 server 声明，不满足「显式 Java 接口」；
  经我们的装饰层后，每个 MCP 工具都是一个显式 Java 对象的方法，副作用路径重新收敛到本进程内。
- 实测 filesystem server 的 14 个工具中 4 个是变更类。若全部 AUTO，等于把「写文件」的权限直接交给模型，
  与阶段 2 建立的「删除需确认」自相矛盾；若全部 CONFIRM，列目录这类高频只读操作会确认轰炸，
  把用户训练成无脑点确认，反向瓦解确认机制。
- 默认 CONFIRM（fail-closed）：新 server 接入时「漏配」的代价是多弹一次确认，而非一次未授权的写入。
  安全默认值必须让错误方向指向「更安全」。

**被否决**：
- ~~按工具名猜只读/变更（如 `read_` / `list_` 前缀）~~：实测 `edit_file` 带 `dryRun` 参数（dryRun 时是只读），
  `search_files` 读内容——名称与前缀语义不可靠，猜错的代价是不可逆写入。显式配置 + 默认从严优于启发式。
- ~~全部 AUTO，靠 server 自身沙箱~~：server 沙箱限定「能碰哪些路径」，不回答「能不能不经人确认就碰」，
  两者是不同层的问题。
- ~~全部 CONFIRM~~：见上文，确认机制贬值。

## R-05 动态工具名与静态确认枚举的桥接：新增通用动作 MCP_TOOL_CALL

**决策**：`ConfirmationAction` 新增取值 `MCP_TOOL_CALL`，payload 为 `{server, tool, arguments}`；
执行由 `mcp` 模块的 `ConfirmationActionHandler` 完成。配套 Flyway 迁移扩展 `confirmation.action` 的 CHECK 约束。

**依据**：
- 现有机制是「静态枚举 + DB CHECK 约束 + 启动期校验每个 action 都有处理器」（阶段 4 泛化重构的成果）。
  MCP 工具名是**运行时动态**的（取决于接入了哪些 server），为每个工具生成枚举值需要每接一个 server
  就发一次迁移，既脆弱又违背「迁移只追加」的纪律。
- 一个通用动作 + payload 承载具体工具，让机制保持静态、内容保持动态；`ConfirmationService`
  的令牌 / TTL / 单次性 / CAS 语义原样复用，不为 MCP 开特例。
- 摘要仍由服务端依据 payload 生成（含 server、工具、关键参数），不采信模型文本——与既有约定一致。

**被否决**：
- ~~每工具一枚举值 + 动态迁移~~：迁移随 server 数量膨胀，且枚举在编译期，工具在运行期，模型错配。
- ~~MCP 工具绕过确认机制、自带一套确认~~：两套令牌语义、两套 UI 入口，用户分不清「这个确认从哪来的」。
- ~~把工具名塞进现有某个动作（如 DELETE_TASK）的 payload~~：类型撒谎，审计与摘要全部失真。

## R-06 命名冲突：前缀 + 启动期校验，冲突即启动失败

**决策**：MCP 工具统一加确定性前缀（形如 `mcp_<server>_<tool>`，最终形态在 plan 定）；
启动期校验「MCP 工具名 × 内置工具名」无交集，有交集则**应用启动失败**并列出冲突项。

**依据**：
- 内置 14 个工具与 filesystem server 的 14 个工具将共存于同一个 `ChatClient`；模型按名调用，
  同名即歧义——调用落到错误的工具上，症状是「结果莫名其妙」，属于最难排查的一类。
- 「启动失败」对齐项目既有模式：`ConfirmationRegistry` 缺处理器即启动失败、
  `VectorDimensionValidator` 维度不符即启动失败。**正确性问题不允许降级运行。**
- 前缀同时解决跨 server 同名工具（两个 server 都有 `read_file`）的区分问题。

**被否决**：
- ~~静默去重（后注册的赢）~~：行为取决于启动顺序，不可复现，难排查。
- ~~运行时解析冲突~~：把编译期/启动期能暴露的问题推给用户对话时刻，代价最大化。

## R-07 故障隔离语义：server 故障 = 降级，命名冲突 = 启动失败

**决策**：两类故障刻意区别对待——
单个 server 连不上 / 启动失败 / 超时：标记不可用 + 日志告警，应用照常启动；
工具名冲突：应用启动失败。

**依据**：
- server 故障是**可用性**问题：没配好一个可选的 MCP server，不该让用户的整个 OS 起不来
  （既有承诺：无模型也能启动、无知识库也能启动）。
- 命名冲突是**正确性**问题：放任运行等于允许模型调用到错误的工具。本项目的一贯立场是
  「静默失效比响亮失败更糟」（阶段 2 工具静默不执行的排查代价即是明证）。

**被否决**：
- ~~server 故障也启动失败~~：把可选功能做成硬依赖，违背「无 X 也能启动」系列承诺。
- ~~冲突也降级（跳过冲突工具）~~：少一个工具是静默的能力缺损，用户不知道助手少了什么。

## R-08 测试传输选型：SDK 无 in-memory，改为 JVM 内自建 stdio server

**决策**：自动化测试的 MCP server 在测试 JVM 内自建——用 SDK 的 `StdioServerTransportProvider`
起一个 stdio server（进程内线程），客户端经管道对接；真实 server（npx）联调列为 quickstart 人工验证项。

**依据**（2026-10-14 实测）：
- 下载 `mcp-core-2.0.0.jar` 检查传输实现：仅有 `StdioClientTransport` /
  `HttpServletSseServerTransportProvider` / `HttpServletStreamableServerTransportProvider` 等，
  **不存在 in-memory 传输**——原规格假设被实测推翻（已记录在 spec.md Notes）。
- 「自动化测试不依赖网络与外部进程」是用户决策③。JVM 内自建 stdio server 满足该约束：
  测试自己同时是 server 与客户端，工具清单、参数、返回、故障注入全部可控
  （要模拟「server 挂起」「schema 损坏」只需改测试 server 几行代码）。

**被否决**：
- ~~in-memory 传输~~：不存在（实测）。
- ~~测试里 spawn `npx -y @modelcontextprotocol/server-*`~~：依赖网络与 npm 缓存，
  CI 与离线环境不可复现；且无法注入故障。
- ~~纯 Mockito 桩 ToolCallbackProvider~~：验证不到真实 MCP 协议链路
  （initialize / tools.list / tools.call 的编解码与超时行为正是风险所在），只配做单元测试的补充。

## R-09 配置面设计：MCP_* 环境变量，零启用默认

**决策**：配置前缀 `MCP_*`（对齐既有 `AI_*` / `SERVER_PORT` 风格），Spring  relaxed binding 绑定到
`com.personalos.mcp.McpProperties`；`MCP_ENABLED` 默认 false，`MCP_SERVERS` 声明启用的 server 名列表；
server 级配置经 `MCP_SERVER_<NAME>_COMMAND / _ARGS / _ENV_* / _AUTO_TOOLS / _TIMEOUT_MS` 下发。

**依据**：
- 宪法原则 II：仓库中 MUST NOT 出现任何密钥。MCP server 常需要 API key（如 GitHub server），
  只能经环境变量传入 server 子进程——配置模型必须有 `env` 直通通道。
- 默认零启用：本特性是可选能力，「装了 starter 但没配置」与「配置前」行为必须一致（SC-003）。
- `.env.example` 给 filesystem 示例且**限定目录**（如 `./mcp-sandbox`），把安全默认值写进模板。

**被否决**：
- ~~默认启用 filesystem server~~：把「开放文件系统」做成默认值，安全默认值方向错误；
  且多数用户并没有 `./mcp-sandbox` 这个目录，默认启用反而报错。
- ~~配置文件（application.yml 片段）~~：密钥会随配置文件走，违背只经环境变量注入的约定。

## R-10 stdio server 生命周期：随应用启停，退出必清理

**决策**：server 子进程由 Spring 容器生命周期管理（`SmartLifecycle` 或 `DisposableBean`），
应用关闭时按 server 逐一销毁；有测试断言「关闭后无残留进程」。

**依据**：
- stdio server 是应用拉起的子进程；Spring AI 的 MCP 客户端自带连接关闭逻辑，但「关闭是否彻底」
  必须由我们自己的测试证明（SC-004 要求 `ps` 无残留）——不能假设框架一定做对。
- 孤儿进程会持续占用用户机器的 CPU / 端口，且用户未必知道它是谁拉的。

**被否决**：
- ~~detached 常驻 server~~：生命周期脱离应用，重启应用后连接状态与进程状态脱钩，难排查。
- ~~不主动清理，依赖 server 检测 stdin 断开自退~~：把正确性寄托在外部进程的礼貌上；
  实测中 server 的行为不可假设，必须主动 kill。

## R-11 schema 自检的定位：SDK 会归一化 schema，自检退化为防御性代码

**决策**：保留工具的 schema 自检（`type: object` 且 `properties` 为对象），
但明确定位为**防御性**代码，并直接单测（`McpToolCallbackProviderTest`），不再寄望集成测试覆盖。

**依据（2026-10-15 实测）**：
- Spring AI 的 `SyncMcpToolCallbackProvider` 在转换 MCP 工具时会**归一化 schema**：
  helper 里刻意声明缺 `type` 的 schema，经转换后到达装配层已是
  `{"properties":{...},"type":"object"}`（打印实测确认）。
- 也就是说「server 声明坏 schema」这条路径在当前依赖版本下**不可达**，
  `type != object` 与 `properties 非对象` 两个分支从集成测试走不到。
- 反过来，`ToolDefinition.Builder` 又拒绝 null/空 schema（实测报
  `inputSchema cannot be null or empty`）——所以连「空白 schema」都必须用接口桩构造才测得到。

**为什么仍然保留**：成本是一次字符串解析，收益是把「坏工具暴露给模型」这类最难排查的问题
挡在装配期；框架行为变化时（例如某天不再归一化）它仍是第一道闸。
**代价与对策**：防御性代码的最坏结局是「没人知道它还在不在工作」——故改为直接单测四个分支
（合法 / 非 object / 缺 properties / 空白与损坏 JSON）。

**教训（可复用）**：
- 「构造一个坏输入」在框架层可能已被拦下或修正，写测试前先确认**输入真的能到达被测代码**。
- 集成测试走不到的防御分支，必须有直接单测，否则等于没测。
- 断言要写**实测行为**，不是设计意图——本阶段三处断言都是按实测改写的（schema 归一化、
  审计归属、工具计数口径）。
