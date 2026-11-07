# 配置指南

入口：[开发指南](development.md) · [双版本差异](backend-parity.md) · [运维](operations.md)。配置名称与默认值以 [application.yml](../backend/src/main/resources/application.yml)、相关 Properties 类和 [.env.example](../.env.example)为准；本页汇总常用项，不复制全部配置。

## 加载与覆盖

| 项目 | Java | Go |
| --- | --- | --- |
| `.env` | 启动时自动查找当前/父目录；可关闭 | 默认不读；系统环境显式 `GO_DOTENV_ENABLED=true` 才读 |
| 禁用 dotenv | `APP_DOTENV_ENABLED=false` | 同名变量强制关闭，即使 Go opt-in 已开 |
| 优先级 | 系统环境优先于 `.env` | 系统环境 > profile 静态值 > 文件，保留占位符来源规则 |
| 支持配置 | Spring 配置体系及项目动态 MCP 配置 | 显式移植的固定属性/profile；不支持的 YAML import 等启动拒绝 |
| 运行配置 | 设置页中的模型配置/档案可持久化 | 实现同类运行配置；外部客户端按各业务装配开启 |

`.env` 是配置文件，不应 `source` 执行。Go 的解析也不执行 shell。不要依赖当前 shell 未知的导出值；端口、环境与目标数据库应在启动前明确确认。两版均不应在日志中输出配置、密钥或完整认证 URL。

## 数据库、进程与认证

| 变量 | Java 本地默认 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | 8080 | Go 业务运行要求显式端口，前端代理随之调整 |
| `POSTGRES_HOST/PORT` | localhost / 55432 | 宿主 Compose 映射；容器内使用服务名/5432 |
| `POSTGRES_DB/USER/PASSWORD` | personal_ai_os / personalos / personalos | 默认仅用于本地；Go 显式提供整组连接参数 |
| `APP_RUNTIME_ROLE` | combined | combined/api/index/review/delivery；角色不等同于自动加载 profile |
| `AUTH_ENABLED` | false | 公网必须启用，单用户身份 |
| `AUTH_USERNAME/PASSWORD` | admin / 空 | 生产密码至少 16 字节 |
| `APP_SECURITY_CSRF_ENABLED` | false | 生产强制开启，写请求携带会话 CSRF token |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | false（安全链读取的 canonical 属性） | HTTPS 入口必须 true，不能仅依赖 SESSION_COOKIE_SECURE 别名 |
| `MODEL_KEY_ENCRYPTION_KEY` | 空 | 可选 AES-256-GCM 主密钥；必须独立备份 |

当前 Java YAML/根生产 Compose 使用的 `SESSION_COOKIE_SECURE` 位于 `spring.servlet...`，安全链读取的是 `server.servlet...`；路径不一致，不能据前者认为 Secure 已开启。Java 生产需显式设置 `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`，Compose 本地叠加示例见[运维](operations.md)。这里只记录现有代码边界，不修改部署配置。

Go TLS 默认 verify-full，使用 `GO_POSTGRES_ROOT_CERT` 指定 CA，主机名需匹配证书；其余 Go 专有项见[开发指南](../backend-go/docs/development.md)与[部署](../backend-go/deploy/README.md)。设置生产安全参数不代表 Go 发布门禁已解除。

## 模型与上下文

| 变量 | 默认 | 含义 |
| --- | --- | --- |
| `AI_CHAT_PROVIDER` | ollama | ollama/openai/none |
| `OLLAMA_BASE_URL/OLLAMA_CHAT_MODEL` | localhost:11434 / qwen2.5:7b | 本地模型服务与名称 |
| `OPENAI_BASE_URL/OPENAI_CHAT_MODEL` | https://api.openai.com / gpt-4o-mini | 兼容协议端点与模型；密钥用 `OPENAI_API_KEY` |
| `AI_EMBEDDING_PROVIDER` | ollama | 与聊天独立配置 |
| `OLLAMA_EMBEDDING_MODEL` | bge-m3 | 默认 1024 维 |
| `OPENAI_EMBEDDING_MODEL` | 空 | 使用兼容嵌入时显式填写 |
| `AI_RETRY_MAX_ATTEMPTS` | 1 | 默认一次请求，无隐式模型重试 |
| `AI_MEMORY_MAX_MESSAGES/MAX_CONTEXT_CHARS` | 20 / 12000 | 历史窗口，不删除持久消息 |
| `AI_MEMORY_CONTEXT_WINDOW_TOKENS` | 131072 | 窗口回退值，实际受模型配置与预算保护约束 |
| `AI_CONTEXT_SUMMARY_ENABLED` | true | 旧历史滚动摘要 |
| `AI_CONTEXT_AUTO_MEMORY_ENABLED` | false | 轮末自动沉淀长期记忆 |

Go 对隐式重试只接受基线（框架 max-attempts=1、SDK max-retries=0），非支持值启动拒绝，不能假定所有 Java 参数都可任意覆盖。

设置页支持模型配置和档案、按会话选择模型。不要把 profile 展示值直接等同于所有启动时初始化客户端的实际参数；更换嵌入模型需核查客户端配置及重建方案。同为 1024 维也可能不在同一向量空间，不能混写旧索引。完整注入/压缩/预算见[记忆与上下文](memory-and-context.md)。

