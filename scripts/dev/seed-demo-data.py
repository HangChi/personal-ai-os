#!/usr/bin/env python3
"""造演示数据，用于人工检查页面排版。

为什么是脚本而不是一堆 curl：
1. **可重跑**：页面调整要反复看，手敲的 curl 第二次就漏字段；
2. **覆盖 UI 状态**：逾期任务、进行中、已完成、重复日程、全天事件、多标签、
   超长标题、有引用的长回答 —— 单看一条「正常数据」看不出排版问题；
3. **少花模型额度**：只有知识库需要真实嵌入（走 API），
   对话消息/用量/审计直接落库 —— 它们的格式由前端渲染决定，不需要模型生成。

用法：
    python3 scripts/dev/seed-demo-data.py            # 清空业务数据后重建
    python3 scripts/dev/seed-demo-data.py --keep     # 保留现有数据，只追加

前置：后端在 127.0.0.1:8080 运行，PostgreSQL 容器 personal-ai-os-postgres 在跑。

⚠️ 默认会**清空全部业务数据**（任务/日程/知识库/记忆/对话/用量/审计），
保留模型配置与 flyway 历史。脚本会先打印将删除的条数再动手。
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import uuid
from datetime import datetime, timedelta, timezone
from urllib import error, parse, request

BASE = "http://127.0.0.1:8080/api/v1"
PSQL = [
    "docker", "exec", "-i", "personal-ai-os-postgres",
    "psql", "-U", "personalos", "-d", "personal_ai_os",
    "-v", "ON_ERROR_STOP=1", "-q", "-t", "-A",
]

# ---------------------------------------------------------------- 基础工具


def api(method: str, path: str, body: dict | None = None):
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    req = request.Request(
        BASE + path, data=data, method=method,
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with request.urlopen(req, timeout=60) as response:
            payload = json.loads(response.read().decode())
    except error.HTTPError as exc:
        detail = exc.read().decode(errors="replace")[:400]
        raise RuntimeError(f"{method} {path} -> HTTP {exc.code}: {detail}") from exc
    if payload.get("code") != 0:
        raise RuntimeError(f"{method} {path} -> code={payload.get('code')} {payload.get('message')}")
    return payload.get("data")


def sql(statement: str) -> str:
    result = subprocess.run(PSQL, input=statement, text=True, capture_output=True)
    if result.returncode != 0:
        raise RuntimeError(f"SQL 失败: {result.stderr[:800]}\n语句: {statement[:400]}")
    return result.stdout.strip()


def q(text: str) -> str:
    """SQL 字符串字面量转义（psql 不支持参数绑定，只能自己双写单引号）。"""
    return "'" + text.replace("'", "''") + "'"


def local_now() -> datetime:
    return datetime.now().astimezone()


def iso(moment: datetime) -> str:
    """转成后端接受的 Instant 字面量（UTC + Z）。"""
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def at(days: int = 0, hour: int = 9, minute: int = 0) -> datetime:
    """本地时间轴上「今天 + days 天的 hour:minute」。"""
    base = local_now() + timedelta(days=days)
    return base.replace(hour=hour, minute=minute, second=0, microsecond=0)


def upload_document(filename: str, title: str, content: str) -> dict:
    boundary = "----seed" + uuid.uuid4().hex
    body = bytearray()
    body += f"--{boundary}\r\n".encode()
    body += (
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        "Content-Type: text/markdown; charset=utf-8\r\n\r\n"
    ).encode()
    body += content.encode()
    body += f"\r\n--{boundary}--\r\n".encode()

    url = BASE + "/knowledge/documents?title=" + parse.quote(title)
    req = request.Request(
        url, data=bytes(body), method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    try:
        with request.urlopen(req, timeout=120) as response:
            payload = json.loads(response.read().decode())
    except error.HTTPError as exc:
        raise RuntimeError(f"上传 {filename} -> HTTP {exc.code}: {exc.read().decode()[:300]}") from exc
    if payload.get("code") != 0:
        raise RuntimeError(f"上传 {filename} -> {payload.get('message')}")
    return payload["data"]


# ---------------------------------------------------------------- 清空

TABLES = [
    ("chat_message_citation", "对话引用"),
    ("chat_message", "对话消息"),
    ("agent_plan_step", "计划步骤"),
    ("agent_plan", "计划"),
    ("chat_session", "会话"),
    ("reminder", "提醒"),
    ("calendar_event", "日程"),
    ("task_tag", "任务标签"),
    ("task", "任务"),
    ("vector_store", "向量"),
    ("knowledge_document", "知识文档"),
    ("memory", "记忆"),
    ("confirmation", "待确认"),
    ("operation_record", "操作记录"),
    ("token_usage", "用量"),
]


def reset() -> None:
    counts = []
    for table, label in TABLES:
        count = int(sql(f"SELECT count(*) FROM {table};") or 0)
        if count:
            counts.append(f"{label} {count}")
    print("将清空：" + ("、".join(counts) if counts else "无数据"))
    # 单语句多用例：ON DELETE CASCADE 会把子表带走，但显式按序删更可控
    sql("BEGIN;\n" + "\n".join(f"DELETE FROM {t};" for t, _ in TABLES) + "\nCOMMIT;")


# ---------------------------------------------------------------- 数据

TASKS = [
    # (标题, 描述, 优先级, due 偏移天数, 状态, 标签)
    ("整理本月账单", "把银行卡、支付平台和现金支出归到同一张表里", "HIGH", -1, "TODO", ["财务", "整理"]),
    ("准备周末短途出行", "确认交通、住宿和天气，列一份轻量行李清单", "HIGH", 0, "TODO", ["出行"]),
    ("回复需要跟进的邮件", "先处理有明确截止时间的三封，其他邮件归档", "MEDIUM", 1, "TODO", ["工作", "沟通"]),
    ("备份手机照片", "整理最近三个月的照片，再同步到云端和移动硬盘", "LOW", None, "TODO", ["生活", "备份"]),
    ("完成个人项目周计划", "拆出本周最重要的三个结果，并给每项安排下一步", "MEDIUM", 4, "DOING", ["个人项目", "计划"]),
    ("更新家庭设备清单", "补充路由器、硬盘和备用电源的购买日期与保修期", "HIGH", -3, "DONE", ["家庭", "资料"]),
    ("联系物业确认维修时间", None, "MEDIUM", 3, "TODO", ["生活", "沟通"]),
    (
        "这是一条特别长的任务标题用来观察列表在窄屏下的截断与换行表现是否会挤压右侧操作按钮",
        "长标题 + 长描述的组合，用来检查表格行高与省略号的实现",
        "LOW",
        6,
        "TODO",
        ["测试", "排版", "边界"],
    ),
]

EVENTS = [
    ("每周计划整理", "回顾上周进展，确定本周最重要的三件事", "家中", False, 0, 9, 0, 0, 9, 45, "WEEKLY", 15),
    ("个人项目讨论", "确认下一步范围、分工和交付时间", "线上会议", False, 0, 14, 0, 0, 15, 30, None, 30),
    ("周末短途出行", "提前半小时出门，带好证件和充电设备", "高铁站", True, 1, 0, 0, 1, 23, 59, None, 1440),
    ("力量训练", "按本周计划完成训练，结束后记录状态", "健身房", False, 1, 19, 30, 1, 20, 30, "WEEKLY", 30),
    ("整理收件箱", "清理待处理邮件和稍后阅读列表", "家中", False, 0, 7, 30, 0, 8, 0, "WEEKDAYS", 10),
    ("每月财务回顾", "核对固定支出、可变支出和下月预算", "家中", False, 5, 16, 0, 5, 17, 0, "MONTHLY", 60),
]

WORKFLOW_MD = """# 个人工作流与资料整理规范

