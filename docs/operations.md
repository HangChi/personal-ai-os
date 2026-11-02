# 运维手册

## 生产部署

生产环境使用独立的 Compose 文件和卷，避免把本地数据库迁移成生产数据库：

```bash
cp .env.production.example .env.production
# 编辑 .env.production：DOMAIN、ACME_EMAIL、POSTGRES_PASSWORD、AUTH_PASSWORD
docker compose -f compose.production.yml config --quiet
docker compose -f compose.production.yml up -d --build
```

`compose.production.yml` 只把 80/443 发布给 Caddy。PostgreSQL、后端和前端只在内部网络通信；
Caddy 自动申请 HTTPS 证书，并将 `/api/*` 转发到后端。域名 DNS 必须先指向服务器，证书申请不会在本地开发中发生。
生产启动时会拒绝认证关闭、CSRF 关闭、非 Secure Cookie、默认数据库密码和弱登录密码。

> 面向「自己跑起来、自己维护」的场景。包含部署、备份恢复、数据库迁移回滚、
> 以及故障排查。设计取舍见 [architecture.md](architecture.md)。

---

## 1. 部署

### 1.1 整机跑（推荐给换机器 / 共用场景）

```bash
cp .env.example .env        # 填模型密钥
docker compose --profile app up -d --build
```

| 服务 | 地址 | 说明 |
|---|---|---|
| 前端 | http://localhost:8088 | nginx 托管静态文件并反代 `/api` |
| 后端 | http://localhost:8080 | 也可直接访问，便于调试 |
| PostgreSQL | localhost:55432 | 映射自容器内 5432 |

不加 `--profile app` 时只启动数据库 —— 日常开发仍用本机跑前后端，行为与之前一致。

**为什么前端容器不需要重新构建就能换域名**：前端访问的是相对路径 `/api/v1`，
后端地址由 nginx 决定。换域名只改 `nginx.conf` 里的 `proxy_pass`。

### 1.2 手动跑（不用 Docker）

```bash
docker compose up -d                       # 只要数据库
cd backend && ./mvnw spring-boot:run       # 后端 :8080
cd frontend && npm run build               # 产物在 dist/，交给任意静态服务器
```

### 1.3 ⚠️ 对公网开放前必须做的事

本项目默认是**本地单用户**，`AUTH_ENABLED=false`。直接暴露等于任何人可读写
你的任务、日程与知识库。

1. 使用上面的 `compose.production.yml`，不要用本地 `--profile app` 直接承载公网流量
2. `.env.production` 设置强数据库密码和至少 16 字节的 `AUTH_PASSWORD`
3. 让 DNS 指向服务器并由 Caddy 申请 HTTPS；生产启动会强制 Secure Cookie 与 CSRF
4. 不要给 PostgreSQL、后端或前端增加宿主机端口映射

---

## 2. 先看这里：出问题时看什么

```bash
# 服务是否活着（包含数据库与模型配置状态）
curl -s localhost:8080/api/v1/health | jq

# 有没有流卡住（>0 且不下降 = 有连接挂住）
curl -s localhost:8080/actuator/metrics/personalos.chat.streams.active | jq

# HTTP 指标（QPS / 耗时分布 / 错误率）
curl -s localhost:8080/actuator/metrics/http.server.requests | jq
```

日志里每一行都有 `[requestId]`，用户报错时让他提供响应头 `X-Request-Id`，
一条 `grep` 就能还原整次请求：

```bash
grep '79a2d36c4d99' backend.log
```

需要接 ELK/Loki 时用 `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` 切 JSON 日志，
无需改配置。

---

## 3. 备份与恢复

### 3.1 完整备份与验证

备份包含完整数据库（含模型配置、用量和审计）以及聊天附件；界面 JSON 导出只包含业务数据，不替代完整备份。工具不执行 `.env` 中的 shell 代码，不把密钥写入日志或元数据。需要 Python 3.9+、Docker；无需宿主机安装 PostgreSQL。

