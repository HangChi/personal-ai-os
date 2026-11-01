# MCP 指南

Personal AI OS 同时支持 MCP Client 和 MCP Server。两个方向互不依赖，默认都关闭。

## 1. 作为 MCP Client

这个模式让 Personal AI OS 的助手调用外部 MCP Server。配置只从环境变量读取，避免把命令、参数和密钥写进数据库。

```bash
MCP_ENABLED=true
MCP_SERVERS_FILESYSTEM_COMMAND=npx
MCP_SERVERS_FILESYSTEM_ARGS=-y,@modelcontextprotocol/server-filesystem,./mcp-sandbox
MCP_SERVERS_FILESYSTEM_AUTO_TOOLS=read_file,list_directory,directory_tree
```

`MCP_SERVERS_<NAME>_ARGS` 的最后一个目录是 filesystem server 的访问边界，应使用专用目录，不能直接开放 home 目录或整个磁盘。

策略规则如下：

- `AUTO_TOOLS` 中的工具按只读操作处理，直接执行；
- 未列入 `AUTO_TOOLS` 的工具默认需要人工确认；
- 工具名会加上 `mcp_<server>_` 前缀，并进入统一的调用审计；
- server 连接失败只会让该 server 标记为不可用，不会阻止应用启动；工具名冲突会阻止启动。

启动后进入前端「活动」页，可以查看外部 server 的连接状态、工具数、只读工具和失败原因。

## 2. 作为 MCP Server

这个模式让 Cursor、Claude Desktop 或其他 MCP 客户端查询 Personal AI OS 的个人数据。第一版只开放只读工具：

| 工具 | 用途 |
|---|---|
| `personal_list_tasks` | 按状态和关键词分页查询任务 |
| `personal_search_knowledge` | 检索知识库片段 |
| `personal_list_calendar` | 按 ISO-8601 时间区间查询日程 |
| `personal_list_memories` | 读取长期记忆 |

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

- **看不到 MCP 菜单**：MCP Server 没有单独的前端菜单；「活动」页展示的是外部 MCP Client 状态。
- **启用后仍然连接不上**：确认后端已重启，且 `PERSONAL_MCP_SERVER_ENABLED=true` 来自实际运行进程的环境变量。
- **浏览器打开返回 401**：这是认证拦截，不代表 MCP 未注册；先登录或在 MCP 客户端配置认证会话。

HTTP 路径和通用响应约定见 [`api.md`](api.md)，本地启动与生产部署见 [`development.md`](development.md) 和 [`operations.md`](operations.md)。
