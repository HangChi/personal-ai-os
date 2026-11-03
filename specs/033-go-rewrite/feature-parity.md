# 功能覆盖矩阵

本表给出行为级任务；**完整逐文件责任分配在 inventory.md**，任何未在本表点名的 Java 文件仍在迁移范围内。
本表所有业务能力当前均未完成 Go 全量实现。代表性来源不是排他名单；每个模块还必须覆盖其 Entity、DTO、Repository、SPI、配置和测试。

## 公共能力

ID | 必须覆盖的行为 | 主要 Java 来源 | 阶段
--- | --- | --- | ---
F01 | 统一信封、全部错误码/HTTP 映射、空 data、UTC 时间、分页/溢出保护、参数/转换/上传/内容协商/未知路径异常脱敏 | `common/api/*`、`common/exception/*`、`common/json/JsonCodec`、`common/text/Texts` | P1
F02 | 环境变量与 .env 优先级、类型化配置、prod/monitoring/角色 profile、生产启动拒绝弱口令/未认证/未开 CSRF/非 Secure Cookie | `common/config/*`、`application*.yml`、`META-INF/spring.factories` | P1
F03 | 单用户登录、密码哈希、用户名/密码校验、连续失败锁定、会话轮换/注销、匿名 me 探测、CSRF、匿名白名单、管理口权限 | `common/auth/*` | P1
F04 | 请求 ID 校验/回显、异步日志传播、模型请求路径匹配、令牌桶、数据库跨进程限流与并发租约、API 预留容量、专用 Redis 限流降级、流结束释放许可 | `common/web/*`、`common/config/ContextTaskConfig` | P1
F05 | 健康依赖探测（数据库 DOWN 不伪报 UP；AI 未配置不拖垮整体）、Actuator 健康/信息/指标/监控端口隔离、业务计时与标签 | `common/health/*`、`chat/metrics/*`、`application-monitoring.yml`、`deploy/monitoring/*` | P1/P8
F06 | 两层幂等、请求摘要稳定性、同键异请求冲突、并发抢占与结果回放、普通 HTTP 陈旧占位处理、TOOL 不确定结果不重做、审计/保留清理 | `common/idempotency/*`、`agent/audit/*`、`common/maintenance/*` | P2/P8
F07 | 确认动作注册完整性、一次性令牌/有效期/归属校验、确认与执行的事务边界、取消/过期/失败状态、并发确认只执行一次 | `common/confirmation/*`、各模块 `confirmation/*` | P2/P4/P7

## 任务、模型、对话与 Agent

ID | 必须覆盖的行为 | 主要 Java 来源 | 阶段
--- | --- | --- | ---
F08 | 任务创建/查询/筛选/分页/统计/标签/编辑/完成/重开/删除；恢复 previous_status；字段省略与显式清空；标签顺序/去空/去重/长度/数量；任务对提醒的影响 | `task/*`、`TaskService`、`TaskTags`、`TaskQuery` | P2
F09 | OpenAI 兼容与 Ollama 配置、配置启动应用/运行时解析、多模型档案 CRUD/排序/激活、模型列表与窗口探测、协议与音频模型、连接测试、密钥掩码/加密/旧数据读取 | `common/ai/config/*`、`AiChatClientProvider`、`RuntimeChatModelResolver` | P3
F10 | 会话创建/全量与分页搜索/改名/删除/模型选择；消息顺序和游标分页；rewind；Markdown 导出；引用快照；原始模型响应检查与 usage 统计 | `chat/controller/*`、`ChatSessionService`、`ChatMessageService`、`ChatCitationService`、`ChatMarkdownExporter`、`UsageStatsService`、`chat/support/*` | P4
F11 | 旧 POST SSE start/citations/rag/delta/done/error 与心跳；同步校验后建流；超时、断线、取消、空输出、上游错误、持久化和 token 用量；图像/思考参数 | `ChatCompletionService`、`ChatStreamEvents`、`SseHeartbeatScheduler`、`StreamUsage` | P4
F12 | 上下文字符/消息/token 预算、实际模型窗口与证据来源、探测/人工预算持久化、输出/图片/工具 schema 预留、过长请求保护/收敛重试、滚动摘要、提示贡献顺序和轮末处理 | `common/ai/*Context*`、`*Token*`、`*Window*`、`ToolSchemaOverhead`、`PromptContributor`、`ChatPromptAssembler`、`ChatSummaryService`、`ChatTurnFinisher` | P4
F13 | 工具注册与参数 schema、参数规范化、调用进度、失败去重、每工具/每轮调用与模型次数预算、连续失败终止、工具结果提示、日程写入防护、审计身份与幂等 | `AgentToolCallingManager`、`AgentTurnBudget`、`common/ai/tool/*`、`CalendarWriteGuardChatModel`、`ToolResultPromptContributor`、各模块 ToolContributor | P4
F14 | 计划创建/查询/启动步骤/完成/失败/跳过/修订、依赖和状态校验、会话唯一活动计划、计划提示注入/截断、工具与步骤的记录绑定、未完成计划不能伪报任务结束 | `agent/plan/*`、`specs/022-agent-planning`、`specs/023-plan-enforcement` | P4
F15 | 持久化运行创建/查询/latest/停止/继续/确认完成；运行互斥与租约；事件持久化/游标/批次/重连；重启恢复与结果不明暂停；HTTP 断开不取消任务 | `ChatRunService`、`ChatRunRepository`、`RunEventBuffer/Hub/Delivery`、`RunEmitter` | P4
F16 | askUser 问题/选项/回答持久化；WAITING_USER；QUEUE/STEER 跟进、编辑/取消/转引导；安全边界应用、顺序保持、停止/确认/异常时保留队列；答案不替代副作用授权 | `agent/interaction/*`、`ChatRunController`、`ChatRunService` | P4
F17 | 附件上传/内容读取/格式与体积限制/路径安全/图像上下文；本地文件与 S3 对象 namespace；孤儿清理和保留引用；音频转写状态与能力校验 | `ChatAttachmentService/Janitor/ContextBuilder`、`chat/storage/*`、`AudioTranscriptionController/Service` | P3/P4

