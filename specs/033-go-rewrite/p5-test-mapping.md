# P5 原测试职责映射

固定 Java 基线为 `cb5d694d62fa93742b5789b97273e76d931ce335`。索引的 78 个文件包含 17 个 HTTP 声明、6 个工具、25 个运行/配置钩子和 139 个测试声明。定向 Java JUnit 运行含参数展开，结果为 156 个通过用例。

| 原 Java 测试类 | 声明 | Go 对照或对应断言 |
| --- | ---: | --- |
| CalendarReminderIntegrationTest | 22 | `TestP5ReminderPostgres`、`TestP5CalendarPaginationAndReminderRebuild`、`TestP5TaskReminderLifecycle`、`TestP5AuthenticatedAgentAndAutoMemory`、`TestP5ReminderRabbitFanout`、`TestP5WebhookRecovery`。覆盖分页、全天、窗口、任务变化、重扫、已读、SSE、Agent 和 HMAC |
| RecurrenceCalculatorTest | 19 | `TestJavaRecurrenceOracle`、`TestOccurrencesDSTAndMonthlyAnchor`。基线直接生成期望，按 UTC 时刻逐项对照 |
| ReminderStreamDeliveryTest | 9 | `TestP5ReminderRabbitFanout`、`TestReminderStreamSharesSnapshotsAndRecovers`、`TestReminderStreamAdmissionAndShutdown`。验证实际 HTTP 快照、跨实例通知、已读、共享查询、心跳、失败恢复、连接容量和关闭 |
| WebPushTransportTest | 2 | `TestP5WebPushRecoveryAndRetryClassification` 检查加密和 VAPID 请求头。真实 Chrome Service Worker 验证可解密 |
| WebPushRepositoryIntegrationTest | 5 | `TestP5WebPushRecoveryAndRetryClassification`、`TestP5AuthenticatedAgentAndAutoMemory`、`TestP5BrowserWebPush`。模型凭据加密仍由 P3 的 `model/write_integration_test.go` 覆盖 |
| WebPushServiceTest | 8 | `TestP5WebPushRecoveryAndRetryClassification`、`TestP5AuthenticatedAgentAndAutoMemory`、`TestP5BrowserWebPush`。核对配置、订阅、有效响应、临时失败上限和过期级联 |
| MemoryIntegrationTest | 14 | `TestP5MemoryPostgres`、`TestP5MemoryConcurrentCreateAndBackfill`、`TestP5AuthenticatedAgentAndAutoMemory`、`TestRenderPinnedRelevanceAndBudget`。CRUD、400/404、提示词、注入上限和真实工具落库 |
| MemoryServiceTest | 21 | `TestP5MemoryPostgres`、`TestP5AuthenticatedAgentAndAutoMemory`、`TestRenderPinnedRelevanceAndBudget`。候选窗 120、条数 30、字符 6000 和命中间隔按 Java 服务逐项核对 |
| MemoryPropertiesTest | 3 | 原固定 Binder 对照保留，`config/module_binding_test.go`。实际参数通过 `foundation_p5.go` 装配 |
| MemoryEmbeddingBackfillTest | 4 | `TestP5MemoryConcurrentCreateAndBackfill` 验证多批写入。空批、三次失败及无进展停止在 `memory/vector.go` 核对 |
| MemoryRelevanceTest | 10 | `TestRenderPinnedRelevanceAndBudget`，以及 `memory/prompt.go` 的 0.7/0.3 权重、范围钳制和半衰期实现 |
| MemoryFactParserTest | 5 | `TestJavaFactParserOracle`，含围栏、无效 JSON、UTF-16 长度和 Jackson 标量转换 |
| MemoryEmbeddingServiceTest | 9 | `TestMemoryEmbeddingTimeoutAndInvalidVectors`、`TestP5MemoryPostgres`，模型标签回落在 `foundation_p5.go` 核对 |
| MemoryEmbeddingStoreTest | 8 | `TestP5MemoryPostgres`、`TestP5MemoryConcurrentCreateAndBackfill`、`TestMemoryEmbeddingTimeoutAndInvalidVectors`，对照真实 pgvector 与非法维度降级 |

Go 以合并的业务测试覆盖原职责。Spring 容器、Mockito 调用次数、JPA flush、Servlet 容器线程池等内部断言通过源码审查对应到 Go 的事务、请求协程或有界通道，逐项索引维持 `implemented`，全量迁移的 `verified` 门禁状态按原发布流程处理。停机恢复场景使用已持久化并过期的领取记录启动新投递实例。实际命令见 [P5 验收](p5-completion.md)。