在无写入的维护窗口执行：本地运行时先停止后端；Compose 部署执行 `docker compose stop backend`，备份后再 `docker compose start backend`。数据库快照本身由 pg_dump 保证，但数据库与附件跨资源一致需要暂停应用写入；脚本不会擅自停服务。

```bash
./scripts/dev/backup-db.sh
# 输出 backups/personal-ai-os-日期-随机串.tar 及 .sha256，默认永久保留
BACKUP_RETAIN=30 ./scripts/dev/backup-db.sh
python3 scripts/dev/backup.py verify backups/具体文件.tar
python3 scripts/dev/backup.py drill backups/具体文件.tar
```

`verify` 验证外层 SHA-256、内部文件清单和逐文件哈希、pg_restore 目录；`drill` 额外在随机临时数据库执行完整恢复与关键表查询，再删除临时库，绝不接收运行库作为目标。目录验证不能替代恢复演练；建议每周执行一次 drill。业务库本身不受演练影响。

可设置 `BACKUP_DIR`、`DB_CONTAINER`、`BACKUP_UPLOAD_DIR`。附件路径默认 `backend/data/uploads`，也读取 `AI_CHAT_UPLOAD_DIR`。Compose 应用模式使用同一宿主机目录绑定，容器重建后附件仍保留；已有旧容器中的附件应先用 `docker cp` 拷出再切换挂载。如直接备份容器内目录，设置 `BACKUP_BACKEND_CONTAINER=personal-ai-os-backend`。目录不存在会明确失败，避免生成缺附件的成功备份。

备份使用同目录临时文件，完成后原子发布；默认不会删除旧备份。`BACKUP_RETAIN` 正数只清理本工具完整的 `.tar/.tar.age` 包及其校验文件，不清理历史 `.dump` 或 `pre-clean-*` 文件。设置 `BACKUP_COPY_DIR=/另一磁盘/目录` 会额外生成经过校验的副本；远程同步可由 NAS/同步工具接管该目录，不会自动上传。

可选加密（需先安装 age，并单独保管身份文件）：

```bash
BACKUP_AGE_RECIPIENT=age1你的接收公钥 ./scripts/dev/backup-db.sh
python3 scripts/dev/backup.py drill backups/具体文件.tar.age --identity /安全位置/identity.txt
```

加密时 SHA-256 针对最终密文，内部清单随归档一起加密。程序不会保存 age 私钥，不备份 `.env`；数据库中已启用静态加密的模型密钥还需要原来的 `MODEL_KEY_ENCRYPTION_KEY` 才能读取。务必独立保管这些配置。

定时备份示例（仅示例，不自动修改 crontab；须与应用维护窗口配合）：

```cron
0 3 * * * cd /path/to/personal-ai-os && ./scripts/dev/backup-db.sh >> /tmp/paios-backup.log 2>&1
```

### 3.2 实际恢复

先成功执行 `verify` 和 `drill`。停止后端，另存当前状态，解开经过验证的包；加密包先使用 `age --decrypt -i /安全位置/identity.txt -o /tmp/backup.tar 备份.tar.age` 解密。实际恢复会替换数据，应由维护者针对选定的备份和目标库执行。

```bash
mkdir -m 700 /tmp/paios-restore
# 以下路径应替换为已经验证的真实文件
# tar -xf backups/具体文件.tar -C /tmp/paios-restore
# docker exec -i personal-ai-os-postgres pg_restore -U personalos -d personal_ai_os \
#   --clean --if-exists --exit-on-error --no-owner --no-privileges < /tmp/paios-restore/database.dump
```

附件应与同一份归档的 `uploads/` 一起恢复到 `backend/data/uploads/`，先把当前附件目录移到另一个位置保存，再放入归档附件，不要混入后来新增的孤儿文件。校验任务、消息、模型配置与用量的条数和附件访问后，再启动后端。演练只验证数据库能完整恢复、附件哈希正确；实际浏览器与模型连通性仍需恢复后确认。

