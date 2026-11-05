# MCP 指南

Personal AI OS 同时支持 MCP Client 和 MCP Server。两个方向互不依赖，默认都关闭。

本地 Maven / JAR 启动时会自动读取项目根目录 `.env`。外部 stdio 服务在后端所在机器上运行：
filesystem 示例需要 Node.js / npx，fetch 示例需要 uv / uvx；这些命令必须在后端进程的 PATH 中。

Java 和 Go 后端也提供内置联网搜索。在根目录 `.env` 或运行环境中设置
`BRAVE_SEARCH_API_KEY` 并重启后端，然后在聊天中说「请联网查……」。应用把公开搜索关键词发给
Brave Search，回答正文中的编号可预览网页摘录、标题和域名，并打开原网页。网页与知识库来源在同一轮共用一套编号。
以「请联网查……」或 `/web 关键词` 开头可直接发起搜索。若知识库证据需要核对，只有拿到可引用网页后才继续回答公开事实。
自动执行的 MCP `fetch` 可将匹配请求地址的网页摘录加入引用。支持的网页搜索工具也可通过结构化 `results` 返回
`url`、`title` 和 `content`、`snippet` 或 `description`。

知识库问答可通过 `APP_KNOWLEDGE_RAG_CITATION_AUDIT_ENABLED=true` 启用模型逐条核对。
它额外发起一次有时限的模型调用，并校验返回的引文确实存在于对应证据中。

## 1. 作为 MCP Client

这个模式让 Personal AI OS 的助手调用外部 MCP Server。配置只从环境变量读取，避免把命令、参数和密钥写进数据库。

```bash
MCP_ENABLED=true
MCP_SERVERS_FILESYSTEM_COMMAND=npx
MCP_SERVERS_FILESYSTEM_ARGS=-y,@modelcontextprotocol/server-filesystem,./mcp-sandbox
MCP_SERVERS_FILESYSTEM_AUTO_TOOLS=read_file,list_directory,directory_tree
```

也可以接入无需密钥的网页抓取 server：

```bash
MCP_SERVERS_FETCH_COMMAND=uvx
MCP_SERVERS_FETCH_ARGS=mcp-server-fetch
MCP_SERVERS_FETCH_AUTO_TOOLS=fetch
```

`MCP_SERVERS_<NAME>_ARGS` 的最后一个目录是 filesystem server 的访问边界，应使用专用目录，不能直接开放 home 目录或整个磁盘。
目录必须已存在；相对路径以实际后端进程的工作目录解析。为兼容从 `backend/` 或项目根目录启动，建议填写专用目录的绝对路径。

策略规则如下：

- `AUTO_TOOLS` 中的工具按只读操作处理，直接执行；
- 未列入 `AUTO_TOOLS` 的工具默认需要人工确认；
- 工具名会加上 `mcp_<server>_` 前缀，并进入统一的调用审计；
- server 连接失败只会让该 server 标记为不可用，不会阻止应用启动；工具名冲突会阻止启动。

启动后进入前端「设置 → MCP」（`/settings?section=mcp`），可以查看外部 server 的连接状态、工具数、只读工具和失败原因。
页面上的“重连”只重启指定 server 的连接，不会重启整个后端；新增、删除或修改 server 仍通过
`.env` 完成，修改后重启后端才会生效。

## 2. 作为 MCP Server

这个模式让 Cursor、Claude Desktop 或其他 MCP 客户端查询 Personal AI OS 的个人数据。第一版只开放只读工具：

| 工具 | 用途 |
|---|---|
| `personal_list_tasks` | 按状态和关键词分页查询任务 |
| `personal_search_knowledge` | 检索知识库片段 |
| `personal_list_calendar` | 按 ISO-8601 时间区间查询日程 |
| `personal_list_memories` | 读取长期记忆 |

对外端点只发布以上 4 个工具，不会转发 filesystem、fetch 等外部 MCP 工具。
协议中这 4 个工具均标记为只读、非破坏性、幂等；助手在对话中仍可使用已配置的外部工具。

### 启用

在根目录 `.env` 中设置：

```bash
PERSONAL_MCP_SERVER_ENABLED=true
```

然后重启后端。开发启动：

```bash
cd backend
./mvnw spring-boot:run
```

Docker Compose：

```bash
docker compose --profile app up -d --build
```

端点为：