## 一、收集

1. 新想法、临时事项和待回复内容先放进收集箱，不在当下打断正在进行的工作。
2. 每条记录尽量写成一个可执行动作，避免只留下模糊主题。
3. 涉及账单、证件和账号的信息单独存放，不混入公开笔记。

## 二、整理

- 任务标题用「动作 + 对象」表达，例如「确认维修时间」而不是「物业」。
- 文档标题包含主题和时间范围，版本更新时保留变更原因。
- 已完成的事项保留结果链接，方便之后回看，不重复做同一件事。

## 三、每周回顾

| 项目 | 检查内容 | 结果 |
| --- | --- | --- |
| 收件箱 | 是否还有未分类事项 | 清空或延期 |
| 日程 | 下周是否存在冲突 | 调整优先级 |
| 账单 | 是否有异常支出 | 标记并核对 |

## 四、隐私

外部发送、删除资料、修改账单和代表本人确认的操作，都需要先明确展示影响范围，
在执行前等待确认。
"""

BUDGET_MD = """# 本月个人预算复盘

## 总体

- 本月预算：8,000 元
- 已使用：5,460 元
- 剩余预算：2,540 元
- 固定支出占比：48%

## 分类分布

| 分类 | 金额 | 占比 |
| --- | --- | --- |
| 房租与账单 | 2,600 | 47.6% |
| 餐饮 | 1,180 | 21.6% |
| 出行 | 620 | 11.4% |
| 生活用品 | 540 | 9.9% |
| 其他 | 520 | 9.5% |