旧 `.dump` 依然可以直接用 `pg_restore` 恢复，但不含附件与新格式校验清单。

---

## 4. 数据库迁移的回滚

### 4.1 先说结论：本项目的回滚策略是「向前兼容 + 备份恢复」，不是 undo

Flyway **社区版不提供 undo**（`flyway undo` 是付费功能）。因此指望「迁移写错了
一键回退」在这里不成立。真正可靠的做法只有两条，且都在**写迁移时**决定：

1. **只做加法**：加表、加列（可空或带默认值）、加索引、加约束（先 `NOT VALID`）。
   不删列、不改类型、不改语义。这样旧版本代码在新 schema 上仍能运行 ——
   回滚就只是「把旧镜像跑起来」，不需要动数据库。
2. **破坏性变更分两步走**：先把代码改成不再使用该列（一个版本），
   确认没问题后，下一次迁移再删。

### 4.2 什么时候必须用备份恢复

出现下列情况时，「前向兼容」救不了你，只能恢复到备份点：

- 迁移里写了 `DELETE` / `UPDATE` 且逻辑有误
- `DROP COLUMN` / `ALTER TYPE` 已经执行、且旧代码仍在使用它
- 数据被误删

步骤见 3.2。**恢复前先 `docker compose stop backend`**，否则恢复过程中新写入
的数据会与快照冲突。

### 4.3 迁移失败卡住启动时

Flyway 会在 `flyway_schema_history` 里留下一条 `success = false` 的记录，
此后每次启动都报 `Found failed migration`。

```bash
# 1. 先确认失败的迁移到底执行到哪一步（不要盲目 repair）
docker exec personal-ai-os-postgres psql -U personalos -d personal_ai_os \
  -c "select version, description, success from flyway_schema_history order by installed_rank desc limit 3;"

# 2. 如果确认那次迁移没有留下半成品对象，清掉失败记录后重启
docker exec personal-ai-os-postgres psql -U personalos -d personal_ai_os \
  -c "delete from flyway_schema_history where success = false;"

# 3. 如果留下了半成品（表建了一半），先手工清理干净再重复第 2 步
```

**永远不要**在失败记录还在时直接改迁移文件内容 —— Flyway 会校验 checksum，
改完启动报 `Migration checksum mismatch`，问题更大。要么前进（新增一个修正迁移），
要么按上面的步骤回到干净状态。

### 4.4 当前迁移链

`V1` 扩展 → `V2` 对话 → `V3` 任务/确认/操作记录 → `V4` 知识库（含 HNSW 索引）
→ `V5` 泛化确认表 → `V6` 改主键 → `V7` 日程 → `V8` MCP 动作 → `V9` 记忆
→ `V10` 任务标签 → `V11` 引用快照 → `V12` 会话摘要 → `V13` 记忆来源
→ `V14` 消息附件。

已应用的迁移**只追加、绝不改写**。需要改结构时新增一个迁移。

---

## 5. 故障排查

| 现象 | 先查什么 |
|---|---|
| 前端 502 | 后端是否在 `:8080`；`docker compose ps` 看 backend 是否 unhealthy |
| 对话「永远转圈」 | `personalos.chat.streams.active` 是否 >0 不降；反向代理是否关掉了 buffering |
| 流中途断开 | 代理的 `proxy_read_timeout` 是否 > 120s；本应用每 15s 发心跳，收不到心跳就是链路断了 |
| 请求 429 | 模型调用限流（默认 60 次/分钟）。批量导入时 `APP_RATE_LIMIT_ENABLED=false` |
| 启动报数据库连接失败 | 容器内要用 `POSTGRES_HOST=postgres`、端口 `5432`；宿主机才是 `localhost:55432` |
| 启动报 `AI_NOT_CONFIGURED` | `AI_CHAT_PROVIDER` 未设；只想关聊天就设 `none`（但**不能**把 `AI_EMBEDDING_PROVIDER` 设为 none，向量库需要它） |


