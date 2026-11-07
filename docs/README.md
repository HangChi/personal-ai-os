# 文档中心

本目录是当前项目的使用与实现文档入口，覆盖 **Java 主后端、Go 候选后端和共用前端**。当前文档按代码核对；有日期的评测/复盘报告保留当时结论。功能存在、测试通过与生产发布批准是三个不同判断。

## 按目标阅读

| 目标 | 推荐文档 |
| --- | --- |
| 第一次运行 | [根 README](../README.md) → [开发指南](development.md) → [配置](configuration.md) |
| 使用工作台 | [使用指南](user-guide.md)、[快捷指令](chat-commands.md) |
| 理解系统 | [架构](architecture.md)、[双版本实现与差异](backend-parity.md) |
| 理解 Agent | [执行链路与恢复](agent-runtime.md) → [工具目录与扩展](agent-tools.md) → [记忆和上下文](memory-and-context.md) |
| 排查 Agent | [预算、失败与追踪](agent-reliability.md)、[评测方法](evaluation.md) |
| 理解 RAG | [全流程](rag.md) → [解析与切块](rag-ingestion.md) → [召回、重排、证据与引用](rag-retrieval.md) |
| 接入工具和模型 | [MCP](mcp.md)、[工具扩展](agent-tools.md)、[配置](configuration.md) |
| 调用 API | [API 约定](api.md)、[Java Controller 路由参考](reference/http-routes.md) |
| 部署维护 | [运维](operations.md)、[独立 Worker](distributed-workers.md)、[Redis 扩展](redis-extensions.md)、[提醒与 MQ](reminder-stream-and-queue.md) |
| 贡献与安全 | [贡献指南](../CONTRIBUTING.md)、[安全策略](../SECURITY.md) |

## 版本专用入口

- [Java 后端](../backend/README.md)：正常业务启动、Flyway、Spring AI 入口。
- [Go 后端](../backend-go/README.md)：诊断/业务开关、候选运行与生产门禁。
- [Go 文档导航](../backend-go/docs/README.md)：语言实现说明、隔离测试和候选部署。
- [前端](../frontend/README.md)：共用工作台、切换代理、状态与测试。

## 文档分工与同步规则

1. `README.md` 面向首次访问者，只保留定位、能力、启动、版本状态和文档导航。
2. `docs/` 描述当前使用方式与实现。共享算法只维护一份，Java/Go 入口都链接到它，并在[双版本说明](backend-parity.md)记录差异。
3. `backend-go/docs/` 描述 Go 装配、依赖和候选边界；模块 README 补充局部实现细节。
4. [规格索引](../specs/README.md)中的 `specs/NNN-*` 保存规格、设计决策、任务和验证证据；未完成任务不能写成当前能力。
5. [历史报告索引](reports/README.md)串联有日期的结果。原始报告不因新版本出现而改写为“通过”。

变更工具、RAG 阶段、配置、HTTP/SSE 或迁移时，同时检查共用文档、两版入口、实现差异及相关测试。不要把迁移基线的固定数量、旧机器路径、旧测试耗时或历史分数当作最新项目状态。

校验命令（仓库根目录）：

```bash
python3 scripts/docs/check_docs.py
python3 scripts/docs/generate_routes.py --check
```

前者检查维护文档本地链接/标题锚点、内置工具名称和两版 SQL 来源；后者检查路由参考是否与 Java Controller 一致。二者均不调用模型或修改业务数据。
