# 配置调用方逐项处置（P1/P3/P4/P5收口）

固定Java基线：`cb5d694d62fa93742b5789b97273e76d931ce335`。70处@Value与8处getProperty全部列出；wired只表示所列阶段的该调用方已装配，deferred是明确的后续业务任务，不表示已实现。

12类record的全部67字段已有真实Binder对照。Auth/RateLimit/App进入P1组合；Agent/Plan/Context/Protection及AI提示/预算已接P4，AI模型配置/协议已接P3，Calendar/Memory已接P5，Knowledge属于P6，MCP属于P7；不创建业务空壳。

| 来源与行号 | 原表达式/键 | 阶段/任务 | 状态与处置 |
| --- | --- | --- | --- |
| `backend/src/main/java/com/personalos/calendar/push/WebPushSettings.java:14` | `${app.web-push.enabled:false}` | P5/T505 | wired：Web Push开关/公私钥/subject已接Foundation；真实Chrome订阅并收到解密后的提醒 |
| `backend/src/main/java/com/personalos/calendar/push/WebPushSettings.java:15` | `${app.web-push.public-key:}` | P5/T505 | wired：Web Push开关/公私钥/subject已接Foundation；真实Chrome订阅并收到解密后的提醒 |
| `backend/src/main/java/com/personalos/calendar/push/WebPushSettings.java:16` | `${app.web-push.private-key:}` | P5/T505 | wired：Web Push开关/公私钥/subject已接Foundation；真实Chrome订阅并收到解密后的提醒 |
| `backend/src/main/java/com/personalos/calendar/push/WebPushSettings.java:17` | `${app.web-push.subject:}` | P5/T505 | wired：Web Push开关/公私钥/subject已接Foundation；真实Chrome订阅并收到解密后的提醒 |
| `backend/src/main/java/com/personalos/calendar/webhook/ReminderWebhookWorker.java:42` | `${app.reminders.webhook.url:}` | P5/T506 | wired：Webhook目的地址/签名/本地HTTP开关已接delivery角色；回环验证签名、重试与禁重定向 |
| `backend/src/main/java/com/personalos/calendar/webhook/ReminderWebhookWorker.java:43` | `${app.reminders.webhook.secret:}` | P5/T506 | wired：Webhook目的地址/签名/本地HTTP开关已接delivery角色；回环验证签名、重试与禁重定向 |
| `backend/src/main/java/com/personalos/calendar/webhook/ReminderWebhookWorker.java:44` | `${app.reminders.webhook.allow-local-http:false}` | P5/T506 | wired：Webhook目的地址/签名/本地HTTP开关已接delivery角色；回环验证签名、重试与禁重定向 |
| `backend/src/main/java/com/personalos/chat/service/ChatAttachmentJanitor.java:56` | `${app.chat.attachment-janitor-enabled:true}` | P4/T408 | wired：附件清理开关已接Foundation/角色；cron默认本地04:30，关闭不清理；扩展L/W/#显式拒绝 |
| `backend/src/main/java/com/personalos/chat/service/ChatAttachmentService.java:58` | `${app.chat.upload-dir:data/uploads}` | P4/T408 | wired：持久目录与6MB业务限制已接上传/真实大小/本地与S3；区别于8MB容器限制 |
| `backend/src/main/java/com/personalos/chat/service/ChatAttachmentService.java:59` | `${app.chat.max-attachment-bytes:6291456}` | P4/T408 | wired：持久目录与6MB业务限制已接上传/真实大小/本地与S3；区别于8MB容器限制 |
| `backend/src/main/java/com/personalos/chat/service/ChatCompletionService.java:138` | `${app.chat.stream-timeout:120s}` | P4/T404 | wired：流超时与取消已接真实模型流，部分回复与空输出有落库断言 |
| `backend/src/main/java/com/personalos/chat/storage/S3StorageConfig.java:16` | `${app.storage.s3.endpoint}` | P4/T408 | wired：S3开关/连接/凭据/命名空间/清理与大小已接官方SDK及一次性MinIO；关闭不创建客户端 |
| `backend/src/main/java/com/personalos/chat/storage/S3StorageConfig.java:17` | `${app.storage.s3.region:us-east-1}` | P4/T408 | wired：S3开关/连接/凭据/命名空间/清理与大小已接官方SDK及一次性MinIO；关闭不创建客户端 |
| `backend/src/main/java/com/personalos/chat/storage/S3StorageConfig.java:18` | `${app.storage.s3.access-key:}` | P4/T408 | wired：S3开关/连接/凭据/命名空间/清理与大小已接官方SDK及一次性MinIO；关闭不创建客户端 |
| `backend/src/main/java/com/personalos/chat/storage/S3StorageConfig.java:19` | `${app.storage.s3.secret-key:}` | P4/T408 | wired：S3开关/连接/凭据/命名空间/清理与大小已接官方SDK及一次性MinIO；关闭不创建客户端 |
| `backend/src/main/java/com/personalos/chat/storage/S3StorageConfig.java:29` | `${app.storage.s3.bucket}` | P4/T408 | wired：S3开关/连接/凭据/命名空间/清理与大小已接官方SDK及一次性MinIO；关闭不创建客户端 |
| `backend/src/main/java/com/personalos/chat/storage/S3StorageConfig.java:30` | `${app.storage.s3.namespace}` | P4/T408 | wired：S3开关/连接/凭据/命名空间/清理与大小已接官方SDK及一次性MinIO；关闭不创建客户端 |
| `backend/src/main/java/com/personalos/chat/storage/S3StorageConfig.java:30` | `${app.storage.s3.cleanup:false}` | P4/T408 | wired：S3开关/连接/凭据/命名空间/清理与大小已接官方SDK及一次性MinIO；关闭不创建客户端 |
| `backend/src/main/java/com/personalos/chat/storage/S3StorageConfig.java:31` | `${app.chat.max-attachment-bytes:6291456}` | P4/T408 | wired：S3开关/连接/凭据/命名空间/清理与大小已接官方SDK及一次性MinIO；关闭不创建客户端 |
| `backend/src/main/java/com/personalos/chat/support/SseHeartbeatScheduler.java:63` | `${app.chat.heartbeat-interval:15s}` | P4/T404 | wired：心跳间隔已接两种业务SSE，默认15s，非正或不足1ms启动拒绝；真实5ms心跳/停止有断言 |
| `backend/src/main/java/com/personalos/common/ai/cache/RedisCacheConfig.java:14` | `${app.redis.uri}` | P3/T304 | wired：第三专用Redis客户端已接CLI/Foundation；哈希namespace、150ms超时、TLS/最小ACL与故障回退已验收 |
| `backend/src/main/java/com/personalos/common/ai/cache/RedisCacheConfig.java:15` | `${app.redis.timeout-ms:150}` | P3/T304 | wired：第三专用Redis客户端已接CLI/Foundation；哈希namespace、150ms超时、TLS/最小ACL与故障回退已验收 |
| `backend/src/main/java/com/personalos/common/ai/cache/RedisCacheConfig.java:21` | `${app.redis.namespace}` | P3/T304 | wired：第三专用Redis客户端已接CLI/Foundation；哈希namespace、150ms超时、TLS/最小ACL与故障回退已验收 |
| `backend/src/main/java/com/personalos/common/ai/config/ModelConfigService.java:128` | `${spring.ai.openai.base-url:}` | P3/T300 | wired：配置逐字段回落/原始环境来源已接持久化、启动覆盖和运行时工厂 |
| `backend/src/main/java/com/personalos/common/ai/config/ModelConfigService.java:129` | `${spring.ai.openai.api-key:}` | P3/T300 | wired：配置逐字段回落/原始环境来源已接持久化、启动覆盖和运行时工厂 |
| `backend/src/main/java/com/personalos/common/ai/config/ModelConfigService.java:130` | `${spring.ai.openai.chat.model:}` | P3/T300 | wired：配置逐字段回落/原始环境来源已接持久化、启动覆盖和运行时工厂 |
| `backend/src/main/java/com/personalos/common/ai/config/ModelConfigService.java:131` | `${spring.ai.openai.embedding.model:}` | P3/T300 | wired：配置逐字段回落/原始环境来源已接持久化、启动覆盖和运行时工厂 |
| `backend/src/main/java/com/personalos/common/ai/config/ModelConfigService.java:132` | `${spring.ai.openai.chat.temperature:}` | P3/T300 | wired：配置逐字段回落/原始环境来源已接持久化、启动覆盖和运行时工厂 |
| `backend/src/main/java/com/personalos/common/ai/config/ModelKeyConverter.java:24` | `${app.model-key-encryption-key:}` | P3/T301 | wired：原AES-GCM格式及三态密钥写入已接仓储；仅用合成密钥双向Java对照 |
| `backend/src/main/java/com/personalos/common/auth/SecurityConfig.java:64` | `${server.servlet.session.cookie.secure:false}` | P1/T105 | wired：Foundation安全链已读取canonical Secure/CSRF；保留历史spring/server路径差异 |
| `backend/src/main/java/com/personalos/common/auth/SecurityConfig.java:65` | `${app.security.csrf-enabled:false}` | P1/T105 | wired：Foundation安全链已读取canonical Secure/CSRF；保留历史spring/server路径差异 |
| `backend/src/main/java/com/personalos/common/auth/SharedSessionConfig.java:23` | `${app.shared-session.uri}` | P1/T105 | wired：独立Redis URI与Secure已装配；SESSION/Lax/HttpOnly/30分钟保持固定策略 |
| `backend/src/main/java/com/personalos/common/auth/SharedSessionConfig.java:49` | `${server.servlet.session.cookie.secure:false}` | P1/T105 | wired：独立Redis URI与Secure已装配；SESSION/Lax/HttpOnly/30分钟保持固定策略 |
| `backend/src/main/java/com/personalos/common/config/ProductionSecurityValidator.java:11` | `${app.security.production-mode:false}` | P1/T104 | wired：原安全守卫已逐项对照；P8切换门禁另阻止所有prod启动 |
| `backend/src/main/java/com/personalos/common/config/ProductionSecurityValidator.java:12` | `${app.auth.enabled:false}` | P1/T104 | wired：原安全守卫已逐项对照；P8切换门禁另阻止所有prod启动 |
| `backend/src/main/java/com/personalos/common/config/ProductionSecurityValidator.java:13` | `${app.security.csrf-enabled:false}` | P1/T104 | wired：原安全守卫已逐项对照；P8切换门禁另阻止所有prod启动 |
| `backend/src/main/java/com/personalos/common/config/ProductionSecurityValidator.java:14` | `${server.servlet.session.cookie.secure:false}` | P1/T104 | wired：原安全守卫已逐项对照；P8切换门禁另阻止所有prod启动 |
| `backend/src/main/java/com/personalos/common/config/ProductionSecurityValidator.java:15` | `${app.auth.password:}` | P1/T104 | wired：原安全守卫已逐项对照；P8切换门禁另阻止所有prod启动 |
| `backend/src/main/java/com/personalos/common/config/ProductionSecurityValidator.java:16` | `${spring.datasource.password:}` | P1/T104 | wired：原安全守卫已逐项对照；P8切换门禁另阻止所有prod启动 |
| `backend/src/main/java/com/personalos/common/config/RuntimeRole.java:11` | `${app.runtime.role:combined}` | P1/T108 | wired：五角色与profile/双端口/池上限已装配，业务Worker按所属阶段实现 |
| `backend/src/main/java/com/personalos/common/maintenance/DataRetentionService.java:24` | `${app.retention.token-usage-days:0}` | P8/T801 | deferred：用量/操作记录保留期与清理调度随运维生命周期验收 |
| `backend/src/main/java/com/personalos/common/maintenance/DataRetentionService.java:25` | `${app.retention.operation-record-days:0}` | P8/T801 | deferred：用量/操作记录保留期与清理调度随运维生命周期验收 |
| `backend/src/main/java/com/personalos/common/web/ModelConcurrencyGate.java:15` | `${app.model-max-concurrent-requests:4}` | P1/T107 | wired：共享租约/角色API预留容量已装配，超100明确拒绝 |
| `backend/src/main/java/com/personalos/common/web/RedisRateBucket.java:41` | `${app.rate-limit.redis.uri}` | P1/T107 | wired：独立URI/namespace、Lua/TIME/150ms/错误指标已装配 |
| `backend/src/main/java/com/personalos/common/web/RedisRateBucket.java:42` | `${app.rate-limit.redis.namespace}` | P1/T107 | wired：独立URI/namespace、Lua/TIME/150ms/错误指标已装配 |
| `backend/src/main/java/com/personalos/knowledge/governance/IngestionReviewWorker.java:26` | `${app.knowledge.rag.evidence-timeout-ms:12000}` | P6/T606 | deferred：证据超时随review租约与业务调度迁移 |
| `backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeIndexJobs.java:53` | `${app.knowledge.jobs.enabled:true}` | P6/T602 | deferred：索引任务开关随持久队列迁移，不启动空Worker |
| `backend/src/main/java/com/personalos/knowledge/jobs/messaging/RabbitJobsConfig.java:16` | `${app.rabbitmq.uri}` | P6/T604 | deferred：Rabbit连接与durable队列随Outbox/信号迁移 |
| `backend/src/main/java/com/personalos/knowledge/jobs/messaging/RabbitJobsConfig.java:23` | `${app.rabbitmq.queue:personalos.knowledge.ready}` | P6/T604 | deferred：Rabbit连接与durable队列随Outbox/信号迁移 |
| `backend/src/main/java/com/personalos/knowledge/jobs/messaging/WorkSignalRelay.java:32` | `${app.rabbitmq.queue:personalos.knowledge.ready}` | P6/T604 | deferred：队列名随可靠信号重发迁移 |
| `backend/src/main/java/com/personalos/knowledge/rag/CrossEncoderReranker.java:23` | `${app.knowledge.rag.reranker.endpoint:}` | P6/T605 | deferred：reranker端点/凭据/模型/协议随真实协议适配迁移 |
| `backend/src/main/java/com/personalos/knowledge/rag/CrossEncoderReranker.java:24` | `${app.knowledge.rag.reranker.api-key:}` | P6/T605 | deferred：reranker端点/凭据/模型/协议随真实协议适配迁移 |
| `backend/src/main/java/com/personalos/knowledge/rag/CrossEncoderReranker.java:24` | `${app.knowledge.rag.reranker.model:}` | P6/T605 | deferred：reranker端点/凭据/模型/协议随真实协议适配迁移 |
| `backend/src/main/java/com/personalos/knowledge/rag/CrossEncoderReranker.java:24` | `${app.knowledge.rag.reranker.protocol:cohere}` | P6/T605 | deferred：reranker端点/凭据/模型/协议随真实协议适配迁移 |
| `backend/src/main/java/com/personalos/knowledge/rag/RagRetrievalPipeline.java:29` | `${app.knowledge.rag.enabled:true}` | P6/T605 | deferred：RAG开关与总预算/证据预算随真实检索流水线迁移 |
| `backend/src/main/java/com/personalos/knowledge/rag/RagRetrievalPipeline.java:30` | `${app.knowledge.rag.budget-ms:20000}` | P6/T605 | deferred：RAG开关与总预算/证据预算随真实检索流水线迁移 |
| `backend/src/main/java/com/personalos/knowledge/rag/RagRetrievalPipeline.java:36` | `${app.knowledge.rag.evidence-timeout-ms:12000}` | P6/T605 | deferred：RAG开关与总预算/证据预算随真实检索流水线迁移 |
| `backend/src/main/java/com/personalos/knowledge/search/SearchConfig.java:15` | `${app.knowledge.search.endpoint:http://localhost:9200}` | P6/T603 | deferred：可选OpenSearch端点/index/凭据随真实检索迁移 |
| `backend/src/main/java/com/personalos/knowledge/search/SearchConfig.java:16` | `${app.knowledge.search.index:personalos-chunks-v1}` | P6/T603 | deferred：可选OpenSearch端点/index/凭据随真实检索迁移 |
| `backend/src/main/java/com/personalos/knowledge/search/SearchConfig.java:17` | `${app.knowledge.search.username:}` | P6/T603 | deferred：可选OpenSearch端点/index/凭据随真实检索迁移 |
| `backend/src/main/java/com/personalos/knowledge/search/SearchConfig.java:17` | `${app.knowledge.search.password:}` | P6/T603 | deferred：可选OpenSearch端点/index/凭据随真实检索迁移 |
| `backend/src/main/java/com/personalos/knowledge/service/RedisCandidateCache.java:31` | `${app.redis.namespace}` | P6/T607 | deferred：候选缓存namespace与全部动态配置指纹键随generation失效迁移 |
| `backend/src/main/java/com/personalos/knowledge/service/VectorStoreInspector.java:41` | `${spring.ai.vectorstore.pgvector.table-name:vector_store}` | P6/T601 | deferred：向量表/schema检查随向量仓储迁移；P1只验证原DDL |
| `backend/src/main/java/com/personalos/knowledge/service/VectorStoreInspector.java:42` | `${spring.ai.vectorstore.pgvector.schema-name:public}` | P6/T601 | deferred：向量表/schema检查随向量仓储迁移；P1只验证原DDL |
| `backend/src/main/java/com/personalos/mcp/status/McpStatusService.java:40` | `${spring.ai.mcp.server.enabled:false}` | P7/T702 | deferred：对外MCP开关/名称/版本/端点随协议入口迁移 |
| `backend/src/main/java/com/personalos/mcp/status/McpStatusService.java:41` | `${spring.ai.mcp.server.name:personal-ai-os}` | P7/T702 | deferred：对外MCP开关/名称/版本/端点随协议入口迁移 |
| `backend/src/main/java/com/personalos/mcp/status/McpStatusService.java:42` | `${spring.ai.mcp.server.version:0.1.0}` | P7/T702 | deferred：对外MCP开关/名称/版本/端点随协议入口迁移 |
| `backend/src/main/java/com/personalos/mcp/status/McpStatusService.java:43` | `${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}` | P7/T702 | deferred：对外MCP开关/名称/版本/端点随协议入口迁移 |
| `backend/src/main/java/com/personalos/memory/service/MemoryEmbeddingService.java:51` | `${spring.ai.openai.embedding.model:}` | P5/T501 | wired：模型名已接记忆向量写入、同模型排序、近重复取代和回填 |
| `backend/src/main/java/com/personalos/memory/service/MemoryEmbeddingService.java:52` | `${spring.ai.ollama.embedding.model:}` | P5/T501 | wired：模型名已接记忆向量写入、同模型排序、近重复取代和回填 |
| `backend/src/main/java/com/personalos/common/ai/cache/EmbeddingCacheConfig.java:21` | `app.embedding-cache.enabled` | P3/T304 | wired：默认观测/显式缓存、容量/TTL/模型选项指纹及Redis共享已接真实嵌入适配器 |
| `backend/src/main/java/com/personalos/common/ai/cache/EmbeddingCacheConfig.java:22` | `app.embedding-cache.capacity` | P3/T304 | wired：默认观测/显式缓存、容量/TTL/模型选项指纹及Redis共享已接真实嵌入适配器 |
| `backend/src/main/java/com/personalos/common/ai/cache/EmbeddingCacheConfig.java:23` | `app.embedding-cache.ttl` | P3/T304 | wired：默认观测/显式缓存、容量/TTL/模型选项指纹及Redis共享已接真实嵌入适配器 |
| `backend/src/main/java/com/personalos/common/ai/cache/EmbeddingCacheConfig.java:28` | `动态：spring.ai.model.embedding, spring.ai.openai.base-url, spring.ai.openai.embedding.model, spring.ai.ollama.base-url, spring.ai.ollama.embedding.model, app.embedding-cache.revision` | P3/T304 | wired：默认观测/显式缓存、容量/TTL/模型选项指纹及Redis共享已接真实嵌入适配器 |
| `backend/src/main/java/com/personalos/common/ai/config/ModelConfigService.java:532` | `动态：OPENAI_BASE_URL, OPENAI_API_KEY, OPENAI_CHAT_MODEL, OPENAI_EMBEDDING_MODEL` | P3/T300 | wired：配置逐字段回落/原始环境来源已接持久化、启动覆盖和运行时工厂 |
| `backend/src/main/java/com/personalos/common/auth/MonitoringSecurityConfig.java:18` | `local.management.port` | P1/T108 | wired：local.management.port由独立监听器结构保证；不依据用户Host/转发头授予管理权限 |
| `backend/src/main/java/com/personalos/common/config/DotEnvEnvironmentPostProcessor.java:73` | `app.dotenv.enabled` | P1/T104 | wired：默认禁读；显式开关/来源优先级/晚于ConfigData已验证 |
| `backend/src/main/java/com/personalos/knowledge/service/RedisCandidateCache.java:39` | `动态：spring.ai.model.embedding, spring.ai.openai.base-url, spring.ai.openai.embedding.model, spring.ai.ollama.base-url, spring.ai.ollama.embedding.model, app.embedding-cache.revision, app.knowledge.search.endpoint, app.knowledge.search.index` | P6/T607 | deferred：候选缓存namespace与全部动态配置指纹键随generation失效迁移 |

## 附加边界

- datasource/Hikari/Flyway/Servlet/管理/日志的支持与拒绝列表见`p1-foundation-contract.md`；未知扩展在创建连接前拒绝，不静默接受。
- prod原安全校验已完成；全量迁移切换属于P8，Go基础运行时仍额外拒绝prod，readyz仍503。
- 原spring.servlet.session.cookie配置路径差异继续保留；实际消费的是server.servlet.session.cookie。
- 完整机器可读来源、动态键集合与对应阶段证据见同名JSON；台账不改变任何全量迁移条目的verified状态。
- 复验：`python3 scripts/migration/go_config_disposition.py`，默认比较；仅显式`--write`重建。