## 6. 数据保留与模型密钥

`TOKEN_USAGE_RETENTION_DAYS=0` 与 `OPERATION_RECORD_RETENTION_DAYS=0` 默认永久保留调用用量和操作记录。原先操作记录硬编码的 30 天自动删除已取消。设为正整数后，每天 04:30 按创建时间批量清理早于阈值的记录；恰好等于阈值的记录保留。此操作不可逆，启用前先备份；保留期之外的幂等键可被重新使用。测试不加载真实 `.env`，不会因本机认证/模型配置而污染结果。

模型数据库密钥可选 AES-256-GCM 加密：生成 32 字节随机主密钥并用 Base64 编码，设置 `MODEL_KEY_ENCRYPTION_KEY`，重启后在设置页逐个重新保存模型配置。旧明文仍可读取，保存时转换为密文；脚本不会批量重写当前配置。留空保持当前行为。主密钥必须单独保管，勿写入数据库、提交 Git 或随备份一起保存。丢失/更换主密钥会导致已有密文无法读取；换钥前使用旧钥导出/迁移配置，或在可恢复的备份基础上重新录入模型密钥。环境变量本身不由此功能加密。

## 7. 默认关闭的 Web Push 后台提醒

本地页面打开时，登录后的所有功能页都会轮询提醒，每个标签页内去重，切换功能页不再停止。后台推送是独立的可选通道；默认 `WEB_PUSH_ENABLED=false`，不影响现在的本地使用。

启用步骤：

1. `python3 scripts/dev/generate-push-keys.py /安全位置/web-push.env`，生成 VAPID 公私钥（输出文件权限 0600，不覆盖已有文件，终端不显示私钥）。
2. 将文件里的公私钥配置为 `WEB_PUSH_PUBLIC_KEY` / `WEB_PUSH_PRIVATE_KEY`，加上 `WEB_PUSH_SUBJECT=mailto:你的联系邮箱` 和 `WEB_PUSH_ENABLED=true`，重启后端。不要提交这些值。
3. 在日程页点击「订阅后台推送」，允许浏览器通知。每个浏览器/设备分别订阅，也可在同处取消。页面不会自动申请通知权限。

Service Worker 仅负责显示提醒及点击跳转，不缓存业务接口/页面。需要 localhost 或 HTTPS 安全上下文；普通局域网 HTTP 地址不支持后台推送。关闭页面后仍需后端运行、网络可用、推送平台和浏览器/系统允许后台通知；完全退出浏览器、系统限制后台或拒绝权限时不保证送达。VAPID 变更后需重新订阅，页面会提示并重建订阅。

每个订阅/提醒持久化一次投递，成功不会重复排队；网络失败、429 和 5xx 最多重试 5 次（60/120/240/480 秒退避）；404/410 删除失效订阅，其他 4xx 记录失败。平台接受不等于用户已读，推送不会替用户标记已读。重启可从 180 秒租约恢复中断投递；已读/取消提醒不再领取，仅发送订阅后产生且最近 24 小时内的到点提醒，避免开启后通知风暴。崩溃发生在平台接受后、成功状态落库前时可能重投，同一提醒 tag 可合并通知。每浏览器的历史送达记录保留 30 天。

推送平台默认允许 Chrome/Firefox/Edge/Safari 官方端点，禁止任意 HTTP 地址或重定向。后台网络使用单独调度线程和明确超时，不阻塞日程扫描。

## 8. 大数据量与模型调用指标

界面 JSON 导出现在通过流式响应、每批 200 条读取；会话消息、单篇文档分块也分批导出，释放 Hibernate 已读取实体。所有查询在异步响应线程的 PostgreSQL repeatable-read 只读事务中共享快照，计数来自实际写出的元素。字段和 formatVersion=1 保持兼容，counts 出现在数组之后。完整恢复仍请用数据库与附件备份；导出过程中断会留下不完整 JSON，应重新下载。

