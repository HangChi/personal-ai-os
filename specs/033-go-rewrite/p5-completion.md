# P5 记忆、日历与提醒投递

固定 Java 基线为 `cb5d694d62fa93742b5789b97273e76d931ce335`。显式运行 `personalos --foundation --migrations /absolute/java/db/migration --p5` 可启用 P5。现有 Flyway 迁移提供全部业务表。Go 启动只读校验历史和运行账号权限。

| 任务 | 实现 | 验收 |
| --- | --- | --- |
| T500 | 记忆 CRUD、来源、置顶、哈希唯一键、词面/向量排序、同模型近重复替代、注入预算和十分钟命中刷新 | 真实 PostgreSQL 并发去重、替代与置顶转移、命中合并。认证 HTTP 和实际 Agent `saveMemory` 链路 |
| T501 | 轮末自动沉淀、限条、查询向量超时降级、更新作废和启动分批回填 | 固定 Java 事实解析对照、模拟模型轮末写入、存量多批回填 |
| T502 | 日程 CRUD、区间分页、日历时区、每日/每周/工作日/每月展开和删除确认 | 378 组固定 Java 对照覆盖时区、DST、月底、截止和远期快进。认证会话工具创建日程，待确认期间保留日程，用户确认后删除 |
| T503 | 扫描任务与日程、前瞻和回看窗口、待提醒快照刷新、物化唯一键、已读、手工扫描 | 真实 PostgreSQL 重扫去重、错过窗口补偿、任务完成和改名、日程编辑保留已读历史、首次已读时间 |
| T504 | 提醒流快照、15 秒心跳、有界连接、共享查询、轮询补偿和 RabbitMQ fanout | 两个 Go API 实例分别订阅同一交换机。长轮询间隔下，两个 SSE 连接收到到点和另一实例的已读变更 |
| T505 | 订阅校验、VAPID、`aes128gcm`、过期删除、投递领取、重试和恢复 | 真实 Chrome PushManager 生成 FCM 订阅，Go API 写入并实际投递，Chrome Service Worker 解密收到内容。PostgreSQL 双池验证过期租约、旧尝试写入保护、重试上限及 410 级联 |
| T506 | Webhook 目标启用时间、24 小时窗口、HMAC、稳定 ID、禁止重定向、领取和退避、尝试上限与保留期 | 回环 HTTP 验证签名和稳定 ID，429 后新实例领取到期租约，307 未访问重定向目标 |
| T507 | 固定 Java 对照、真实基础设施回归、测试声明职责映射 | 见 [P5 测试映射](p5-test-mapping.md) |

## 复验

- `cd backend-go && go test -race -count=1 ./... && go vet ./... && go mod verify`
- `python3 scripts/migration/java_p5_fixture.py` 对照 378 组重复规则、24 组事实解析和 6 个 Spring AI 工具定义。
- `python3 scripts/migration/run_go_postgres_tests.py --package app --tls --run '^TestP5'` 在随机回环端口和真实 Flyway 初始化的临时 PostgreSQL 上运行。传入随机回环端口上一次性 RabbitMQ 的 `GO_TEST_REMINDER_RABBIT_URI` 时启用双实例广播场景。
- `python3 scripts/migration/run_go_postgres_tests.py --package calendar --tls --run '^TestP5WebPushRecovery'` 验证并发接管与投递状态。
- `node scripts/migration/chrome_p5_web_push.mjs` 使用一次性 Chrome profile 和回环 Service Worker。Chrome 路径可通过 `CHROME_PATH` 指定。需要访问真实浏览器推送平台。
- Java 原有 14 类共执行 156 个用例，失败、错误和跳过均为 0。此轮使用 Maven 离线定向测试，JaCoCo 全量覆盖门禁由后续发布阶段执行。

## 运行边界

日历使用进程本地时区，与原 `ZoneId.systemDefault()` 对齐。DST 按原规则处理，全天同日补足使用原有的 24 小时约定。记忆写入向量前的模型调用设 10 秒上限，查询读取配置的超时。Go 使用行级更新保留并发编辑，并在记忆已被取代时跳过新一轮替代判定，避免编辑旧记忆时形成循环关系。

广播和投递按运行角色启用。API 订阅广播并保留数据库轮询。允许 `delivery` 的角色发送 RabbitMQ、Web Push 和 Webhook。所有私密端点和密钥只用于配置和实际投递。Web Push 启动时校验 VAPID 公私钥匹配。新租约接管验证以两个组件和预置到期时间执行。

记忆和日历的跨模块搜索/导出接线归 P7。P6–P8 与全量发布门禁仍开放，`/readyz` 仍为 503，prod 保护继续生效。