## 观察

1. 餐饮支出在周末明显上升，下周提前准备两顿简单的饭。
2. 出行支出低于预算，但有一笔重复扣款需要核对。
3. 生活用品中有一项一次性购买，不纳入下月固定支出。

## 下月动作

- 固定支出在月初统一核对。
- 餐饮预算拆成工作日与周末两部分。
- 为年度设备更换单独建立储备项。
"""

TRAVEL_MD = """# 周末短途出行计划

## 行程

- 周六上午：出发，办理入住或寄存行李
- 周六下午：城市步行路线与展览
- 周六晚上：晚餐，保留一小时自由安排
- 周日上午：早餐后返程

## 行李清单

- 身份证件、充电器、移动电源
- 一套备用衣物和轻便雨具
- 常用药品和水杯
- 耳机、阅读设备

## 出发前确认

1. 查看天气和交通提醒。
2. 确认住宿、车票和支付方式。
3. 把行程放进日历，留出返程缓冲。
4. 只保留必要安排，给临时变化留出空间。
"""

DEVICE_MD = """# 家庭设备与账号清单

## 设备

| 设备 | 用途 | 购买时间 | 保修到期 |
| --- | --- | --- | --- |
| 路由器 | 家庭网络 | 2025-04 | 2027-04 |
| 移动硬盘 | 本地备份 | 2024-11 | 2026-11 |
| 备用电源 | 停电应急 | 2025-08 | 2026-08 |

## 定期检查

- 每季度检查一次设备固件和备份状态。
- 保修到期前两周决定维修、续保或更换。
- 不在普通笔记中保存完整密码，只记录密码管理器中的条目名称。
"""

CONVERSATIONS = [
    {
        "title": "安排本周重点",
        "when": at(0, 10, 12),
        "messages": [
            ("USER", "帮我把这周的事情按重要程度排一下，最好只保留三个重点。"),
            ("ASSISTANT", """可以先按「影响大、截止近、完成后能减少后续压力」排序：

1. **整理本月账单**：今天先完成分类，避免月底集中处理。
2. **完成个人项目周计划**：明确本周交付结果，并拆出第一步。
3. **准备周末短途出行**：确认交通和住宿，减少临出发前的决策。