Micrometer `/actuator/metrics` 新增：

- `personalos.chat.duration`：按模型适配器 provider、模型名、success/error/timeout/cancelled 统计次数与耗时分布。
- `personalos.chat.tokens`：按模型、ACTUAL/ESTIMATED、input/output 统计 token。
- `personalos.chat.streams.active`：当前活跃流。

不把端点、密钥、会话 id 写入指标标签；价格没有可信来源，未生成费用金额。指标为进程累计，重启会归零，历史用量仍保存在数据库。

## 9. 可选历史监控

生产环境叠加监控配置（在 `.env` 或 shell 设置 `GRAFANA_ADMIN_PASSWORD`，不要提交密码）：

```bash
docker compose -f compose.production.yml -f compose.monitoring.yml up -d --build
```

Grafana 位于 `http://127.0.0.1:3000`，默认用户 admin，密码取环境变量；远程访问可使用 SSH 隧道。
自动预置「Personal AI OS 运行状态」看板，显示首段正文 P95、完整回复 P95、活跃流、HTTP 速率、连接等待与堆内存。
首段正文计时包括检索和上下文准备；没有正文的失败请求不会进入该指标。
Prometheus 每 15 秒从 backend:9091 采集，数据最多保留 15 天或约 2GB；Grafana 配置和数据使用独立卷。
管理端口不发布到宿主机，Caddy 继续屏蔽 `/actuator/*`。主业务端口保持原有认证。
不使用叠加文件时不启用独立管理端口；原 `/actuator/metrics` 行为保持兼容。

### 后台对话准入与事件流

后台 `/runs` 的启动、恢复、回答后继续及排队执行，在 Service 层取得模型令牌桶额度与共享并发许可。
许可随模型执行结束、异常或停止释放，不随发起请求的 HTTP 响应释放；同幂等键重试只读取原执行。
旧同步模型端点仍通过 HTTP filter 使用同一个并发上限。
正文增量最多缓冲 100ms 或 2048 字符，控制事件与终态先刷完正文，再持久化。
浏览器通过 `GET /api/v1/chat/sessions/{conversationId}/runs/{id}/events?after={cursor}` 读取 SSE，
断线后只重连读取，后台任务继续运行。数据库保留事件与单调游标；无事件时发送心跳并每 2 秒兜底检查状态。

### 嵌入缓存的观测与启用

默认 `EMBEDDING_CACHE_ENABLED=false`：只保存有界、过期的文本/选项摘要，统计重复比例，模型仍照常调用。
在看板观察真实使用的「重复嵌入观测比例」和 `personalos.embedding.duration` 后，再决定是否开启。
设置 `EMBEDDING_CACHE_ENABLED=true` 并重启后，Caffeine 保存成功的向量；默认最多 1000 条、10 分钟过期，
可用 `EMBEDDING_CACHE_CAPACITY`、`EMBEDDING_CACHE_TTL` 调整。没有实际使用数据时不预设收益或自动开启。
缓存按模型实例及完整请求选项隔离，重启清空；失败不缓存，返回向量为副本；批量请求仅对缺失项调用模型。
缓存不覆盖聊天回答、知识库检索结果或人工确认状态，因此修改文档后检索仍读取最新内容。
看板中的观测重复率与真实命中率是两个指标，不应混算；缓存后的响应 usage 仅反映实际发起的嵌入调用。

### 知识库数据库后台任务

知识库页面的上传与替换现在先入队，再展示解析、嵌入、保存三个阶段；提交成功不代表索引完成。
离开页面或重启服务不会丢失任务，重新打开文档页面可查看最近 20 个任务。原同步接口仍保持兼容。