## 记忆、日程与通知

ID | 必须覆盖的行为 | 主要 Java 来源 | 阶段
--- | --- | --- | ---
F18 | 手动记忆 CRUD/置顶、常驻条数和预算、相似度/新近度排序、无嵌入降级、hash/语义去重、事实解析、替代关系、命中计数、自动沉淀开关、向量回填及批处理 | `memory/*`、`MemoryService`、`MemoryFactParser`、`MemoryRelevance`、`MemoryEmbeddingBackfill` | P5
F19 | 日程 CRUD/区间查询/范围与条数限制、重复规则展开、结束时间/提前量/时区语义、任务与日程的提醒物化、去重、lookback/lookahead 和重启补偿 | `calendar/recurrence/*`、`CalendarEventService`、`ReminderScanner`、`ReminderScheduler` | P5
F20 | 提醒查询/已读/手动扫描、实时 SSE snapshots/心跳/重连；本机订阅者隔离、数据库快照补偿、RabbitMQ fanout 跨 API 广播和降级轮询 | `ReminderController`、`ReminderService`、`ReminderStreamDelivery`、`calendar/messaging/ReminderBroadcast` | P5
F21 | VAPID 配置、订阅新增/删除/去重、订阅过期清理、可靠投递领取/重试/状态、Web Push 与站内提醒独立 | `calendar/push/*`、`V28__create_web_push.sql` | P5
F22 | Webhook 配置/HTTPS/本地测试例外、目标启用时间、过期提醒窗口、稳定投递 ID、时间戳+正文 HMAC、超时/重定向/429/5xx/非重试 4xx、最多尝试/退避/清理与领取隔离 | `calendar/webhook/ReminderWebhookWorker`、`V40__reminder_webhook_delivery.sql` | P5

## 知识、基础设施、MCP 与运维