其他事项可以放进待处理清单，等这三件事各自完成第一步后再安排。"""),
        ],
    },
    {
        "title": "整理旅行清单",
        "when": at(0, 8, 40),
        "messages": [
            ("USER", "帮我把周末短途出行整理成一份轻量清单，不要带太多东西。"),
            ("ASSISTANT", """## 出发前

- [ ] 查看天气和交通提醒
- [ ] 确认车票、住宿和支付方式
- [ ] 准备身份证件、充电器和移动电源
- [ ] 带一套备用衣物和轻便雨具
- [ ] 把行程放进日历，留出返程缓冲

## 随身

证件、手机、充电设备、常用药品、水杯和耳机即可。大件物品尽量使用目的地可购买或可租用的方案，避免为了「可能用到」增加行李。"""),
        ],
    },
    {
        "title": "Excel 记录月度开支",
        "when": at(-1, 19, 5),
        "messages": [
            ("USER", "我想用 Excel 记录每月开支，帮我设计一个简单的表头和汇总公式。"),
            ("ASSISTANT", """建议先保持四列，足够覆盖大多数个人记录：

| 日期 | 分类 | 备注 | 金额 |
| --- | --- | --- | --- |

假设分类在 B2:B200，金额在 D2:D200：

    =SUM(D2:D200)
    =SUMIF(B2:B200,"餐饮",D2:D200)
    =COUNTIF(B2:B200,"交通")

再加一个数据透视表按分类汇总。先把记录写完整，比一开始设计十几个字段更容易坚持。"""),
        ],
    },
    {
        "title": "日程冲突检查",
        "when": at(-3, 15, 20),
        "messages": [
            ("USER", "下周三下午我有一个线上讨论，帮我看看和现有日程会不会冲突。"),
            ("ASSISTANT", """我查到下周三下午有三个时间块：

| 时间 | 事项 | 冲突 |
| --- | --- | --- |
| 14:00-15:30 | 个人项目讨论 | 当前安排 |
| 15:40-16:20 | 收件箱整理 | 无 |
| 16:30-17:10 | 力量训练 | 无 |

讨论结束后到下一项之间有 10 分钟缓冲，日程可以保留。若讨论容易超时，建议把收件箱整理顺延到晚上。"""),
        ],
    },
    {
        "title": "统一文件命名规则",
        "when": at(-1, 11, 0),
        "messages": [
            ("USER", "我的文件越来越乱，帮我定一个简单的命名规则，个人项目和生活资料都能用。"),
            ("ASSISTANT", """可以用「日期-主题-状态」三段式：

    2026-11-01-个人项目周计划-v1
    2026-11-01-家庭设备清单-当前
    2026-11-01-周末出行-已确认

规则只保留三条：