- `POST /api/v1/knowledge/jobs`：multipart `file`、可选 `title`、替换时传 `documentId`；返回 202。
  `Idempotency-Key` 是 UUID，相同键和相同文件只建立一项任务，不同内容返回冲突。
- `GET /api/v1/knowledge/jobs` 和 `GET /api/v1/knowledge/jobs/{id}`：查询阶段、状态与尝试次数。
- 最多 20 项待处理任务、单实例最多 2 个索引 worker，并受全局模型并发上限和令牌桶约束。
- 60 秒租约，每 10 秒续期；进程异常后新实例重新领取过期任务。最终提交校验领取令牌与租约，旧 worker 无权回写。
- 连接或写入故障最多尝试 3 次，间隔至少 5 秒；文件验证错误直接失败。失败信息不包含上游原始报文。
- 文件输入临时保存在数据库，成功或最终失败后清除；终态任务记录保留 7 天。数据库备份会包含尚未处理的输入。
- 替换时旧文档保持可检索，直至新向量和成功回执一起提交。原文档在任务完成前被删除时不重新创建。
- `app.knowledge.jobs.enabled=false` 可停止自动领取；已接受的任务继续保留，重新启用后处理。

指标新增 `personalos.knowledge.jobs.pending/active/attempts/duration`，看板可查看等待数与尝试结果。
全局额度暂满时，聊天跟进队列和启动恢复会保留进度，后续调度继续尝试，不因一次限流丢弃。

### 宿主机 JAR 的本地监控

若数据库运行在 Docker、后端直接运行在 macOS 宿主机，可使用独立本地监控栈，不必启动生产 Compose。

后端使用已构建的 JAR，并开启独立回环采集端口：

```bash
java -jar backend/target/personal-ai-os-backend-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=monitoring --management.server.address=127.0.0.1
```

首次启动需在被 Git 忽略的 `.env.monitoring.local` 中保存 `GRAFANA_ADMIN_PASSWORD`（建议随机生成并设文件权限 600），然后运行：

```bash
docker compose --env-file .env.monitoring.local -f compose.monitoring.local.yml up -d
```

此配置面向 Docker Desktop，通过 `host.docker.internal:9091` 采集宿主机后端；不修改或重建原数据库。
Grafana：`http://127.0.0.1:3000/d/personalos-runtime`，用户 admin，密码在本地环境文件中。
Prometheus：`http://127.0.0.1:9090`，Targets 中 personal-ai-os 应显示 UP。
停止监控可将上述 `up -d` 改为 `down`；不加 `-v` 时保留历史指标与看板数据。

## 可选 Redis 共享嵌入缓存

`compose.integrations.yml --profile cache` 提供仅回环暴露、有密码与 128MB 上限的 Redis。
默认不开启。设置 `REDIS_ENABLED=true`、`REDIS_URI`（可用 rediss TLS）、唯一的 `REDIS_NAMESPACE`，
并明确启用 `EMBEDDING_CACHE_ENABLED=true` 后，查找顺序为 Caffeine → Redis → 模型。
Redis 只保存文本/选项的摘要与向量，命名空间按用户和部署隔离；模型端点、名称和请求选项参与隔离。
模型服务在同名模型下更新时应修改 `app.embedding-cache.revision`，防止复用旧空间向量。
Redis 故障在短超时后回到上游模型，错误记录在 `personalos.embedding.redis.errors`；共享命中标记为 shared_hit。

## 可选 RabbitMQ 任务通知

`compose.integrations.yml --profile queue` 启动 RabbitMQ 4.4.0，AMQP 与管理端口仅绑定回环地址。
设置 `RABBITMQ_ENABLED=true`、包含应用账户的 `RABBITMQ_URI`；默认队列 personalos.knowledge.ready。
任务先写数据库，再向持久队列发送 UUID，等待发布确认（有超时）；不可路由、连接失败均记录 poll_fallback，仍返回已接受的数据库任务。
消费者只唤醒数据库 worker，重复通知不会绕过原子领取或租约；ACK 只代表通知已处理，不代表索引完成。
消息最多保留 60 秒/1000 条，通知过期仍由数据库轮询恢复；文件和模型密钥不进入消息。

