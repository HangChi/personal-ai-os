# 提醒实时同步与消息队列

适用于两版的职责说明；Java/Go 的运行入口见[独立 Worker](distributed-workers.md)与[Go 部署](../backend-go/deploy/README.md)。

## 已实现的提醒链路

数据库保存提醒和已读状态。页面连接 `GET /api/v1/reminders/events`，接收名为
`reminders` 的 SSE 事件，数据为完整的到点未读提醒数组，最多 500 条。
新连接和重连均取得当前快照，不需要持久化 SSE 游标；确认前 500 条后会继续取得后续提醒。
空数组也是有效快照，用于同步其它标签页已经确认或删除的提醒。

- 完整扫描默认每 10 秒运行，补齐新建、修改的任务或日程提醒。
- 仅有订阅者时，每秒共用一次数据库查询发现到点、已读和删除变化。各连接共享查询结果。
- 已物化的未来提醒自然到点时也会发送，无需再次扫描。上述间隔不构成硬实时保证。
- 状态没变化不重复发送，每 15 秒发送 SSE 注释心跳。每条连接最长 5 分钟，然后重连并重新认证。
- 最多 32 条连接，发送池也限制为 32 个任务；每个连接只保留最新快照，慢连接不会积压事件。
- SSE 故障时前端立即 HTTP 补查，并按 1、2、4、8、16、30 秒上限退避重连。
  故障或浏览器不支持 SSE 时每 30 秒补查，健康时每两分钟校准一次，切回前台立即补查。
- HTTP 响应晚于新 SSE 快照时不覆盖新状态；登出后清理连接、重连定时器、弹框和补查回调。
- 每次只展示一条弹框，只提供该条的“知道了”。成功或手动关闭后再显示下一条；关闭不标记已读。
  确认失败保留当前条供重试，队列内已被其它标签页确认的条目会跳过。

浏览器连接与用户已读不绑定 RabbitMQ ACK；MQ 可选，关闭时依靠数据库快照与轮询。
SSE 共享查询相比单个页面每 30 秒轮询会增加数据库读取频率，收益是更及时的展示和跨页面同步。
新连接补全快照、定期补查与数据库恢复共同兜底；不承诺网络故障期间立即送达。

配置：`AI_CALENDAR_SCAN_INTERVAL_MS`（默认 10000）、
`AI_CALENDAR_STREAM_CHECK_INTERVAL_MS`（默认 1000）。已有 `.env` 显式配置优先于默认值。
Web Push 的后台投递调度仍使用独立配置，SSE 只覆盖页面保持打开的场景。

新增监控指标：

- `personalos_reminders_stream_connections`：当前连接数。
- `personalos_reminders_stream_snapshots_total`：成功发送的快照数。
- `personalos_reminders_stream_failures_total{stage="read|write|capacity"}`：数据库读取、发送和容量失败。

## RabbitMQ 当前承担的工作

异步知识库上传或替换先将任务写入 `knowledge_index_job`，再向
`personalos.knowledge.ready` 发送任务 UUID。消费者校验 UUID 后唤醒执行器，由数据库原子领取
实际待执行任务。解析、切分、嵌入、索引写入、进度、重试和租约恢复均由数据库任务逻辑控制。
combined 在同一进程执行，分布部署由 index/review/delivery 独立 Worker 执行；API 角色不承担这些后台工作。

发布端等待 broker 确认；发送失败由每秒一次的数据库任务检查补偿。
通知队列持久化，消息最多保留 60 秒、最多 1000 条；业务任务自身另有限额。
消费者 ACK 表示“唤醒通知已处理”，不表示索引成功，也不表示用户已读。
因此当前通知过期不会删除实际任务，业务失败也不能单靠 MQ 死信队列判断。

## 当前分布职责

| 工作 | 当前实现 | 事实来源与补偿 |
| --- | --- | --- |
| 文档索引 | index 领取/解析/嵌入，combined 可内置执行 | knowledge_index_job、租约、版本和短事务；数据库轮询兜底 |
| 冲突候选审查 | review 独立配额/领取 | knowledge_review_job；旧版本结果拒绝提交 |
| 搜索投影 | delivery 消费投影 Outbox | 外部版本与条件 ACK；可从 PostgreSQL 重建 |
| Push/Webhook | delivery 独立渠道状态与有限重试 | 持久投递、去重、失败；不代替用户已读 |
| 多 API 提醒 | fanout，每实例独立订阅队列 | 收信后刷新本地 SSE，重连用数据库快照 |

数据库事务同步保存任务/Outbox。消费者幂等，外部未知写保留状态，不能按 broker ACK 宣告业务成功。无需对普通查询、缓存命中或每个聊天 token 增加 MQ。批量 OCR、任意后台 Agent 或新通知渠道不因已有 Worker 就自动具备；新增前需设计取消、预算、确认和恢复。

参考：[RabbitMQ 确认机制](https://www.rabbitmq.com/docs/confirms)、
[广播与独立订阅队列](https://www.rabbitmq.com/tutorials/tutorial-three-java)、
[死信机制](https://www.rabbitmq.com/docs/dlx)、
[浏览器 SSE](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events)。

## 验证记录（2026-11-02，Asia/Shanghai）

- 后端完整回归：1124 项通过，无失败、错误或跳过；行覆盖率 86.30%，分支覆盖率 70.32%，门禁通过。
- 前端：1151 项逻辑断言、195 项组件测试通过；类型、lint、格式及生产构建检查通过。
- 隔离数据库的真实 HTTP 用例覆盖：首帧补查、已读后空快照、未来提醒自然到点、重连补查、未认证拒绝。
- 前端测试覆盖断线退避、无效快照、轮询降级、HTTP/SSE 乱序、登出后晚到响应、逐条弹框及退场排队。
- 已更新本地运行版本，后端 JAR 为 `.cache/local-runtime/releases/backend-fab017f.jar`。
  通过 `http://127.0.0.1:8088` 实测匿名 SSE 返回 401、登录后返回 200、重连正常，首帧约 890 毫秒。
- 该次实际入口检查未读数为 0，未创建或确认用户提醒；新建与到点变化由隔离数据库集成测试验证。
  前端 21 个关联静态资源与新构建逐字节一致，后端健康为 UP，SSE 监控指标正常暴露。
- 旧 JAR 保留，前端切换前的归档保存在 `.cache/local-runtime/releases/frontend-before-stream-*.tar.gz`。
