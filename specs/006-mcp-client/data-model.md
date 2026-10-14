# Data Model: 阶段 6 — MCP 客户端接入

> 只写本特性**新增或写入**的数据结构；并明确声明「不做哪些变更」。

## 1. 持久化结构变更（仅一处）

### 1.1 `confirmation.action` CHECK 约束扩展

**迁移**：`V8__extend_confirmation_action_mcp.sql`（Flyway，只追加不改写）。

```sql
ALTER TABLE confirmation DROP CONSTRAINT confirmation_action_check;
ALTER TABLE confirmation ADD CONSTRAINT confirmation_action_check
    CHECK (action IN ('DELETE_TASK', 'SCHEDULE_TASK', 'SET_PRIORITY',
                      'DELETE_CALENDAR_EVENT', 'MCP_TOOL_CALL'));
```

**纪律提醒**（阶段 4 踩过）：已应用的迁移**只追加，绝不改写**。本迁移是全新文件，不触碰 V1–V7。
约束名沿用 V5/V6 确立的 `confirmation_action_check`（V6 已把 `task_confirmation` 时代的主键改名，
本次只动 CHECK 取值，不动主键与索引）。

**新取值语义**：

| 字段 | 值 |
|---|---|
| `action` | `MCP_TOOL_CALL` |
| `summary` | 服务端生成，形如 `MCP 工具调用确认：server=filesystem，工具=write_file，路径=./notes.md` |
| `payload_json` | `{"server":"filesystem","tool":"write_file","arguments":{...}}` |

**为什么 payload 是自由 JSON 而不是新列**：不同 MCP 工具的参数结构完全不同（filesystem 是
`{path, content}`，GitHub 是 `{owner, repo, ...}`），用列承载会逼出一个「万能列」或一张稀疏表；
`confirmation.payload_json` 本就来者不拒（阶段 4 泛化重构的成果），动态内容放这里。

## 2. 配置结构（非持久化）

### 2.1 `McpProperties`（`@ConfigurationProperties(prefix = "mcp")`）

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | boolean | `false` | 总开关。false 时整个特性不初始化，行为与阶段 5 之前一致 |
| `servers` | `Map<String, ServerConfig>` | 空 | server 名 → 配置 |
| `servers.<name>.command` | String | — | 启动命令（如 `npx`） |
| `servers.<name>.args` | `List<String>` | 空 | 参数（如 `-y,@modelcontextprotocol/server-filesystem,./mcp-sandbox`） |
| `servers.<name>.env` | `Map<String, String>` | 空 | 透传给 server 子进程的环境变量（密钥只从这里进） |
| `servers.<name>.autoTools` | `Set<String>` | 空 | 策略为 AUTO 的工具名清单；**未列出的工具默认 CONFIRM** |
| `servers.<name>.connectTimeout` | Duration | `10s` | 连接/列举工具的超时 |

**环境变量映射**（relaxed binding，与既有 `AI_*` 风格一致）：

```
MCP_ENABLED=true
MCP_SERVERS_FILESYSTEM_COMMAND=npx
MCP_SERVERS_FILESYSTEM_ARGS=-y,@modelcontextprotocol/server-filesystem,./mcp-sandbox
MCP_SERVERS_FILESYSTEM_AUTO_TOOLS=read_file,read_text_file,list_directory,directory_tree,search_files,get_file_info
MCP_SERVERS_GITHUB_ENV_GITHUB_TOKEN=${GITHUB_TOKEN}
```

> **2026-10-15 修订（实现期实测偏差）**：原设计写的是 `MCP_SERVER_<NAME>_*`（单数），
> 且有一个 `MCP_SERVERS=filesystem` 列表属性。实测两点修正：
> ① relaxed binding **不做单复数转换**——`MCP_SERVER_FILESYSTEM_COMMAND` 归一到
> `mcp.server.filesystem.command`，与字段名 `servers` 对不上，**静默绑不上**
> （servers 永远是空 map，不报错）。已改为复数 `MCP_SERVERS_<NAME>_*` 与字段名对齐，
> 并由 `McpDegradedStartupIntegrationTest` 的断言守住；
> ② `MCP_SERVERS` 列表属性已删除——`servers` 映射的键就是 server 清单，
> 少一个属性就少一种「声明了却没配置」的半成品状态。
> 教训：环境变量名必须与配置字段名逐字对应（大小写/分隔符宽松，单复数不宽松）。

### 2.2 工具策略的运行时表示

`McpToolPolicy`（枚举，进程内）：`AUTO` / `CONFIRM`。
解析顺序（**顺序本身是契约**，详见 contracts）：

1. `servers.<name>.autoTools` 命中 → AUTO；
2. 否则 → CONFIRM（默认）。

**不做**：不按工具名启发式猜测；不提供「全 server 一键 AUTO」的开关（逼用户逐个审视只读工具）。

## 3. 明确不做哪些变更

| 不做 | 依据 |
|---|---|
| 不新增 MCP 专属表（工具清单 / server 状态 / 调用记录都不落库） | 工具清单启动时从 server 拉取，**配置即真相**；调用审计走既有 `ToolInvocation`；server 状态是进程内事实，重启即重新发现，落库只会产生过期数据 |
| 不改 `chat_session` / `chat_message` / `task` / `knowledge_*` / `calendar_event` / `reminder` | 本特性不触碰既有业务数据 |
| 不存 MCP 工具的参数 schema | schema 由 server 在 `tools.list` 时提供，缓存到内存即可；落库会在 server 升级后变成谎言 |
| 不为每个 MCP 工具建 `ConfirmationAction` 枚举值 | 见 research.md R-05：动态工具名与静态枚举之间用通用动作桥接 |

## 4. 与既有结构的关系

```
confirmation（既有表）
├── action = 'DELETE_TASK' | 'SCHEDULE_TASK' | 'SET_PRIORITY' | 'DELETE_CALENDAR_EVENT'   ← 既有
└── action = 'MCP_TOOL_CALL'                                                                ← 本特性新增
        payload_json = {"server": ..., "tool": ..., "arguments": {...}}
        handler      = mcp 模块的 McpConfirmationHandler（ConfirmationActionHandler SPI）
```

`ConfirmationRegistry` 的启动期校验自动覆盖新动作：加了枚举没配处理器 → 启动失败（既有机制，
无需新代码，只需遵守）。
