# Quickstart: 阶段 4 验证场景

> 只有**实际执行过**的项才能标 ✅。未执行一律 ⏳。

## 场景 A — 自动化测试（对应 SC-001~SC-010）

```bash
cd backend && ./mvnw -B verify
```

**验证点**（全程无网络、无外部服务、**无 `Thread.sleep`**）：
- 重复规则是纯函数（同输入两次计算逐元素相等）（SC-001）。
- `WEEKDAYS` 在 30 天区间内零周末（SC-002）；`MONTHLY` 跨 12 个月每月恰好一次（SC-003）。
- 扫描 5 次后 `reminder` 行数与 1 次相等（SC-004）；重建扫描器后再扫行数不变（SC-005）。
- 任务完成后针对该任务的新提醒数为 0（SC-006）。
- 删除日程后该日程提醒为 0（SC-007）。
- 同一 `(source_type, source_id, occurrence_at)` 行数恒为 1（SC-008）。
- 非法输入 `calendar_event` 变化 0 行（SC-009）。
- 阶段 2/3 的既有测试**全部保持通过**（前置重构无回归）。

## 场景 B — 真机 REST 冒烟（不依赖模型）

```bash
B=http://localhost:8080/api/v1

# 建一条「每工作日 09:00」的重复日程，提前 1 分钟提醒
curl -s -X POST $B/calendar/events -H 'Content-Type: application/json' -d '{
  "title":"每日站会",
  "startsAt":"2026-10-15T09:00:00+08:00",
  "endsAt":"2026-10-15T09:15:00+08:00",
  "recurrenceType":"WEEKDAYS",
  "reminderLeadMinutes":60
}'

# 区间查询：应看到展开后的多个发生实例，且都不落在周末
curl -s "$B/calendar/events?from=2026-10-15T00:00:00%2B08:00&to=2026-10-29T00:00:00%2B08:00"

# 手动触发一次扫描（无需等调度周期）
curl -s -X POST $B/reminders/scan

# 看待提醒列表（dueOnly=false 可看到「接下来会提醒什么」，便于验证物化是否生效）
curl -s "$B/reminders?dueOnly=false"

# 标记已读（幂等：重复调用仍是 200）
RID=$(curl -s "$B/reminders" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print(d[0]["id"] if d else "")')
[ -n "$RID" ] && curl -s -X POST "$B/reminders/$RID/read" && curl -s -X POST "$B/reminders/$RID/read"

# 删除日程 → 提醒必须归零
EID=$(...); curl -s -X DELETE "$B/calendar/events/$EID"
docker compose exec -T postgres psql -U personalos -d personal_ai_os \
  -c "SELECT count(*) FROM reminder WHERE source_id = '$EID'"    # 期望 0
```

## 场景 C — 任务也能提醒（对应 FR-013/FR-014）

```bash
# 建一个「截止时间在 1 分钟内」的任务
TID=$(curl -s -X POST $B/tasks -H 'Content-Type: application/json' \
  -d "{\"title\":\"交周报\",\"dueAt\":\"$(date -u -v+1M +%Y-%m-%dT%H:%M:%SZ)\"}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])')

curl -s -X POST $B/reminders/scan
curl -s "$B/reminders?dueOnly=false"      # 期望出现 sourceType=TASK 的提醒

# 标记任务完成后再扫描：不应产生该任务的新提醒
curl -s -X POST $B/tasks/$TID/complete
curl -s -X POST $B/reminders/scan
docker compose exec -T postgres psql -U personalos -d personal_ai_os \
  -c "SELECT count(*) FROM reminder WHERE source_id = '$TID'"
```

## 场景 D — 重启幂等（对应 SC-005）

```bash
curl -s -X POST $B/reminders/scan
BEFORE=$(docker compose exec -T postgres psql -U personalos -d personal_ai_os -tAc "SELECT count(*) FROM reminder")
# 重启应用
kill <pid>; ./mvnw spring-boot:run &
sleep 5
curl -s -X POST $B/reminders/scan
AFTER=$(docker compose exec -T postgres psql -U personalos -d personal_ai_os -tAc "SELECT count(*) FROM reminder")
echo "重启前 $BEFORE / 重启后 $AFTER"    # 两者必须相等
```

## 场景 E — Agent 与确认流程

```bash
python3 scripts/dev/mock-openai-server.py --port 9099   # 已支持 tool_calls
# 对话里说「明天三点开会」→ 查 calendar_event 应真实新增
curl -s "$B/agent/tool-invocations" | python3 -m json.tool

# 让助手删除日程 → 日程仍在 + 生成待确认
curl -s "$B/agent/confirmations?status=PENDING"
# 确认后才真正删除
curl -s -X POST "$B/agent/confirmations/<id>/confirm" -H 'Content-Type: application/json' -d '{"token":"<token>"}'
```

## 场景 F — 前端

```bash
cd frontend && npm run dev    # http://localhost:5173/calendar
```

**验证点**：日程按天分组展示；创建重复日程后列表出现多个实例；到点提醒在 30 秒内自动出现；
点「知道了」后不再出现；后端停掉时页面不弹报错。

## 验证记录表

| 场景 | 命令 | 结果 | 证据 |
|---|---|---|---|
| A-自动化测试 | `./mvnw -B verify` | ⏳ | |
| B-REST 冒烟 | curl 建/查/扫/读/删 | ⏳ | |
| C-任务也能提醒 | curl + psql | ⏳ | |
| D-重启幂等 | 重启前后行数比对 | ⏳ | |
| E-Agent 与确认 | 对话 + 确认接口 | ⏳ | |
| F-前端 | `npm run dev` | ⏳ | 需人工（无 GUI 环境下可只做构建验证） |
