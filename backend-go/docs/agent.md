# Go Agent 实现导航

行为定义维护在[Agent 运行机制](../../docs/agent-runtime.md)、[完整工具目录](../../docs/agent-tools.md)、[上下文/记忆](../../docs/memory-and-context.md)与[可靠性](../../docs/agent-reliability.md)。本页只列 Go 的实现位置与特有装配。

| 入口 | 职责 |
| --- | --- |
| [business/agent](../internal/business/agent) | 工具 Schema/参数、策略、调用预算、计划与工具证据 |
| [business/chat](../internal/business/chat) | 模型轮次、持久 Run、事件、恢复、澄清、followup、引导 |
| [business/context](../internal/business/context) | tokenizer、全请求预算、历史裁剪与有界超限重试 |
| [business/model](../internal/business/model) | 配置/档案、OpenAI/Ollama 客户端、嵌入缓存 |
| [business/confirmation](../internal/business/confirmation) | 人工批准与处理器执行 |
| [business/idempotency](../internal/business/idempotency) | HTTP/TOOL 所有权、回放与未知结果保护 |
| [platform/modelgate](../internal/platform/modelgate) | 前台准入与共享并发 |
| [app](../internal/app) | 按角色、模块及开关显式组合 |

`tool-definitions.json` 为 P4 的 16 项定义，`tool-definitions-p5.json` 增加 6 项，p6 再增加知识检索；合计 23 个标准工具，另有条件注册 searchWeb 和动态 MCP。不能把最初“16 工具”当成完整 p8 目录。

业务执行必须 `--foundation`；记忆/日历、知识、MCP 分别随 p5/p6/p7（或包含它们的 p8）接入。API/combined 执行对话，Worker 不开放业务对话路由。默认诊断没有 Agent。

Run 终态、确认与问题状态相互独立。只读事件连接断开不取消持久 Run；legacy stream 则有不同取消语义。未知外部写保留所有权，恢复/队列不得绕过。Go 使用数据库租约和 fencing，内存 Hub 只加速状态通知。实际调用图与请求/恢复约束见共用运行文档。

双端扩展必须同步 Java `@Tool` 与 Go JSON Schema/分发/策略/测试，更新共用目录；直接工具 API、Agent 目录和对外 MCP 只读工具集合各有边界。具体步骤见[工具扩展](../../docs/agent-tools.md)。