1. 日期统一用 YYYY-MM-DD，方便排序。
2. 主题使用人能直接理解的词，不堆内部缩写。
3. 需要区分版本时只用 v1、v2，最终状态写在末尾。"""),
        ],
    },
]
# ---------------------------------------------------------------- 各模块


def seed_tasks() -> None:
    created = []
    for title, description, priority, due_offset, status, tags in TASKS:
        body = {"title": title, "priority": priority, "tags": tags}
        if description:
            body["description"] = description
        if due_offset is not None:
            body["dueAt"] = iso(at(due_offset, 18, 0))
        task = api("POST", "/tasks", body)
        created.append(task)
        if status == "DONE":
            api("POST", f"/tasks/{task['id']}/complete")
        elif status == "DOING":
            # 没有「开始」接口，DOING 只在状态机里，直接落库最快
            sql(f"UPDATE task SET status = 'DOING' WHERE id = {q(task['id'])};")
    print(f"  任务 {len(created)} 条（含逾期 / 今天 / 无期限 / 长标题 / 已完成 / 进行中）")


def seed_events() -> None:
    for (title, description, location, all_day, start_day, sh, sm,
         end_day, eh, em, recurrence, lead) in EVENTS:
        body = {
            "title": title,
            "description": description,
            "location": location,
            "allDay": all_day,
            "startsAt": iso(at(start_day, sh, sm)),
            "endsAt": iso(at(end_day, eh, em)),
            "reminderLeadMinutes": lead,
        }
        if recurrence:
            body["recurrenceType"] = recurrence
            # 重复截止放在两个月后，够月视图翻两页
            body["recurrenceUntil"] = iso(at(60, 23, 59))
        api("POST", "/calendar/events", body)
    print(f"  日程 {len(EVENTS)} 条（含全天 / 每周 / 工作日 / 每月）")


def seed_knowledge() -> None:
    docs = [
        ("个人工作流规范.md", "个人工作流与资料整理规范", WORKFLOW_MD),
        ("本月个人预算.md", "本月个人预算复盘", BUDGET_MD),
        ("周末短途出行.md", "周末短途出行计划", TRAVEL_MD),
        ("家庭设备清单.md", "家庭设备与账号清单", DEVICE_MD),
    ]
    for filename, title, content in docs:
        result = upload_document(filename, title, content)
        print(f"  知识库《{title}》{result['chunkCount']} 块 / {result['charCount']} 字")


def seed_memory() -> None:
    items = [
        ("回答尽量简洁，先给结论，再补充必要细节。", True),
        ("涉及外部发送、删除资料、修改账单或代表我确认时，先说明影响并等待确认。", True),
        ("我习惯用 Markdown 整理清单、计划和会议记录。", False),
        ("个人资料、账单和账号信息属于敏感内容，默认不在公开笔记中展开。", False),
        ("安排事情时优先考虑截止时间、影响范围和完成后的后续压力。", False),
    ]
    for content, pinned in items:
        memory = api("POST", "/memories", {"content": content})
        if pinned:
            # 置顶是独立端点（PATCH /memories/{id}/pin），不是更新接口的一个字段
            api("PATCH", f"/memories/{memory['id']}/pin", {"pinned": True})
    print(f"  记忆 {len(items)} 条（2 条常驻）")


def seed_conversations() -> None:
    for convo in CONVERSATIONS:
        conversation_id = str(uuid.uuid4())
        when = convo["when"].astimezone(timezone.utc)
        last = when
        statements = [
            "INSERT INTO chat_session (conversation_id, title, created_at, updated_at) VALUES "
            f"({q(conversation_id)}, {q(convo['title'])}, "
            f"'{when.isoformat()}', '{when.isoformat()}');"
        ]
        for index, (role, content) in enumerate(convo["messages"], start=1):
            last = when + timedelta(minutes=index * 2)
            statements.append(
                "INSERT INTO chat_message (conversation_id, message_type, content, sequence_no, created_at) "
                f"VALUES ({q(conversation_id)}, {q(role)}, {q(content)}, {index}, '{last.isoformat()}');"
            )
        # updated_at 取最后一条消息的时间，侧栏分组才落在正确的日期上
        statements.append(
            f"UPDATE chat_session SET updated_at = '{last.isoformat()}' WHERE conversation_id = {q(conversation_id)};"
        )
        sql("\n".join(statements))
    print(f"  会话 {len(CONVERSATIONS)} 个（今天 2 / 昨天 2 / 三天前 1，含表格与代码块）")


def seed_usage() -> None:
    """过去 30 天的用量。

    <p>为什么是 30 天而不是 7 天：用量页的默认窗口是「近 30 天」，
    只造 7 天会让图表柱子全部堆在右侧、左边半个月空白 ——
    看起来像渲染坏了，其实是没数据。造满窗口才能检查图表的真实形态。
    """
    model = "qwen3.8-flash"
    rows = []
    # 工作日多、周末少，避免图表出现「等高柱」这种不真实的样子
    for day_offset in range(30, -1, -1):
        day = local_now() - timedelta(days=day_offset)
        weekend = day.weekday() >= 5
        count = 2 if weekend else 4 + (day_offset * 7) % 6
        for index in range(count):
            moment = at(-day_offset, 9 + (index * 2) % 10, (index * 17) % 60).astimezone(timezone.utc)
            prompt = 2800 + ((day_offset * 13 + index * 7) % 9) * 620
            completion = 210 + ((day_offset * 5 + index * 11) % 7) * 105
            rows.append(
                "(" + ", ".join([
                    q(f"seed-{day_offset}-{index}"),
                    q(model),
                    str(prompt),
                    str(completion),
                    "'ACTUAL'",
                    f"'{moment.isoformat()}'",
                ]) + ")"
            )
    sql(
        "INSERT INTO token_usage (conversation_id, model, prompt_tokens, completion_tokens, source, created_at) VALUES "
        + ", ".join(rows) + ";"
    )
    print(f"  用量 {len(rows)} 条（过去 30 天，填满默认视图窗口）")


def seed_audit() -> None:
    """工具调用审计：活动页要能看到成功/失败/待确认三种结果。"""
    rows = [
        ("createTask", "SUCCESS", 42),
        ("createTask", "SUCCESS", 38),
        ("completeTask", "SUCCESS", 25),
        ("updateTask", "REQUIRES_CONFIRMATION", 31),
        ("deleteTask", "REQUIRES_CONFIRMATION", 19),
        ("searchKnowledge", "SUCCESS", 620),
        ("deleteTask", "REJECTED", 12),
        ("createEvent", "FAILED", 3300),
        ("findEvents", "SUCCESS", 88),
    ]
    statements = []
    for index, (operation, outcome, duration) in enumerate(rows):
        moment = at(0, 9 + index % 8, (index * 13) % 60).astimezone(timezone.utc)
        statements.append(
            "INSERT INTO operation_record "
            "(idempotency_key, source, operation, request_digest, outcome, duration_ms, created_at) VALUES ("
            + ", ".join([
                q(f"seed-audit-{index}-{uuid.uuid4().hex[:8]}"),
                "'TOOL'",
                q(operation),
                q(uuid.uuid4().hex * 2),
                q(outcome),
                str(duration),
                f"'{moment.isoformat()}'",
            ]) + ");"
        )
    sql("\n".join(statements))
    print(f"  审计 {len(rows)} 条（成功 5 / 待确认 2 / 拒绝 1 / 失败 1）")


# ---------------------------------------------------------------- 入口


def main() -> int:
    parser = argparse.ArgumentParser(description="造演示数据用于人工检查页面")
    parser.add_argument("--keep", action="store_true", help="保留现有业务数据，只追加")
    args = parser.parse_args()

    try:
        health = api("GET", "/health")
    except Exception as exc:  # noqa: BLE001 — 这里就是要给出可读的失败原因
        print(f"后端不可用（{BASE}）：{exc}", file=sys.stderr)
        return 1
    print(f"后端 {health['status']}，数据库 {health['components']['database']}")

    if not args.keep:
        reset()

    print("开始造数据…")
    seed_tasks()
    seed_events()
    seed_knowledge()
    seed_memory()
    seed_conversations()
    seed_usage()
    seed_audit()

    print("\n完成。刷新页面对照检查：")
    print("  首页      统计卡应有待办/逾期数字（1 条逾期）")
    print("  任务页    8 条，含逾期、进行中、已完成、多标签、超长标题")
    print("  日程页    月视图有全天与重复事件；今日 2 条")
    print("  知识库    4 个文档，可试搜索「移项」「及格率」，题库那篇会切成多块")
    print("  记忆页    5 条，2 条常驻置顶")
    print("  对话页    5 个会话，按今天/昨天/更早分组")
    print("  活动页    9 条审计，含失败与待确认")
    print("  用量页    过去 30 天（30 天窗口是默认视图，造满了才看得出图表形态）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
