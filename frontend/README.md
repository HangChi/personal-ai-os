# 共用前端

Personal AI OS 的 Vue 3 + TypeScript + Vite 8 工作台，Java 主后端与 Go 候选后端共用。包含对话/Agent、任务、知识库、日程、定时报告、运行中心、搜索和设置；旧工作区地址由路由重定向保留。

## 开发

推荐 Node.js 22.18+。package engines 允许 `^20.19.0 || >=22.12.0`，但原生 TS 逻辑测试需要更高的 22.18+。

```bash
cd frontend
npm ci
npm run dev -- --host 127.0.0.1
```

默认 <http://127.0.0.1:5173>；`/api` 代理到 `http://localhost:8080`。使用已按[Go 指南](../backend-go/docs/development.md)启动的隔离候选时：

```bash
VITE_PROXY_TARGET=http://127.0.0.1:8081 npm run dev -- --host 127.0.0.1
```

代理在 Vite 启动时读取，修改后需重启。`VITE_API_BASE_URL` 默认 `/api/v1`，与代理目标是不同配置；`/mcp` 不是此代理的前端页面。生产由反向代理处理 SPA 与 API，见[运维](../docs/operations.md)。

## 结构与状态

| 目录 | 职责 |
| --- | --- |
| `src/api` | DTO、响应信封、HTTP/SSE 客户端 |
| `src/stores` | 业务与会话状态 |
| `src/views` | 页面编排 |
| `src/components` | 共用组件，生产使用自动注册 |
| `src/lib` | 可测试的纯函数与协议辅助逻辑 |
| `src/router` | 路由、旧地址跳转、认证守卫 |
| `test` | Node 逻辑测试及 Vitest 组件测试 |

Agent Run 由服务端持久化；刷新/断流后从状态与游标恢复。停止、继续、确认、澄清、排队和引导是独立动作，不应靠本地布尔值推断成功。直接工具未知结果时会保留幂等键信息；不要自动重发写操作。协议见 [Agent 运行](../docs/agent-runtime.md)和 [API](../docs/api.md)。

## 验证

```bash
npm run lint
npm run format:check
npm run type-check
npm run test:logic
npm run test:component
npm run build
```

逻辑测试使用 Node 解析钩子与类型剥离；组件测试使用 Vitest、happy-dom、Vue Test Utils。`test/component/setup.ts` 注册 Element Plus 并补充浏览器 API 桩；测试配置与 Vite 开发优化配置分开。`npm run preview` 仅预览构建产物。

两版接口变更应同步 DTO、错误与 SSE 分支；确认、运行终态、引用和未知写恢复需验证真实交互。前端构建成功不代表 Go 已获得生产批准。参见[使用指南](../docs/user-guide.md)、[贡献指南](../CONTRIBUTING.md)。
