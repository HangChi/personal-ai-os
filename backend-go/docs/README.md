# Go 文档中心

Go 候选的默认诊断、显式模块装配、隔离验证与生产门禁独立于 Java。通用产品行为维护在根 `docs/`，本目录提供 Go 实现入口和特有边界。

| 目标 | 文档 |
| --- | --- |
| 启动与测试 | [Go README](../README.md)、[开发指南](development.md) |
| 组合与角色 | [架构](architecture.md)、[模块装配](../internal/app/README.md) |
| Agent | [Go 代码导航](agent.md)、[共用运行机制](../../docs/agent-runtime.md)、[全部工具](../../docs/agent-tools.md) |
| RAG | [Go 代码导航](rag.md)、[共用摄入/切块](../../docs/rag-ingestion.md)、[检索与证据](../../docs/rag-retrieval.md) |
| 版本对应 | [双版本差异](../../docs/backend-parity.md)、[当前 API](../../docs/api.md) |
| 安全与部署 | [安全](security.md)、[候选部署](../deploy/README.md) |
| 质量门禁 | [Java / Go 统一验收要求](../../docs/testing.md) |
| 验证证据 | [当前评测方法](../../docs/evaluation.md)、[Agent / RAG 覆盖率](../../docs/reports/coverage-agent-rag-2026-11-07.md)、[分阶段验证原记录](validation-history.md)、[P8](../../specs/033-go-rewrite/p8-completion.md) |

`internal/**/README.md` 记录模块局部契约与分阶段实验；其中固定 V1–V42、P1/P3 等范围不等于当前所有模块状态。当前 SQL 只读取 Java 的 V1–V49 来源。任何生产批准都必须单独核查 release gates，不能靠目录数量、测试样例或文档措辞推断。

2026-11-07 测试扩展的完整集合语句覆盖率为 **95.05%**，默认全量为 **89.16%**；口径、31 份 profile 的合并方式及复现命令见[覆盖率报告](../../docs/reports/coverage-agent-rag-2026-11-07.md)。Java 分支覆盖率为 95.09%；两种语言的指标分别统计。