## Agent 准入与执行预算

| 变量 | 默认 |
| --- | --- |
| `APP_RATE_LIMIT_ENABLED/CAPACITY/PERIOD` | true / 60 / 1m |
| `AI_MODEL_MAX_CONCURRENT_REQUESTS` | 4 |
| `AI_TOOL_MAX_CALLS_PER_TOOL` | 3 |
| `AI_TOOL_MAX_TOTAL_CALLS` | 12 |
| `AI_TOOL_ON_LIMIT_EXCEEDED` | throw |
| `AI_AGENT_MAX_MODEL_CALLS` | 12 |
| `AI_AGENT_MAX_CONSECUTIVE_FAILURES` | 2 |

准入 429 与轮内预算 50203 含义不同。工具写策略由服务端目录决定，不能通过 Prompt 授予模型确认权。详见[执行链路](agent-runtime.md)、[工具目录](agent-tools.md)。

## RAG 与索引

| 变量 | 默认 | 影响 |
| --- | --- | --- |
| `AI_KNOWLEDGE_EMBEDDING_DIMENSIONS` | 1024 | 必须与数据库列和真实输出一致 |
| `AI_KNOWLEDGE_MAX_FILE_SIZE` | 5242880 | 单文件 5 MiB；仍受 multipart 限制 |
| `AI_KNOWLEDGE_CHUNK_SIZE_CHARS` | 1200 | 字符目标，非 token 上限 |
| `AI_KNOWLEDGE_CHUNK_OVERLAP_CHARS` | 0 | 长段切分重叠 |
| `AI_KNOWLEDGE_MIN_CHUNK_SIZE_CHARS` | 350 | 短片合并门槛，非所有最终块的硬下限 |
| `AI_KNOWLEDGE_TOP_K` | 4 | 返回数量，接口最大 20 |
| `AI_KNOWLEDGE_SIMILARITY_THRESHOLD` | 0.3 | 向量余弦门槛 |
| `AI_KNOWLEDGE_HYBRID_ENABLED` | true | PostgreSQL 关键词通道，不关闭 OpenSearch 双通道 |
| `AI_KNOWLEDGE_RRF_K/KEYWORD_CHANNEL_WEIGHT` | 60 / 1.0 | 位置融合及关键词权重 |
| `RAG_ENABLED` | true | 多阶段改写/重排/证据；关闭后问答仍可能调用最终模型 |
| `RAG_BUDGET_MS/RAG_EVIDENCE_TIMEOUT_MS` | 20000 / 12000 | 辅助流水线预算，不是端到端 SLA |
| `RAG_RERANKER_ENABLED` | false | 可选 CrossEncoder；关闭时仍可用 LLM 重排 |
| `RAG_RERANKER_PROTOCOL` | cohere | 支持 cohere/dashscope 协议；配 endpoint/key/model |
| `APP_KNOWLEDGE_RAG_CITATION_AUDIT_ENABLED` | false | 可选引用陈述审计 |

具体算法、失败降级与成本见 [RAG](rag.md)、[切块](rag-ingestion.md)、[检索](rag-retrieval.md)。修改切块/嵌入配置不会自动把旧文档重建成新格式。

## 可选扩展

默认均关闭；具体 URI、命名空间、TLS/ACL 与超时应分别配置。

| 开关 | 职责与边界 |
| --- | --- |
| `MCP_ENABLED` | 外部 stdio 工具；动态 `MCP_SERVERS_<NAME>_*`，只读名单是受信配置 |
| `PERSONAL_MCP_SERVER_ENABLED` | 本项目 `/mcp` Streamable HTTP，四个只读工具 |
| `BRAVE_SEARCH_API_KEY` | 有值时开放 searchWeb，问题/结果发送外部搜索服务 |
| `REDIS_ENABLED` | 嵌入/候选缓存基础连接；不自动打开共享会话或限流 |
| `REDIS_SESSION_ENABLED` | 独立连接共享登录会话；Java/Go 序列化不互通 |
| `REDIS_RATE_LIMIT_ENABLED` | 独立共享预算桶；故障拒绝放行 |
| `RAG_CANDIDATE_CACHE_ENABLED` | 候选短缓存，同时需要 Redis；默认 TTL 20 秒 |
| `RABBITMQ_ENABLED` | 任务就绪唤醒，数据库仍为事实来源 |
| `OPENSEARCH_ENABLED` | 可重建搜索投影，pending/故障回退 PostgreSQL |
| `S3_ENABLED` | 聊天附件对象存储；不是知识原文件自动归档 |
| `WEB_PUSH_ENABLED` | 浏览器推送，需 VAPID 及权限 |
| `REMINDER_WEBHOOK_ENABLED` | 对受信配置地址投递提醒 |

用量/审计保留 `TOKEN_USAGE_RETENTION_DAYS`、`OPERATION_RECORD_RETENTION_DAYS` 默认 0（永久）；未知写所有权不能靠清理解除。参考 [Redis](redis-extensions.md)、[提醒/MQ](reminder-stream-and-queue.md)、[安全策略](../SECURITY.md)。
