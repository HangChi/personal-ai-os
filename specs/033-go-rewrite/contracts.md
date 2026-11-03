# 兼容契约与验收清单

## 契约来源

优先阅读固定基线的 Controller、DTO、Service、SQL 与测试，再核对前端实际消费。surface.md 是注解端点索引，不包括全部框架生成协议。动态/条件端点不能因默认关闭而删去。

## HTTP 与 JSON

- 保留 `/api/v1` 基路径、104 个注解映射的路径/方法/参数/请求头/consumes/produces。`ApiErrorController` 的映射包含可配置 `${server.error.path:/error}`，默认解析为 `/error`；不能把占位字符串作为真实 URL。
- 普通成功/失败信封：`{code,message,data,timestamp}`；成功码 `0`，默认消息 `OK`；错误 `data:null`；时间保持 UTC/ISO-8601 语义。
- ErrorCode 的 19 个符号逐个保持数值、中文文案和 HTTP 映射。特别注意 `CONTEXT_TOO_LARGE=41301` 的 HTTP 是 400，嵌入维度错误 50201 的 HTTP 是 500；不能根据业务码前缀猜状态。
- 保留 400/401/403/404/405/406/409/429/5xx 的区别，错误不包含 SQL、堆栈、内部路径、密钥或上游原始敏感响应。
- 字段沿用现有 camelCase；日期、UUID、消息数字 ID、状态/枚举、排序和大小写保持一致。
- 区分 `null`、字段缺失、空字符串与空数组；写请求中省略、明确清空和 PATCH 语义不能合并。
- Java String.length 使用 UTF-16 单元，strip/trim、Unicode 空白、大小写与 Go 的字节/rune 语义不同；标签、标题、正文长度、模型窗口估算、摘要和关键词必须以边界 fixtures 确认。
- SQL 分页与内存分页分别实现，不二次切片。越界页返回空列表但保留原页号和总数；offset 溢出不得返回 500。
- 上传使用真实 multipart；知识库/聊天附件/容器上限分别对齐，不能只在读完整请求后检查体积。文件下载保留 MIME、Content-Disposition、编码与文件名回退。
- 幂等头 `Idempotency-Key` 的可选性、作用域、摘要和重复回放根据各端点实现对齐，不对所有 POST 假定相同规则。
- SSE、Markdown/JSON 导出和附件原文不套普通数据包装。导入直接接收导出 JSON 文件原文，不新增一层 data。

## 认证和安全

- 未登录的 `/auth/me` 返回 HTTP 200；响应内容以 CurrentUserDto 和前端 auth.ts 为准，不因文档摘要把它改成普通 401 或空 body。
- 登录成功轮换会话，注销清除会话；登录锁定独立于模型调用限流，用户名错误也遵守当前密码校验时序。
- `/auth/csrf`、`X-CSRF-TOKEN`、登录/注销后 token 刷新、Cookie HttpOnly/Secure/SameSite、prod 启动约束全部保留。
- 共享会话的 Redis URI/TLS/DB/namespace 单独配置；不能与缓存或模型限流错误共用命名空间。切换会话格式时强制重新登录并写清说明，不声称能直接读取 Spring Session。
- MCP 的 CSRF 例外不意味着匿名绕过认证。匿名健康白名单与监控 profile 权限/独立端口分别验收。
- Request ID 白名单 `[A-Za-z0-9._-]{1,64}`，无效输入重新生成，异步执行中传播而不串到其他请求。
- Worker 的业务端口保持隔离；角色无效时启动失败，不静默降为 API。
- 生产反向代理头信任范围、HTTPS 与本地回环绑定遵循当前部署边界；不能默认把迁移开发进程暴露公网。

## 三类流式协议

### 旧 POST 对话流

`POST /chat/sessions/{conversationId}/stream`：兼容 start、citations、rag、delta、done、error，以及 comment 心跳、结束/错误时机、客户端取消和正文持久化。以实际发送器与前端 chat.ts 共同确认 payload，不能只复制 ChatStreamEvents 的部分 record。

建流前的参数/归属/认证错误仍返回 JSON 信封，建流后按事件错误协议返回。usage、引用快照、模型参数、思考预算、空内容和上游错误必须单独覆盖。

### 持久化 Run 流

`GET /chat/sessions/{conversationId}/runs/{id}/events?after=...`：SSE 外层事件名 `run`，携带完整运行快照。快照内 `events` 含单调 ID、事件名和 JSON 字符串 payload；不要把 payload 直接改成 JSON 对象。

