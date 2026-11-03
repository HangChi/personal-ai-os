# 独立 worker 与提醒广播

默认启动使用 combined 角色。独立运行时，API 接收业务请求，index 解析文档并生成向量，review 审查资料冲突，delivery 执行搜索投影和通知投递。各进程使用同一个发布 JAR 和 PostgreSQL。模型并发和限流使用数据库协调，配置的总并发大于 1 时至少为 API 保留一个并发位。

| 角色 | 业务端口 | 管理端口 |
| --- | --- | --- |
| API | 8080 | 9091 |
| index | 18083 | 18093 |
| review | 18084 | 18094 |
| delivery | 18085 | 18095 |

worker 绑定回环地址，业务入口返回 404。管理端口提供健康检查和 Prometheus 指标。每个 worker 使用独立 JVM 与连接池。API 副本各自管理浏览器会话，负载均衡时需维持会话亲和。

1. 构建发布 JAR，并在切换前备份数据库和附件。
2. 用同一 JAR 启动 API 和 worker。启动 API 时 Flyway 应用 V37–V41。
3. 检查角色状态与管理端口，再提交索引任务。

```bash
python3 scripts/dev/local-runtime.py stop
python3 scripts/dev/local-runtime.py start --jar /absolute/path/backend-release.jar --distributed
python3 scripts/dev/local-workers.py start --jar /absolute/path/backend-release.jar
python3 scripts/dev/local-workers.py status
```

`local-workers.py stop` 优雅关闭 worker。要切回单进程运行，先停止 worker 和 API，再用同一 JAR 启动 API，省略 `--distributed`。完成切换前应先确认正在执行的任务状态。

索引、冲突审查和搜索投影保留数据库任务。数据库事务写入唤醒 Outbox，RabbitMQ 发布确认后按版本延后下一次通知。消费者唤醒对应执行器，数据库轮询负责 broker 故障后的领取。索引与审查提交时检查租约，搜索投影成功后按版本和领取标识删除对应 Outbox 行。每套环境使用独立 RabbitMQ 虚拟主机和 OpenSearch 索引。

提醒变更发布到 `personalos.reminders.changed`。每个 API 实例用自己的临时队列订阅，收到变更后刷新本机 SSE，数据库快照补偿断线。网页中的“知道了”仍由提醒已读接口保存。广播使用 RabbitMQ 的 fanout 路由。[RabbitMQ 发布订阅说明](https://www.rabbitmq.com/tutorials/tutorial-three-java)

浏览器后台推送与通用 Webhook 独立领取投递。Webhook 启用时，在本地环境文件填写以下变量并重启相应进程。

```dotenv
REMINDER_WEBHOOK_ENABLED=true
REMINDER_WEBHOOK_URL=https://receiver.example/reminders
REMINDER_WEBHOOK_SECRET=<at least 32 characters>
```

Webhook 请求包含 `event=reminder.due`、投递 ID、提醒 ID、标题、来源类型和提醒时间。请求使用 `Idempotency-Key` 标识投递，用 `X-PersonalOS-Timestamp` 提供 Unix 秒数，签名头 `X-PersonalOS-Signature` 为 `sha256=<hex>`。接收端对原始正文计算 `HMAC-SHA256(secret, timestamp + "." + body)`，校验时间窗并按投递 ID 去重。网络故障、429 和 5xx 会退避重试，最多尝试 5 次。新目的地址从启用后到点的提醒开始发送。

运行记录可查看 `.cache/local-runtime/workers/` 下各角色日志和进程状态。主要指标包括 `personalos_work_signals_total`、`personalos_knowledge_jobs_active`、`personalos_knowledge_search_projections_total`、`personalos_reminders_webhook_deliveries_total` 和 `personalos_reminders_broadcast_received_total`。

## 验证记录

2026-11-03 在隔离数据库、RabbitMQ 虚拟主机和 OpenSearch 索引上运行了两个 API 实例及三个 worker。模型请求由本地模拟端点响应，Webhook 由本地接收器验签。

- 双 API 并发使用同一个幂等键只生成一条任务，独立索引进程完成索引。
- 审查进程推进资料审核状态，投递进程将 Outbox 写入实际 OpenSearch 索引。
- 两个 API 实例各自收到广播，SSE 同步到点和已读状态。
- Webhook 重试保留投递 ID，接收器验证签名。
- 全量后端回归通过 1135 项测试，覆盖率门禁通过。Surefire 在测试完成后的退出阶段超时回收测试 JVM，构建结果为成功。
- 临时进程、数据库、虚拟主机和搜索索引已清理。

本机已验证发布包位于 `.cache/local-runtime/releases/backend-workers-f801edf.jar`。现有实例切换时使用上面的独立运行命令。