```text
http://127.0.0.1:8080/mcp
```

它是协议端点，不是浏览器管理页面。浏览器直接打开可能看到 `401` 或其他协议响应，这是正常的；应在 MCP 客户端中配置该地址并发起 MCP 会话。

### 认证与安全

MCP Server 复用后端的认证配置。启用 `AUTH_ENABLED=true` 时，客户端必须使用已认证会话；生产部署还必须启用 Secure Cookie、CSRF 和 HTTPS 反向代理。不要把本地的未认证端点直接映射到公网。

写入工具暂未开放。这样外部 AI 不能绕过 Personal AI OS 已有的人工确认机制修改任务、日程或记忆。

### 常见误区

- **看不到 MCP 菜单**：MCP 管理集中在前端「设置 → MCP」；页面会展示外部 MCP Client 状态、重连操作和本系统对外的 4 个工具目录。
- **启用后仍然连接不上**：确认后端已重启，且 `PERSONAL_MCP_SERVER_ENABLED=true` 来自实际运行进程的环境变量。
- **浏览器打开返回 401**：这是认证拦截，不代表 MCP 未注册；先登录或在 MCP 客户端配置认证会话。

## 3. 如何新增

### 新增外部连接

当前设置页支持查看状态、查看自动执行工具和重连，尚不支持在页面新增、编辑或删除连接。
外部接入目前只支持 **stdio**：后端启动一个本地进程并通过标准输入输出连接它，不能直接填入远程 HTTP MCP 地址。

以接入自己编写的 Python MCP Server 为例，在项目根目录 `.env` 追加：

```bash
MCP_ENABLED=true
MCP_SERVERS_MYTOOLS_COMMAND=python3
MCP_SERVERS_MYTOOLS_ARGS=/absolute/path/my_mcp_server.py
MCP_SERVERS_MYTOOLS_AUTO_TOOLS=
```

示例中的脚本需要先实现 MCP stdio 服务，路径应替换成实际文件路径；`COMMAND` 和 `ARGS` 以该服务的启动说明为准。
每个服务使用不同的名称，例如 `MYTOOLS`、`FILESYSTEM`。需要传递服务专用环境变量时使用
`MCP_SERVERS_MYTOOLS_ENV_<变量名>`，连接超时可设置 `MCP_SERVERS_MYTOOLS_CONNECT_TIMEOUT=10s`。
后端运行在容器中时，启动命令、依赖和脚本也必须在容器内可用。

保存后重启后端，在「设置 → MCP」检查新连接。页面上的“刷新状态”和“重连”都不会重新读取 `.env`。
默认将 `AUTO_TOOLS` 留空，全部工具需要确认；确认哪些工具适合直接执行后，再填写它们的原始工具名并重启。

### 扩展本系统服务

“本系统服务”代表 Personal AI OS 自己的一个 `/mcp` 端点，四个目录项是它提供的工具。
如果要加入另一个独立的 MCP Server，即使它也是自己开发的，也按上面的“外部连接”接入。
如果要让其他 AI 查询本系统更多业务能力，则在现有端点中扩展工具：

1. 在 [`PersonalMcpTools.java`](../backend/src/main/java/com/personalos/mcp/server/PersonalMcpTools.java)
   中新增带 `@McpTool` 的公开方法，用 `@McpToolParam` 描述参数，复用对应的业务 Service。
2. 在 [`McpStatusService.java`](../backend/src/main/java/com/personalos/mcp/status/McpStatusService.java)
   的 `personalServer()` 中同步维护工具名称、描述和只读属性，设置页才会显示新工具。
3. 更新 [`PersonalMcpExposureTest.java`](../backend/src/test/java/com/personalos/mcp/server/PersonalMcpExposureTest.java)
   的对外工具清单和相关业务测试，重新构建并重启后端，再用 MCP 客户端核对工具目录。

前端会直接显示新工具的原始名称；如需中文标题和专用图标，可在
[`McpSettingsPanel.vue`](../frontend/src/components/settings/McpSettingsPanel.vue) 的 `toolPresentation` 中补充映射。
当前对外工具均为只读；新增写入能力需要接入系统现有的人工确认流程，不能只改只读标记。

HTTP 路径和通用响应约定见 [`api.md`](api.md)，本地启动与生产部署见 [`development.md`](development.md) 和 [`operations.md`](operations.md)。