ID | 必须覆盖的行为 | 主要 Java 来源 | 阶段
--- | --- | --- | ---
F23 | 同步上传/替换/删除与异步索引任务；MD/TXT/PDF 文本层/DOCX/图片视觉提取；文件名/类型/大小/内容阈值；Markdown 结构切分/重叠；文档锁、向量批次写入/原子替换、维度启动校验 | `knowledge/controller/*`、`reader/*`、`splitter/*`、`service/*`、`startup/*` | P6
F24 | 异步任务状态/进度/阶段/负载清理/摘要去重、SKIP LOCKED 领取、租约 token/续租/最终提交 fencing、失效回收/重试/清理；index 角色隔离 | `knowledge/jobs/*`、`V34__knowledge_index_jobs.sql` | P6
F25 | pgvector 与关键词召回、标识符/LIKE 转义、RRF/权重/阈值/排名、OpenSearch 可选召回/降级与重建、投影 Outbox 版本/领取身份、防乱序与删除投影 | `KnowledgeRetrievalService`、`KeywordExtractor`、`VectorStoreInspector`、`knowledge/search/*`、`KnowledgeSearchPort` | P6
F26 | 多阶段 RAG、查询预算/超时、可选 reranker、证据审查/澄清/拒答、引用与来源溯源、来源空间/元数据/版本、矛盾片段核验、异步审查/重试、人工冲突解决与旧版本决议失效 | `knowledge/rag/*`、`knowledge/governance/*`、`KnowledgeQaService`、`KnowledgeMetadata` | P6
F27 | 有界本地 embedding cache 与观测、Redis embedding/candidate cache、provider/model 隔离、TTL/故障回退、候选缓存 generation 失效、命名空间与敏感键策略 | `common/ai/cache/*`、`RedisCandidateCache`、`V42__knowledge_candidate_cache_generation.sql` | P3/P6
F28 | RabbitMQ durable 唤醒信号、事务 Outbox、publisher confirm、重试与定时兜底、index/review/delivery 独立执行器、共享模型预算/API 预留、租约与多副本领取 | `WorkSignalRelay`、`RabbitJobNotifier`、`WorkerWakeupsConfig`、`RuntimeRole`、`DatabaseCoordination`、`V37–V39` | P1/P6/P8
F29 | 外部 MCP 连接 stdio/HTTP 配置、启动验证/命名冲突/工具名单、原始名与装配名映射、AUTO/CONFIRM 默认策略、连接失败降级、待确认执行与摘要、连接状态/刷新 | `mcp/client/*`、`mcp/config/*`、`mcp/tool/*`、`mcp/confirmation/*`、`mcp/status/*` | P7
F30 | 对外 MCP 协议入口/认证/CSRF 例外、四个只读工具及参数 schema；不递归暴露外部工具或内部写工具；独立开关/身份/名称/版本/endpoint | `mcp/server/PersonalMcpTools`、`application.yml`、`PersonalMcpExposureTest` | P7
F31 | 五模块全局搜索的分组/排序/转义/限制；流式全量 JSON 导出与各模块贡献者；空库导入/格式版本/20MB/校验/回滚、引用与附件/向量恢复边界 | `common/search/*`、各 `*SearchContributor`、`export/*`、各 `*ExportContributor` | P7
F32 | 全部 profile/Compose/Caddy/镜像/角色端口、监控仪表盘与告警、备份恢复与保留清理、启动停止/worker discovery、评估语料与指标、CI、配置模板、开源许可 | `deploy/*`、`compose*.yml`、`scripts/dev/*`、`scripts/eval/*`、`.github/workflows/ci.yml`、docs/operations 等 | P8

## 文件级覆盖规则

- Java 根启动类必须迁移启动装配、扫描/调度启用与生命周期；package-info.java 的模块约定也纳入审查。
- `common` 131、`chat` 61、`knowledge` 59、`calendar` 42、`agent` 27、`mcp` 22、`memory` 22、`task` 19、`export` 7，以及根启动类 1：合计 391 个主源码文件。
- 符号、record 参数、注解声明和来源行号在 inventory.json；完整文件内容以固定 Git blob 和 SHA-256 为依据。索引不是 Java AST，不能以提取到的符号代替阅读方法体。
- 全部 154 个 Java 测试文件、测试辅助实现与测试资源逐项验收，不只迁移命名为 IntegrationTest 的文件。
- 145 个显式 `${UPPER_CASE}` 环境变量名已索引，但动态属性/relaxed binding 不一定出现此语法，仍须审查完整配置文件和类。
- Spring 自动配置能力不一定存在 Controller：MCP、Actuator、会话、multipart、错误分派、默认校验/序列化和内容协商必须人工补充验收。

## 常见误判

- 只复刻旧聊天流，遗漏持久化 runs/提问/队列/引导。
- 只做向量检索，遗漏异步索引、全文投影、证据审查、来源版本与冲突治理。
- 用进程内 mutex/计数器替代数据库协调，导致多 API/Worker 互斥失效。
- 用 RabbitMQ 投递成功替代数据库任务完成，或漏掉 broker 停机后的轮询恢复。
- 只做页面通知，遗漏已读持久化、跨实例广播、Web Push、签名 Webhook。
- 用 JSON 字节拼接直接算摘要，改变 Java JSON 规范化/幂等/加密协议。
- 认为共享同一个 Redis 即可共享 Spring 会话；序列化兼容或切换失效策略必须明确。
- 只在空库启动成功，未验证历史迁移、原有密文/向量/附件和任务状态。
- 将测试文件数量或清单项数量当作业务等价的证明。


## P3 阶段验收补记（2026-11-03）

F09全部模型/档案存储、密文、协议、热切换和探测已完成；F17的音频状态/转写及F27的embedding缓存/观测已完成。F10/F12的会话持久化、上下文预算消费，F17附件及F27 candidate cache仍属于P4/P6，不因共享模型接口可用而提前完成。14条模型相关路由、224场景固定Java差分、TLS PostgreSQL/Redis及最小权限/五角色进程均已验收。具体边界、原231个P3测试声明的职责映射见[p3-completion.md](p3-completion.md)。
