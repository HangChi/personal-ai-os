# Quickstart: 阶段 6 — MCP 客户端接入

> 自动化测试之外的人工验证步骤。每一步都给出**期望看到什么**，以及**看不到时先查什么**。

## 0. 前置

- Docker 起 PostgreSQL（`docker compose up -d`），端口 55432；
- 已配置模型（Ollama 本地或 OpenAI 兼容服务），否则对话不可用（MCP 组件仍会初始化）；
- Node ≥ 18（filesystem server 经 npx 拉起，会现下载）。

## 1. 零启用验证（先证明「不配也不错」）

**动作**：确认 `.env` 中没有任何 `MCP_*` 变量，启动后端。

**期望**：
- 启动日志中**没有**任何 MCP 相关内容；
- `curl http://localhost:8080/api/v1/health` 正常；
- 对话功能与阶段 5 之前一致。

**看不到时先查**：`MCP_ENABLED` 是否被.env 或 shell 环境变量置了 true。

## 2. 接入 filesystem server（只读放行）

**动作**：在 `.env` 末尾加：

```bash
MCP_ENABLED=true
MCP_SERVERS_FILESYSTEM_COMMAND=npx
MCP_SERVERS_FILESYSTEM_ARGS=-y,@modelcontextprotocol/server-filesystem,./mcp-sandbox
MCP_SERVERS_FILESYSTEM_AUTO_TOOLS=read_file,read_text_file,read_multiple_files,list_directory,list_directory_with_sizes,directory_tree,search_files,get_file_info,list_allowed_directories
```

建沙箱目录与样例文件：`mkdir -p mcp-sandbox && echo "hello mcp" > mcp-sandbox/hello.txt`，重启后端。

**期望**：
- 启动日志出现 server 连接成功与工具列举数量（**14 个**：10 个按上面的清单 AUTO，其余 4 个变更类默认 CONFIRM）；
- 日志中工具名带 `mcp_filesystem_` 前缀。

**看不到时先查**：npx 是否能联网下载 server 包（首次较慢）；`./mcp-sandbox` 是否相对后端工作目录存在。

## 3. 对话里用只读工具（应直接执行）

**动作**：前端对话页问：「mcp-sandbox 目录下有什么？」

**期望**：助手调用 `mcp_filesystem_list_directory`（或同类），**不弹确认**，回复与真实目录一致。

## 4. 对话里用变更类工具（应弹确认）

**动作**：对话中说：「把『今天天气不错』写进 mcp-sandbox/note.txt」。

**期望**：
- 助手调用 `mcp_filesystem_write_file` 被拦截：**文件尚未创建**（先 `cat` 确认不存在）；
- 对话中出现待确认卡片，摘要含 `server=filesystem`、`工具=write_file`、`路径=./mcp-sandbox/note.txt`；
- 点确认后文件才真正出现，内容正确。

**看不到时先查**：该工具是否被误加进 `AUTO_TOOLS`；确认卡片是否渲染了 server/工具名（FR-012）。

## 5. 故障注入（应有明确降级）

| 注入 | 操作 | 期望 |
|---|---|---|
| 命令不存在 | `MCP_SERVERS_FILESYSTEM_COMMAND=/nonexistent/npx` 重启 | 应用正常启动；该 server 标记不可用 + 错误日志；对话仍可用（无该 server 工具） |
| 连接挂起 | 临时把 `command` 换成一个不退出也不说话的进程（如 `sleep 300`） | 启动在超时（默认 10s）后继续，该 server 不可用；应用总体就绪不被无限阻塞 |
| 名字冲突 | 给另一个 server 配一个暴露同名工具的实现（或用测试替身） | **启动失败**，错误信息列出冲突的工具名、来源 server 与内置工具 |

## 6. 生命周期（退出零残留）

**动作**：确认 filesystem server 进程在运行（`ps aux | grep server-filesystem` 应命中），
然后正常停止后端（Ctrl-C / kill）。

**期望**：停止后 `ps aux | grep server-filesystem` **无命中**——server 子进程随应用一并退出。

**看不到时先查**：是否用了 kill -9 之外的信号后应用卡在关闭钩子；逐个检查 server 销毁日志（L4）。

## 7. 收尾

- 全部通过后，把 `.env` 里的 `MCP_ENABLED` 改回 `false`（或注释整段），确认 §1 行为恢复；
- 在 `specs/006-mcp-client/quickstart.md` 的「验证记录」表补上实际结果与日期。

## 验证记录

| 步骤 | 结果 | 日期 | 备注 |
|---|---|---|---|
| 1. 零启用 | ✅ 通过 | 2026-10-15 | 自动化覆盖：`PersonalOsApplicationTests` 断言未配置时不建 registry bean；`McpDegradedStartupIntegrationTest` 守无模型场景 |
| 2. 接入 server | ✅ 通过 | 2026-10-15 | 真实 `npx @modelcontextprotocol/server-filesystem`（14 个工具）：10 AUTO / 4 CONFIRM，逐条装配日志 |
| 3. 只读直行 | ✅ 通过 | 2026-10-15 | `operation_record` 记 `mcp_sandbox_list_directory` SUCCESS / 17ms / 返回真实 `[FILE] hello.txt` |
| 4. 变更确认 | ✅ 通过 | 2026-10-15 | 拦截时 `note.txt` 不存在；确认后落盘「来自真机验证」；重复确认返回 40002；浏览器端卡片展示与点击确认同样实测通过 |
| 5. 故障注入 | ✅ 通过 | 2026-10-15 | 命令不存在 / 挂起超时 / 多 server 故障隔离均自动化覆盖；**schema 损坏一项实测不可达**（SDK 会归一化 schema），改由单测直测自检函数（见 research R-11） |
| 6. 退出清理 | ✅ 通过 | 2026-10-15 | 关闭前 `pgrep -f server-filesystem` = 3 → 关闭后 = 0；自动化侧以「shutdown hook 写关闭标记文件」硬断言 |

> 真机环境与逐项证据见 `tasks.md` 的 T035 记录。本表只记**真实结果**，不写承诺。
