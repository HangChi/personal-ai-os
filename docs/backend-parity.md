# Java / Go 双版本实现与差异

Java 是主后端，Go 是同产品的迁移候选，两版共用前端、业务接口及 SQL 来源。本页记录当前实现的对应关系与有意差异；历史验收结果保存在 [Go 规格](../specs/033-go-rewrite/README.md)和[报告索引](reports/README.md)，不能用功能清单推导生产批准。

## 源码导航

Java 路径均相对 `backend/src/main/java/com/personalos/`；Go 相对 `backend-go/internal/`。

| 领域 | Java | Go | 共用说明 |
| --- | --- | --- | --- |
| HTTP/装配 | 各模块 controller、common/config | app、platform/http、cmd/personalos | [API](api.md)、[路由参考](reference/http-routes.md) |
| Agent 循环 | common/ai、common/ai/tool、agent | business/agent、business/chat | [运行机制](agent-runtime.md) |
| 工具目录 | agent/tool、calendar/tool、memory/tool、knowledge/tool | business/agent 的 tool-definitions*.json | [工具](agent-tools.md) |
| Run/问题/排队 | chat/service、agent/interaction | business/chat | [恢复与状态](agent-runtime.md) |
| 计划 | agent/plan | business/agent | [计划及证据](agent-runtime.md#3-计划与步骤) |
| 历史/压缩 | chat、common/ai | business/chat、business/context | [上下文](memory-and-context.md) |
| 长期记忆 | memory | business/memory | [记忆](memory-and-context.md) |
| 文档解析/切块 | knowledge/reader、knowledge/service | business/knowledge | [摄入](rag-ingestion.md) |
| 召回/证据/问答 | knowledge/service、knowledge/rag | business/knowledge | [检索](rag-retrieval.md) |
| 索引/治理 | knowledge/jobs、knowledge/governance | business/knowledge | [Worker](distributed-workers.md) |
| MCP | mcp | business/mcp | [双向 MCP](mcp.md) |
| 事务/幂等/确认 | common/idempotency、common/confirmation | platform/database、business/idempotency、business/confirmation | [可靠性](agent-reliability.md) |
| 认证/限流 | common/auth、common/web | platform/auth、platform/ratelimit、platform/modelgate | [安全](../SECURITY.md) |

可从 [Java 入口](../backend/README.md)、[Go 入口](../backend-go/README.md)、[前端入口](../frontend/README.md)分别开始。目录级映射表示职责对应，不承诺逐行实现一致。

## 当前必须区分的行为

| 事项 | Java | Go |
| --- | --- | --- |
| 默认启动 | 业务 Spring 应用 | 离线诊断，不连接模型/数据库 |
| 业务装配 | 依赖注入与配置/profile | `--foundation`，再显式 p5/p6/p7/p8 |
| dotenv | 自动查找；可禁用 | 默认禁用，显式 opt-in |
| 生产状态 | 根生产 Compose 的主实现 | prod 启动拒绝，readyz 503；门禁未解除 |
| SQL 执行 | Flyway migrate/validate | 只读历史/脚本校验，不执行迁移 |
| PDF | PDFBox 文本层 | Poppler 外部进程；需要系统依赖 |
| 同步知识入库 | 短元数据事务 + 外部向量写 + 失败补偿 | 解析/全部嵌入完成后单个短事务提交 |
| 异步知识入库 | 事务外准备 + 租约/fencing 短事务提交 | 同类原子提交边界 |
| OpenAI 文档嵌入 | Spring AI 默认 EMBED 元数据格式；token 批处理 | 对照相同格式；保守按 16 块批处理 |
| 零相关证据分类 | 历史严格负例出现 CLARIFY，失败保留 | 已有对应 INSUFFICIENT 修复与单独负例证据 |
| 共享登录 | Servlet/Spring 会话序列化 | Go 会话序列化；切换需重新登录 |
| 模型隐式重试配置 | 可配置框架参数，默认一次/SDK 无重试 | 当前只接受一次/SDK零重试基线；不支持值启动拒绝 |
| 附件清理 cron | Spring 调度语义 | 支持子集；不支持 L/W/# 时启动拒绝 |

Go 的单项修复不能回填 Java 历史结果，也不能把修复前全量测试与修复后单题测试拼成“新版全量通过”。两端真实模型结果的差异和局限见 [2026-11-06 RAG 报告](rag-evaluation-20261106.md)。

## SQL 唯一来源与不可变基线

当前 SQL 在 [Java migration 目录](../backend/src/main/resources/db/migration/)，为 V1–V49。V43–V49 扩展任务工作区、日历交换、采集、只读工作流、维护及边界修复。Go 从同一目录读取，不维护第二份 SQL。

Go 迁移 oracle 固定提交 `cb5d694d62fa93742b5789b97273e76d931ce335` 的 V1–V42。固定清单、checksum、历史报告中的“42”是实验条件；应保留并明确版本范围。当前启动目录和历史基线是两个概念，不应为追平数量而修改基线哈希。

## 发布边界

Go 仍需独立生产门禁审查，详见 [P8 记录](../specs/033-go-rewrite/p8-completion.md)、[Go 安全](../backend-go/docs/security.md)及[部署](../backend-go/deploy/README.md)。真实浏览器 Push、原 E2E 预算断言、saveMemory 跳过用例、用户备份/生产 TLS 与人工验收等已有残余不能靠文档改写消除。

两版不得并行写现有业务库。受控候选验证使用隔离实例；真实切换需要停入口与全部 Worker、备份、核查未知写/待确认/投递、验证恢复，回退同样停掉当前写端。

## 双端同步改动清单

1. 先明确共享协议与领域语义：DTO、错误码、默认值、时间、取消、幂等、SSE 和确认边界。
2. 在两版实现和测试中同步，保留框架差异。新增工具还需同步 Schema/策略/证据/直接调用目录，不只增加 HTTP 路由。
3. SQL 只追加到唯一来源；核对两端当前目录校验与固定基线测试的各自范围。
4. 更新共用行为文档、两版入口及本页差异；当前路由用生成器更新。
5. 分别执行工程回归；需要模型质量证据时再按[评测指南](evaluation.md)运行有授权、有预算的隔离评测。