## 可选私有 S3 附件存储

`compose.integrations.yml --profile storage` 使用 SeaweedFS 4.51，端口 8333 仅绑定回环。
在本地环境文件设置随机 `S3_ACCESS_KEY`、`S3_SECRET_KEY`；mini 自动创建 `S3_BUCKET`。
应用设置 `S3_ENABLED=true`、端点、区域、bucket、唯一 `S3_NAMESPACE` 和凭据。
未指定静态凭据时采用 AWS 默认凭据链；生产端点使用 HTTPS。
附件经过应用认证读取，不生成公开链接；对象键为命名空间摘要和 UUID。旧本地附件优先读取，新附件写对象存储。
故障明确失败，不静默改写到另一位置。`S3_CLEANUP_ENABLED` 默认关闭；开启后每轮最多扫描 100 项，
只删除本命名空间、带归属标记、超过 24 小时且数据库未引用的对象。不同数据库使用不同命名空间。
数据库和对象卷需配套备份。真实容器验证了上传、读取、大小、归属隔离、清理和旧文件兼容。

## 可选 OpenSearch 混合召回

`compose.integrations.yml --profile search` 提供 OpenSearch 3.9.0，单节点、512MB JVM 堆，9200 只绑定回环。
本地配置关闭安全插件；生产需单独配置 TLS、认证与网络隔离，不要将本地端口配置改为公网绑定。
设置 `OPENSEARCH_ENABLED=true` 后，新 RAG 管线使用 CJK/BM25 和 HNSW 向量两路召回，以 RRF 融合排名。
PostgreSQL 保存原文与向量，触发器在同一业务事务写 chunk outbox，异步投影使用递增外部版本和条件 ACK。
删除也进入 outbox，旧通知不能覆盖更新后的索引；外部结果仅提供 ID，正文回 PostgreSQL 校验。
搜索服务故障或 outbox 尚未清空时退回 PostgreSQL 召回。`GET /api/v1/knowledge/search-index` 查看积压；
`POST /api/v1/knowledge/search-index/reindex` 排队重建当前所有片段。换向量维度应使用新索引名。
索引是可重建投影；删除索引卷不会删除 PostgreSQL 资料。`personalos.knowledge.search.pending/projections` 记录积压与同步结果。

## RAG 管线与资料冲突治理

默认启用 `app.knowledge.rag.enabled`：保留原问题，再生成至多两个改写；每路最多 40 个候选，RRF 融合后重排，
对多来源片段精审，再截取最终结果。辅助调用使用当前配置的无工具模型，不读对话历史；两条工作线程、四项等待上限，
改写和重排单次最多 8 秒，证据精审默认最多 12 秒（`RAG_EVIDENCE_TIMEOUT_MS`），辅助阶段共享默认 20 秒预算。没有资料时不调用生成模型。关闭该配置可回到原检索行为。
重排不可用时保留 RRF；精审失败返回 UNVERIFIED，展示资料而暂停确定结论。返回查询、阶段耗时、回退原因，
指标为 `personalos.knowledge.rag.stage/results`。这套测试证明链路和保护行为，不代表真实语料准确率已有提升。

新增资料自动具有默认知识库与来源记录；在「资料治理」中设置知识库、版本、适用范围、生效日期、截止日期、发布方和状态。
发布方为用户填写，不由模型授予权威。SUPERSEDED 资料不进入新管线。日期不重叠的说法请求澄清，不直接判作冲突。
后台按片段增量审查相关候选，状态包括 PENDING、PARTIAL、CHECKED_CANDIDATES、UNAVAILABLE；
CHECKED_CANDIDATES 只表示候选审查完成，不表示穷尽知识库或不存在冲突。失败五分钟后重试，可手动重新排队。

