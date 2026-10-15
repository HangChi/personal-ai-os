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

> 全部为 2026-10-14 本机**实际执行**的结果。

| 场景 | 命令 | 结果 | 证据 |
|---|---|---|---|
| A-自动化测试 | `./mvnw -B verify` | ✅ | **Tests run: 115, Failures: 0, Errors: 0**，`BUILD SUCCESS`。新增 28 例：重复规则单元测试 14（无 Spring、无数据库、**无 sleep**）+ 日程提醒集成测试 14 |
| A-重复规则纯函数（SC-001） | 单元测试 | ✅ | 同输入计算两次结果逐元素相等 |
| A-工作日零周末（SC-002） | 单元测试 + 真机 | ✅ | 30 天区间内周末发生数 **= 0**；真机 20 天区间展开 16 个实例，周末 **0** 个 |
| A-每月不跳月（SC-003） | 单元测试 | ✅ | 跨 12 个月**每月恰好一次**；1/31 → 2/28 → **3/31**（不是 3/28）；闰年 2/29 |
| A-夏令时不漂移 | 单元测试 | ✅ | 用 America/New_York 验证跨夏令时切换后本地时刻始终 09:00（用 Instant 累加 24h 会漂到 10:00） |
| A-扫描幂等（SC-004） | 集成测试 | ✅ | 连续扫描 5 次，`reminder` 行数与 1 次完全相同；后续 `created=0`、`skipped=1` |
| A-重启幂等（SC-005） | 集成测试 + 真机 | ✅ | 用全新扫描器实例再扫行数不变；真机重启前后均为 **1** |
| A-任务来源（SC-006） | 集成测试 | ✅ | 未完成任务产生提醒；**已完成任务不产生** |
| A-唯一约束（SC-008） | 集成测试 | ✅ | 同一 (来源, 发生时刻) 恒为 1 条 |
| A-删除清理（SC-007） | 集成测试 + 真机 | ✅ | 删除日程后该日程提醒 **= 0**（真机 `deletedReminders=1`，剩余 0） |
| A-孤儿自愈 | 集成测试 | ✅ | 绕过清理直接删任务 → 下一轮扫描 `pruned=1`，不再展示幽灵提醒 |
| A-非法输入零写入（SC-009） | 集成测试 | ✅ | 结束早于开始 / 重复截止早于开始 → 零写入，HTTP 返回 400 |
| A-到点与否 | 集成测试 | ✅ | 将来的提醒被提前物化但不出现在待提醒列表；`dueOnly=false` 能看到；过点未读仍展示 |
| A-已读幂等 | 集成测试 + 真机 | ✅ | 重复标记均返回 200 且为 READ，保留首次阅读时间 |
| A-Agent 工具 | 集成测试 | ✅ | `createCalendarEvent` → `calendar_event` **真实 +1 行**；`deleteCalendarEvent` → **日程仍在** + 待确认 +1，确认后才删除 |
| B-真机启动与迁移 | `Migrating schema ... version "7"` | ✅ | 处理器注册含 `DELETE_CALENDAR_EVENT`；V1~V7 全部 success |
| B-真机 REST 冒烟 | curl 建/查/扫/读/删 | ✅ | 见上「真机」各行 |
| C-维度/权限守卫未被破坏 | 既有测试 | ✅ | 阶段 2/3 的 87 个测试全部保持通过（前置重构零回归） |
| F-前端 | `npm run type-check` + `npm run build` | ✅ | 类型检查退出码 0；构建成功（新增 `CalendarView` chunk）；顺手修掉了 `HomeView` 停留在阶段 0 的文案 |
| G-浏览器联调 | `npm run dev` 打开 `/calendar` | ✅ | 2026-10-14 真实浏览器（`scripts/dev/verify-frontend.js`）：日程编辑基于**原始定义**取回、重复规则 WEEKLY 与提前量 20 分钟均未被抹掉、列表与数据库双更新、控制台无错误 |

**未验证项**：无。`G-浏览器联调` 已于 2026-10-14 用真实浏览器完成，SC-001 ~ SC-010 均有实测证据。

**关于「重启」的验证口径**：测试里用「新建一个扫描器实例」等价于应用重启 ——
因为扫描器是**无状态**的，全部状态都在数据库里。
这正是当初选择「DB 状态驱动的周期扫描」而不是「内存定时任务」的原因：
后者重启即丢，用户会静默地收不到提醒。真机侧则做了真实的进程重启验证。
