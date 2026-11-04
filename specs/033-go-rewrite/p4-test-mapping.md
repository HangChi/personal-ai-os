# P4 原测试职责映射（2026-11-04）

固定Java基线 `cb5d694d62fa93742b5789b97273e76d931ce335`。P4索引共163个文件（118主源码、45测试）、32条HTTP声明、16工具、12运行/配置钩子和379个测试声明。379是来源声明数，不是Go测试执行数；同职责在Go以合并用例覆盖。

此表逐类给出实现/断言入口；`progress.json`逐测试声明保留来源方法名、实现与测试路径、状态和边界。`implemented`表示本阶段代码已接线且有对应职责验证，不将每个Spring/Mockito/JPA内部细节声称为一对一差分，也不批量标记`verified`。全部发布门禁保持原状态。

原Java测试类 | 声明数 | Go职责/实际断言入口
--- | ---: | ---
| `AgentToolCallingIntegrationTest` | 16 | 参数/16工具定义、预算/审计/幂等与真实任务副作用；`backend-go/internal/business/agent/tools_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go`, `backend-go/internal/business/chat/execution_integration_test.go` |
| `ToolInvocationAuditIntegrationTest` | 8 | 参数/16工具定义、预算/审计/幂等与真实任务副作用；`backend-go/internal/business/agent/tools_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go`, `backend-go/internal/business/chat/execution_integration_test.go` |
| `AgentPlanServiceTest` | 19 | 计划七工具、顺序/失败/证据/修订与防提前结束；`backend-go/internal/business/chat/execution_integration_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go` |
| `AgentPlanToolsTest` | 13 | 计划七工具、顺序/失败/证据/修订与防提前结束；`backend-go/internal/business/chat/execution_integration_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go` |
| `PlanPromptContributorTest` | 10 | 计划七工具、顺序/失败/证据/修订与防提前结束；`backend-go/internal/business/chat/execution_integration_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go` |
| `PlanWiringTest` | 7 | 计划七工具、顺序/失败/证据/修订与防提前结束；`backend-go/internal/business/chat/execution_integration_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go` |
| `AgentInteractionIntegrationTest` | 11 | askUser/等待/回答、QUEUE/STEER、恢复与确认边界；`backend-go/internal/business/chat/execution_integration_test.go` |
| `ChatApiIntegrationTest` | 23 | 旧SSE/超时/取消/部分回复、计划/日历防伪完成与轮末接口；`backend-go/internal/business/chat/execution_integration_test.go`, `backend-go/internal/business/chat/stream_test.go` |
| `ChatKnowledgeIntegrationTest` | 7 | 贡献顺序、时间/知识/附件与固定预算；`backend-go/internal/business/chat/prompt_test.go`, `backend-go/internal/business/chat/context_integration_test.go`, `backend-go/internal/business/context/token_test.go`；完整RAG/冲突证据1项待P6，P4六项SPI/引用/降级已覆盖 |
| `ChatMessagePaginationIntegrationTest` | 6 | 会话/消息/引用、分页搜索与rewind/导出；`backend-go/internal/business/chat/postgres_integration_test.go`, `backend-go/internal/business/chat/java_oracle_test.go`, `backend-go/internal/business/chat/model_test.go` |
| `ChatRunIntegrationTest` | 10 | 持久Run/事件游标/租约fence、启动恢复/停止/关闭与读者解耦；`backend-go/internal/business/chat/stream_test.go`, `backend-go/internal/business/chat/execution_integration_test.go` |
| `ChatStreamTerminationIntegrationTest` | 4 | 旧SSE/超时/取消/部分回复、计划/日历防伪完成与轮末接口；`backend-go/internal/business/chat/execution_integration_test.go`, `backend-go/internal/business/chat/stream_test.go` |
| `RunEventBufferTest` | 1 | 持久Run/事件游标/租约fence、启动恢复/停止/关闭与读者解耦；`backend-go/internal/business/chat/stream_test.go`, `backend-go/internal/business/chat/execution_integration_test.go` |
| `TokenUsageIntegrationTest` | 8 | 有效usage快照/估算、完成后尽力记账、时区/DST与来源聚合；`backend-go/internal/business/chat/usage_integration_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go`, `backend-go/internal/business/chat/execution_integration_test.go`；两项保留清理待P8 |
| `ChatContextControllerTest` | 1 | 预算偏好/窗口、历史副本/工具配对与有界保护重试；`backend-go/internal/business/context/budget_test.go`, `backend-go/internal/business/context/guard_test.go`, `backend-go/internal/business/chat/context_integration_test.go` |
| `ThinkingLevelTest` | 4 | 旧SSE/超时/取消/部分回复、计划/日历防伪完成与轮末接口；`backend-go/internal/business/chat/execution_integration_test.go`, `backend-go/internal/business/chat/stream_test.go` |
| `JpaChatMemoryTest` | 12 | 事务外摘要、边界快照/竞态与超时；`backend-go/internal/business/chat/context_integration_test.go`, `backend-go/internal/business/chat/prompt_test.go` |
| `ChatStreamMetricsTest` | 2 | 流首正文延迟/耗时/活跃/usage和指标输出；`backend-go/internal/app/chat_metrics_test.go`, `backend-go/internal/app/foundation_p4_integration_test.go` |
| `ChatAttachmentContextBuilderTest` | 6 | UUID/大小/路径、文本/DOCX/PDF/图片与引用清理；`backend-go/internal/business/chat/attachments_test.go`, `backend-go/internal/business/chat/execution_integration_test.go` |
| `ChatAttachmentServiceTest` | 10 | UUID/大小/路径、文本/DOCX/PDF/图片与引用清理；`backend-go/internal/business/chat/attachments_test.go`, `backend-go/internal/business/chat/execution_integration_test.go` |
| `ChatMarkdownExporterTest` | 8 | 会话/消息/引用、分页搜索与rewind/导出；`backend-go/internal/business/chat/postgres_integration_test.go`, `backend-go/internal/business/chat/java_oracle_test.go`, `backend-go/internal/business/chat/model_test.go` |
| `ChatMessageServiceRewindTest` | 4 | 会话/消息/引用、分页搜索与rewind/导出；`backend-go/internal/business/chat/postgres_integration_test.go`, `backend-go/internal/business/chat/java_oracle_test.go`, `backend-go/internal/business/chat/model_test.go` |
| `ChatPromptAssemblerTest` | 10 | 贡献顺序、时间/知识/附件与固定预算；`backend-go/internal/business/chat/prompt_test.go`, `backend-go/internal/business/chat/context_integration_test.go`, `backend-go/internal/business/context/token_test.go` |
| `ChatSessionServiceTest` | 22 | 会话/消息/引用、分页搜索与rewind/导出；`backend-go/internal/business/chat/postgres_integration_test.go`, `backend-go/internal/business/chat/java_oracle_test.go`, `backend-go/internal/business/chat/model_test.go` |
| `ChatSummaryServiceTest` | 13 | 事务外摘要、边界快照/竞态与超时；`backend-go/internal/business/chat/context_integration_test.go`, `backend-go/internal/business/chat/prompt_test.go` |
| `ChatTurnFinisherTest` | 12 | 旧SSE/超时/取消/部分回复、计划/日历防伪完成与轮末接口；`backend-go/internal/business/chat/execution_integration_test.go`, `backend-go/internal/business/chat/stream_test.go` |
| `UsageStatsServiceTest` | 8 | 有效usage快照/估算、完成后尽力记账、时区/DST与来源聚合；`backend-go/internal/business/chat/usage_integration_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go`, `backend-go/internal/business/chat/execution_integration_test.go` |
| `S3AttachmentStoreIntegrationTest` | 1 | 可选官方SDK/本地优先、所有权/namespace与有界GC；`backend-go/internal/business/chat/s3_integration_test.go` |
| `ChatResponseInspectionTest` | 12 | 有效usage快照/估算、完成后尽力记账、时区/DST与来源聚合；`backend-go/internal/business/chat/usage_integration_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go`, `backend-go/internal/business/chat/execution_integration_test.go` |
| `SseHeartbeatSchedulerTest` | 5 | 两种SSE可配置心跳、默认本地04:30附件cron与关闭开关；`backend-go/internal/business/chat/scheduling_test.go`, `backend-go/internal/business/chat/stream_test.go`, `backend-go/internal/business/chat/execution_integration_test.go`, `backend-go/internal/app/foundation_p4_integration_test.go` |
| `AgentToolCallingManagerTest` | 1 | 参数/16工具定义、预算/审计/幂等与真实任务副作用；`backend-go/internal/business/agent/tools_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go`, `backend-go/internal/business/chat/execution_integration_test.go` |
| `AgentTurnBudgetTest` | 4 | 参数/16工具定义、预算/审计/幂等与真实任务副作用；`backend-go/internal/business/agent/tools_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go`, `backend-go/internal/business/chat/execution_integration_test.go` |
| `AiChatClientProviderModelSelectionTest` | 22 | P3按名工厂/单飞/隔代证据，P4按会话模型消费；`backend-go/internal/business/model/runtime_test.go`, `backend-go/internal/business/model/p3_oracle_test.go`, `backend-go/internal/business/chat/context_integration_test.go` |
| `CalendarWriteGuardChatModelTest` | 4 | 旧SSE/超时/取消/部分回复、计划/日历防伪完成与轮末接口；`backend-go/internal/business/chat/execution_integration_test.go`, `backend-go/internal/business/chat/stream_test.go` |
| `ContextBudgetPersistenceIntegrationTest` | 1 | 预算偏好/窗口、历史副本/工具配对与有界保护重试；`backend-go/internal/business/context/budget_test.go`, `backend-go/internal/business/context/guard_test.go`, `backend-go/internal/business/chat/context_integration_test.go` |
| `ContextBudgetServiceTest` | 25 | 预算偏好/窗口、历史副本/工具配对与有界保护重试；`backend-go/internal/business/context/budget_test.go`, `backend-go/internal/business/context/guard_test.go`, `backend-go/internal/business/chat/context_integration_test.go` |
| `ContextGuardChatModelTest` | 14 | 预算偏好/窗口、历史副本/工具配对与有界保护重试；`backend-go/internal/business/context/budget_test.go`, `backend-go/internal/business/context/guard_test.go`, `backend-go/internal/business/chat/context_integration_test.go` |
| `ContextWindowEvidenceTest` | 5 | P3按名工厂/单飞/隔代证据，P4按会话模型消费；`backend-go/internal/business/model/runtime_test.go`, `backend-go/internal/business/model/p3_oracle_test.go`, `backend-go/internal/business/chat/context_integration_test.go` |
| `CurrentTimePromptContributorTest` | 2 | 贡献顺序、时间/知识/附件与固定预算；`backend-go/internal/business/chat/prompt_test.go`, `backend-go/internal/business/chat/context_integration_test.go`, `backend-go/internal/business/context/token_test.go` |
| `ModelContextWindowsTest` | 7 | P3按名工厂/单飞/隔代证据，P4按会话模型消费；`backend-go/internal/business/model/runtime_test.go`, `backend-go/internal/business/model/p3_oracle_test.go`, `backend-go/internal/business/chat/context_integration_test.go` |
| `ModelWindowReferencesTest` | 2 | P3按名工厂/单飞/隔代证据，P4按会话模型消费；`backend-go/internal/business/model/runtime_test.go`, `backend-go/internal/business/model/p3_oracle_test.go`, `backend-go/internal/business/chat/context_integration_test.go` |
| `TokenEstimationTest` | 6 | 原BPE词表与UTF-16/字符估算、schema开销；`backend-go/internal/business/context/token_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go` |
| `ToolSchemaOverheadTest` | 8 | 原BPE词表与UTF-16/字符估算、schema开销；`backend-go/internal/business/context/token_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go` |
| `ToolArgumentsTest` | 4 | 参数/16工具定义、预算/审计/幂等与真实任务副作用；`backend-go/internal/business/agent/tools_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go`, `backend-go/internal/business/chat/execution_integration_test.go` |
| `ToolExecutorTest` | 1 | 参数/16工具定义、预算/审计/幂等与真实任务副作用；`backend-go/internal/business/agent/tools_test.go`, `backend-go/internal/business/chat/p4_oracle_test.go`, `backend-go/internal/business/chat/execution_integration_test.go` |