运行状态包括 RUNNING、COMPLETED、WAITING_CONFIRMATION、WAITING_USER、INTERRUPTED、STOPPED；事件追赶、批量上限、终态收尾和归属校验按代码对齐。前端只重连 GET，不得重新提交 start。停止、重连、恢复和启动新运行是四种操作。

内部事件 reset/activity/delta/done/error/citations/rag 等以实际生产与消费为准。问题和 followups 在刷新后保留。等待回答/确认时不消耗队列，不把澄清答案当作危险操作授权。

### 提醒流

`GET /reminders/events`：事件名 `reminders`，payload 为未读提醒快照；心跳、初始快照、已读变更和断线补偿继续有效。RabbitMQ 广播只通知“有变更”，数据库仍是状态来源。两 API 实例必须各自收到变更，不能共享同一个竞争消费队列。

## 工具与模型协议

- 23 个内部工具和 4 个对外 MCP 工具见 surface.md；名称、描述、参数 JSON schema、必填/可选、校验、异常与返回格式均属于范围。
- 内部工具必须经过执行预算、进度/审计、幂等、确认与调用身份管理，不能直接把 Service 暴露给模型。
- 五类确认动作 DELETE_TASK、SCHEDULE_TASK、SET_PRIORITY、DELETE_CALENDAR_EVENT、MCP_TOOL_CALL 的处理器必须齐备；PENDING/CONFIRMED/CANCELLED/EXPIRED/FAILED 不得合并。
- 模型配置默认/档案/会话覆盖、OpenAI 兼容额外参数、Ollama、embedding、图片、语音、usage/token、探测与窗口证据分别验收。
- 加密数据需复现 ModelKeyConverter 的格式、算法、密钥派生和兼容读取；不能因 Go 成功存新密文而忽略旧密文。禁止记录明文测试生产密钥。
- MCP 客户端原始名与命名空间装配名、默认 CONFIRM、安全自动名单、连接错误降级、stdio/HTTP 生命周期和刷新保持一致。
- 对外 MCP 通过框架注册，未列入 104 个注解路由。默认 `/mcp` 可配置；只暴露 personal_list_tasks、personal_search_knowledge、personal_list_calendar、personal_list_memories，不转售内部或外部写工具。

## 数据、任务与基础设施

- V1–V42 的 SQL 全部保留：schema、CHECK/FK/唯一约束、UUID/序号、vector(1024)、HNSW cosine、JSON 与 BYTEA、事务锁和索引。
- 不能忽略运行/事件、交互队列、幂等/确认、计划、来源/冲突、索引/投影、模型并发/限流、唤醒信号、推送/Webhook/广播与缓存 generation 等非页面主表。
- 数据库租约 token/过期/续租/最终提交 fencing、SKIP LOCKED、版本条件删除/更新、独立事务边界要用多连接真实数据库测试。
- RabbitMQ publisher confirm 不等于业务完成。Outbox 写入与业务事务一致，确认后的信号版本处理、故障轮询和重复投递不产生重复写。
- OpenSearch 的重建、版本、删除、可用性降级；Redis 的四类用途及故障退路；本地/S3 存储和 namespace；关闭可选设施的基本模式均纳入测试。
- Webhook 按 Java 原始正文计算 HMAC，timestamp + "." + body，固定投递 ID 重试；测试 URL/重定向/超时/过期窗口/重试上限/非重试 4xx。
- Web Push 的订阅标识、VAPID 和加密由浏览器真实订阅回归，不能用普通 POST 模拟完成。

## 框架与运维端点补充

Actuator 的 `/actuator/health`、子健康路径、info、metrics 和监控 profile 的 prometheus 不是普通 Controller。需要列明每个 profile 的路径/端口/权限/数据脱敏要求，并提供 Go 对应实现；JVM 指标按 Go 等效指标另行记录。

Spring 自动提供的 JSON、校验、multipart、方法不匹配、内容协商、错误分派、Session 和调度装配均需 Go 显式实现或验证。95 个运行/配置钩子中有 13 个调度声明；没有 @Scheduled 的启动恢复、事件回调和显式线程池也仍在逐文件范围内。

## 最终验收证据

1. 全部请求/事件/工具契约 fixtures 与 Java/Go 差分报告。
2. Java 测试声明 → Go 测试/场景映射，包含权限、并发、超时、故障与 Unicode 边界。
3. 空库重放、基线库升级、备份恢复、密文/向量/附件可读写报告。
4. combined 与 API+三个 Worker、双 API、可选设施开/关/故障模式报告。
5. 前端所有页面和交互验收、现有 E2E/评估脚本适配及实际运行报告。
6. 上线暂停写入、停止 Worker、备份、切换、重新登录、回退核查与恢复演练报告。