冲突保留双方文档、具体片段、原文摘录与版本。模型只能提出待审查分歧；原文摘录不匹配时拒绝结果。
提问时先核对多个来源，已记录分歧会补入另一方，避免 topK=1 隐藏矛盾。CONFLICT / CLARIFY 时不生成唯一答案，
而是并列引用并请求补充对象、时间或版本；普通知识增强对话采用相同保护，并持久化提问、答复和引用。
人工可保留双方、确认不是冲突，或填写理由后选定来源；决定仅作用于具体证据及当前版本，留存审计。
修改资料后旧决定标为 STALE，重新复核；系统不按相似度或上传时间自动覆盖资料。

可选真实 cross-encoder：设置 `RAG_RERANKER_ENABLED=true`、`RAG_RERANKER_ENDPOINT`（完整 rerank URL）、
`RAG_RERANKER_MODEL` 与密钥，使用 Cohere 兼容的 query/documents/results 协议。
只有显式启用才向该端点发送问题与候选原文；生产使用 HTTPS。超时或结果编号/分数非法时退回 RRF。
`rankingScores` 分开返回 fusion 和 rerank，原 `hit.score` 保持向量余弦口径；这些分数不表示事实真实性。
导出现在保留知识库名称与来源适用条件，恢复时重新排队审查；冲突处理审计需通过 PostgreSQL 备份保留。
OpenSearch 每次启用启动会重新排队当前片段，避免新索引或恢复后的索引被误判为空且已同步。

基础设施 Compose 的 cache / queue / storage / search 可分别启用。只有启动对应服务时才校验它的凭据；
例如 search profile 不要求 Redis、RabbitMQ 或 S3 密码。启用缓存和队列时，应用 URI 也必须包含相同的账号密码。

### 宿主机本地发布

`python3 scripts/dev/local-runtime.py status|stop|start --jar 发布包路径` 管理本项目 JAR。
它读取 `.env` 和权限为 600 的 `.env.integrations.local`，不执行环境文件中的 shell 文本，
凭据仅通过子进程环境传入；业务和管理端口绑定回环，继续保留登录认证。
发布包放入 `.cache/local-runtime/releases/`，日志和非敏感进程状态保存在 `.cache/local-runtime/`。
先完成构建，再停止后端进行数据库与附件一致备份、verify/drill，最后启动新包。
原 `backend/target/` JAR 保留；回退时停止新进程，指定原 JAR 并传 `--without-integrations`。
工具只按 PID 和 JAR 身份停止本项目进程，不强制杀死停止超时的进程。

百炼原生重排支持 `RAG_RERANKER_PROTOCOL=dashscope`；请求采用 input/parameters，
结果取 output.results。`cohere` 仍为兼容默认协议。模型和可用性以实际服务端响应为准。

本地前端可用 `npm --prefix frontend run build` 后执行
`docker compose -f compose.local-web.yml up -d`，访问 `http://127.0.0.1:8088`。
它只托管静态产物并代理宿主机 8080 的 `/api/*`，不代理管理端口。
这套配置面向 Docker Desktop 的本机使用，不替代公网生产配置。

### 启用 S3 后的备份

备份工具同时读取 `.env` 和 `.env.integrations.local`。启用 S3 后按当前命名空间枚举对象，
校验 UUID、归属标记和大小，将远端附件内容一起写入归档的 `uploads/`，旧本地文件保持读取优先级。
对象读取、分页或校验失败时不发布备份；仍需先停止应用写入以保证数据库和附件一致。
恢复出的 `uploads/` 可按原流程放回本地目录，应用优先读取这些文件，无需先向 S3 重传。
只读快照支持静态凭据和 AWS 环境临时凭据；仅配置 IAM 角色而无可读备份凭据时会明确失败，不能静默漏掉对象。