## 合并与差分边界

- 固定原Java差分资产：存储206场景；预算150轮/4500动作、请求绑定504、工具参数21、工具定义16、usage快照56、估算100；原JTokkit1325场景。差分脚本默认比较，显式`--write`才生成，不从Go输出生成期望。
- 真实TLS PostgreSQL断言覆盖Run重放/停止/恢复/未知结果、问答/队列/引导、工具确认与审计、计划/失败证据、摘要竞态、引用完成顺序、异步记账失败、DST统计；S3另由一次性MinIO执行。
- 心跳调度用原配置驱动真实两种HTTP流；stop/写失败/并发序列化有独立断言。Go取消/超时使用context/timer，不复制Java的ScheduledFuture对象。写失败立即停止，安全收紧为不继续写已损坏的连接。
- 附件清理消费开关和六字段cron，默认本地时区04:30，关闭保留旧孤儿文件；支持数字、名称、范围、列表、步长、?、标准宏与`-`禁用。Spring扩展L/W/#不支持，启动明确拒绝；不能据此声称支持全部Spring cron语法。
- 日历防伪完成测试使用合成成功/失败回执；真正日历写、自动记忆贡献属P5；完整RAG冲突证据属P6；搜索/导出跨模块注册属P7；用量/操作记录保留清理属P8。
- 重启测试用持久中断状态和新执行组件接管，不等同操作系统强杀演练。前端API是原Axios/CSRF/SSE客户端22组真实联调，加既有类型/构建/逻辑/组件测试；无浏览器视觉验收。

实际命令/结果与失败重跑边界见 [p4-completion.md](p4-completion.md) 和 [validation.md](validation.md)。

人工确认实时状态归 P2 基线，由 P4 提示词组装消费。补验涵盖会话隔离、待确认计数、精确过期边界、最近记录和真实模型请求中的确认前后变化。见 [补验记录](validation.md#p2p4-人工确认实时状态补验)。
