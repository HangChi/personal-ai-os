# 运维手册

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

1. `.env` 设 `AUTH_ENABLED=true` 与强密码（密码未设时应用会**启动失败**，这是刻意的）
2. 关掉 `management.endpoint.health.show-details: always`
3. 不要暴露 `/actuator/*`（`env`/`beans` 会把配置原样吐出来）
4. 前面套一层 HTTPS（反向代理终结 TLS）
5. 用 `POSTGRES_PORT` 之类把数据库端口从公网撤掉

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

### 3.1 备份

```bash
./scripts/dev/backup-db.sh                 # 默认保留最近 14 份，输出到 ./backups
BACKUP_RETAIN=30 ./scripts/dev/backup-db.sh
```

建议加进 crontab：

```cron
0 3 * * * cd /path/to/personal-ai-os && ./scripts/dev/backup-db.sh >> /tmp/paios-backup.log 2>&1
```

用 `pg_dump -Fc`（自定义压缩格式）：体积约为纯 SQL 的 1/5，且 `pg_restore`
支持**只恢复某几张表**——真出事时这比全量恢复实用得多。

### 3.2 恢复

```bash
# 停后端，避免恢复期间有写入
docker compose stop backend

# 全量恢复（--clean 会先删对象再建，适用于「回到某个时间点」）
docker exec -i personal-ai-os-postgres \
  pg_restore -U personalos -d personal_ai_os --clean --if-exists < backups/personal-ai-os-20261027-030000.dump

# 只恢复某张表（例如误删了任务）
docker exec -i personal-ai-os-postgres \
  pg_restore -U personalos -d personal_ai_os --data-only --table=task < backups/xxx.dump

docker compose start backend
```

恢复后校验：

```bash
docker exec personal-ai-os-postgres psql -U personalos -d personal_ai_os \
  -c "select count(*) from task; select count(*) from chat_message;"
```

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
