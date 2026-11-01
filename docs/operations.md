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
