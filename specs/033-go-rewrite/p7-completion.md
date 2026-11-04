# P7 分批实现与验收

P7 开始于 2026-11-04，T700–T705 仍按实际验收逐项关闭；P8 和五个发布门禁不在本阶段关闭。

## 批次 1：MCP 连接基础

- 官方 Go SDK v1.8.0，固定 Java 协议版本 2025-06-18；真实 stdio 子进程与 HTTP 握手、发现、调用。
- 默认 CONFIRM，按原工具名匹配 AUTO；名称清洗/长度限制、冲突检测、schema 规范化。
- 无效配置启动失败；单个服务不可用时降级。超时、重连旧会话隔离、关闭后不可复活；子进程不继承应用敏感环境。
- `go test -race -count=1 -timeout=2m ./internal/business/mcp` 通过。
- HTTP 模拟工具有独立的有限执行时间；不假定 stateless 服务在客户端断开后立即取消处理。
- 尚未完成运行时状态路由、确认装配，因此不提前关闭 T700/T701。

## 批次 2：MCP 安全执行与协议入口

- 外部工具接入既有 Agent 预算、持久化幂等与审计；CONFIRM 不调用远程服务，确认 CAS 只允许一次执行。
- 真实 HTTP 服务验证确认前零调用、重复申请复用、12 个并发确认仅执行一次、重连后回调使用当前会话。
- 保留 Java MCP 参数解析语义：坏 JSON 回退空参数，再走预算/审计，由远程 schema 校验；内置工具绑定规则不变。
- 对外独立开关，固定四个只读工具与 readOnly/destructive/idempotent/openWorld 声明；禁止递归暴露 Agent/MCP 工具。
- 默认及自定义 MCP 端点只豁免 CSRF、不豁免认证；状态/重连接口仍要求常规 CSRF。
- `Properties.MCP()` 保留来源优先级和列表整体覆盖，再解析最终使用值的占位符；旧 157 项绑定对照通过。
- 加法 HTTP 配置：`mcp.http-servers.<name>.endpoint/headers/auto-tools/connect-timeout`，环境变量使用 `MCP_HTTPSERVERS_<NAME>_ENDPOINT`；与 stdio 同名拒绝。
- CLI `--p7` 包含 P6/P5，默认开关、生产拒绝与 readiness 守卫不变。
- 定向 P7 race 测试通过，最终整体验收后关闭 T700–T702。
