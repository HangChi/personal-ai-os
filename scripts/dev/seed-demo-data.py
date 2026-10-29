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
    ("批改初一27班期中试卷", "49 份，重点看第 18 题的辅助线思路", "HIGH", -1, "TODO", ["教学", "批改"]),
    ("准备下周一家长会材料", "含成绩分析、下阶段计划、家长提问汇总", "HIGH", 0, "TODO", ["教务"]),
    ("整理学生缴费信息表", "发给家长前先核对名单，共 49 人", "MEDIUM", 1, "TODO", ["教务", "表格"]),
    ("更新班级座位表", "按上次月考成绩重新排，注意视力问题学生", "LOW", None, "TODO", []),
    ("编写数学单元测试卷", "第三章 一元一次方程，难度比期中略低", "MEDIUM", 4, "DOING", ["教学"]),
    ("提交教务处月报", "已交，等回复", "HIGH", -3, "DONE", ["教务"]),
    ("联系周栩老师确认班会时间", None, "MEDIUM", 3, "TODO", ["沟通"]),
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
    ("初一27班 数学课", "第三章 一元一次方程（第 2 课时）", "教学楼 301", False, 0, 9, 0, 0, 10, 0, None, 15),
    ("教研组例会", "讨论期中成绩与下阶段进度", "行政楼 2 楼会议室", False, 0, 14, 0, 0, 15, 30, None, 30),
    ("校运会", "全天，班级需要带队", "操场", True, 1, 0, 0, 1, 9, 0, None, 1440),
    ("升旗仪式", "全体师生", "操场", False, 1, 8, 0, 1, 8, 30, "WEEKLY", 30),
    ("早读", "语文/英语交替", "教学楼 301", False, 0, 7, 30, 0, 8, 0, "WEEKDAYS", 10),
    ("家长沟通日", "每月固定电话沟通 5 位家长", "办公室", False, 5, 16, 0, 5, 17, 0, "MONTHLY", 60),
]

POLICY_MD = """# 初一（27）班 班级公约

## 一、课堂

1. 上课铃响前坐好，桌面只留本节课要用的书和文具。
2. 发言先举手，别人发言时不打断。
3. 手机一律放在教室后面的收纳袋里，放学取回。

## 二、作业

- 每天晚自习结束前把作业交到组长处，**迟交当天补完**。
- 抄作业一经发现，当天作业重做一遍，并当面说明。
- 每周五放学前公布本周作业完成情况。

## 三、卫生

| 区域 | 负责人 | 检查时间 |
| --- | --- | --- |
| 教室地面 | 第一组 | 每天中午、放学 |
| 黑板与讲台 | 值日班长 | 每节课后 |
| 走廊与窗台 | 第三组 | 每天放学 |

## 四、请假

学生请假须由家长**提前**通过电话或微信告知班主任周栩老师，事后补假一律不认。
突发情况可先由家长电话说明，返校当天补交书面说明。
"""

SCORE_MD = """# 期中考试成绩分析（初一 27 班）

## 总体

- 参考人数：49
- 班级平均分：78.6
- 及格率（≥60）：91.8%
- 优秀率（≥85）：24.5%

## 分数段分布

| 分数段 | 人数 | 占比 |
| --- | --- | --- |
| 90-100 | 7 | 14.3% |
| 85-89 | 5 | 10.2% |
| 70-84 | 22 | 44.9% |
| 60-69 | 11 | 22.4% |
| 60 以下 | 4 | 8.2% |

## 典型问题

1. **第 18 题**（辅助线）：全班仅 6 人完整做出，多数人卡在「不知道往哪添线」。
2. **第 22 题**（应用题）：列方程正确但解方程出错的有 13 人，属于计算基本功问题。
3. **第 25 题**（压轴）：空白率 41%，建议下学期开始做分层训练。

## 下一步

- 每周三下午第四节加一节「错题重做」，只做本次错得最多的 5 道题。
- 对 60 分以下的 4 名学生做一对一谈话，先解决计算准确率。
"""

NOTES_MD = """# 数学教学笔记

## 一元一次方程的常见错误

### 1. 移项不变号

学生最容易错的地方不是「不会移项」，而是**移项时忘了变号**。
典型写法：

```
2x + 5 = 11
2x = 11 + 5      ← 错，应该是 11 - 5
```

建议在讲这一节时，要求学生每一步都写「移项要变号」六个字，
连续两周后再撤掉。这个动作看起来笨，但比反复讲道理有效。

### 2. 去分母时漏乘

方程两边同乘最小公倍数时，学生只乘了含分母的项，常数项被漏掉。
检验办法：讲完后立刻给一道 `x/2 + 1 = 3` —— 漏乘的学生会算出 `x = 4` 而不是 `x = 4`（正确）。
所以这题不能用来检验，要换成 `x/3 + 2 = 5`。

### 3. 括号前是负号

`-(2x - 3)` 展开成 `-2x - 3` 是高频错误。建议先用具体数字验证：
把 `x = 1` 代进去，左边 `-(2-3) = 1`，右边 `-2-3 = -5`，学生自己能看出不对。

## 分层作业设计

按期中成绩把作业分三层，同一份卷子标 ★ / ★★ / ★★★：

- ★：只做课本例题的同型题，目标是「会做」；
- ★★：加一道需要两步的题，目标是「做对」；
- ★★★：加一道开放题（如「自己编一道能用这个方程解决的应用题」），目标是「想清楚」。

**关键：三层都留最后一道相同的题。** 让基础层学生也能参与课堂讨论，
不至于一开始就把自己归到「不用听」那一类。
"""

EXAM_MD = """# 期中复习题库（一元一次方程与几何入门）

> 使用说明：前 12 题是基础题，13 题之后是提高题。带 ★ 的题建议只给学有余力的学生。

## 一、解方程（每题 4 分，共 32 分）

**1.** `3x - 7 = 11`

解：移项得 `3x = 11 + 7 = 18`，两边同除以 3，得 `x = 6`。
检验：把 `x = 6` 代回，左边 `3×6 - 7 = 11`，等于右边，成立。

**2.** `5(x - 2) = 3x + 4`

解：去括号得 `5x - 10 = 3x + 4`；移项得 `5x - 3x = 4 + 10`；合并得 `2x = 14`；所以 `x = 7`。

**3.** `(x + 1)/2 - (x - 3)/4 = 1`

解：两边同乘 4（最小公倍数），得 `2(x + 1) - (x - 3) = 4`。
去括号得 `2x + 2 - x + 3 = 4`，合并得 `x + 5 = 4`，所以 `x = -1`。
**这个题最常见的错误是只乘了左边的第一项，把 `- (x-3)/4` 漏乘。**

**4.** `0.3x - 1.2 = 0.5x + 0.6`

解：两边同乘 10 化为整数：`3x - 12 = 5x + 6`；移项得 `-2x = 18`；所以 `x = -9`。

**5.** `2x - 3(x - 1) = 5 - 2(x + 2)`

解：去括号得 `2x - 3x + 3 = 5 - 2x - 4`；合并得 `-x + 3 = 1 - 2x`；移项得 `x = -2`。

**6.** `(2x - 1)/3 = (x + 2)/4`

解：交叉相乘得 `4(2x - 1) = 3(x + 2)`；展开得 `8x - 4 = 3x + 6`；移项得 `5x = 10`；所以 `x = 2`。

**7.**★ `|x - 2| = 5`

解：绝对值的方程要分两种情况：
- `x - 2 = 5`，得 `x = 7`；
- `x - 2 = -5`，得 `x = -3`。
所以 `x = 7` 或 `x = -3`。**只写一个解的扣一半分。**

**8.**★ `3x - 2(x - 1) = 2(x + 3) - (x - 4)`

解：去括号得 `3x - 2x + 2 = 2x + 6 - x + 4`；合并得 `x + 2 = x + 10`；
移项得 `0 = 8`，矛盾。所以**原方程无解**。
这类题要敢于下「无解」的结论，而不是硬凑一个数。

## 二、应用题（每题 6 分，共 30 分）

**9.** 某班有 49 名学生，男生比女生多 5 人。求男女生各多少人。

解：设女生 `x` 人，则男生 `x + 5` 人。
列方程：`x + (x + 5) = 49`；解得 `x = 22`。
答：女生 22 人，男生 27 人。
**检验：22 + 27 = 49，且 27 - 22 = 5，两个条件都满足。**

**10.** 一件商品按标价的八折出售，仍可获利 20%。若进价 100 元，求标价。

解：设标价 `x` 元。售价为 `0.8x`，利润为 `0.8x - 100`。
列方程：`(0.8x - 100)/100 = 0.2`；解得 `0.8x = 120`，`x = 150`。
答：标价 150 元。

**11.** 甲乙两地相距 240 千米，一辆汽车从甲地开往乙地，速度 60 千米/小时。
出发 1 小时后，另一辆车从乙地开往甲地，速度 80 千米/小时。问第二辆车出发几小时后相遇。

解：设出发 `x` 小时后相遇。第一辆车已行驶 `1 + x` 小时。
列方程：`60(1 + x) + 80x = 240`；解得 `140x = 180`，`x = 9/7`。
答：约 1.29 小时后相遇。
**注意：单位要统一，「1 小时」是第二辆车出发前已经过去的时间，容易漏掉。**

**12.** 一个两位数，十位数字比个位数字大 3，把两个数字对调后得到的新数比原数小 27。求原数。

解：设个位数字 `x`，十位数字 `x + 3`。
原数 = `10(x + 3) + x = 11x + 30`；新数 = `10x + (x + 3) = 11x + 3`。
列方程：`11x + 30 - (11x + 3) = 27`，即 `27 = 27`，**恒成立**。
说明这类两位数有多个解：31、42、53、64、75、86、97 都满足条件。
**这是本题的「陷阱」：条件不足，答案不唯一。**

## 三、几何入门（每题 5 分，共 20 分）

**13.** 已知 `∠AOB = 90°`，`OC` 是 `∠AOB` 内部的一条射线，`∠AOC = 3∠BOC`。求 `∠BOC`。

解：设 `∠BOC = x`，则 `∠AOC = 3x`。
列方程：`x + 3x = 90`；解得 `x = 22.5`。
答：`∠BOC = 22.5°`。

**14.** 两条直线相交，其中一个角是 40°，求另外三个角。

解：对顶角相等，所以对角也是 40°；邻补角互补，所以另外两个角是 `180° - 40° = 140°`。
答：另外三个角分别是 140°、40°、140°。

**15.** 线段 `AB = 12cm`，点 `C` 在 `AB` 上，`AC = 2BC`。求 `AC` 与 `BC`。

解：设 `BC = x`，则 `AC = 2x`。`x + 2x = 12`，得 `x = 4`。
答：`AC = 8cm`，`BC = 4cm`。

**16.**★ 一个角的补角比它的余角的 2 倍还大 30°。求这个角。

解：设这个角为 `x`。补角 `180 - x`，余角 `90 - x`。
列方程：`180 - x = 2(90 - x) + 30`；解得 `180 - x = 210 - 2x`，所以 `x = 30`。
答：这个角是 30°。
**验证：补角 150°，余角 60°，150 = 2×60 + 30，成立。**

## 四、易错点汇总（讲评时用）

1. **移项不变号** —— 全班错误率最高的一项，占计算题失分的 40%。
2. **去分母漏乘常数项** —— 第 3 题是专门为此设计的。
3. **去括号时符号处理错** —— 括号前是负号时，每一项都要变号。
4. **应用题不写「设」和「答」** —— 按评分标准各扣 1 分，但学生往往不重视。
5. **解完不回代检验** —— 第 10、16 题都适合让学生自己验一遍。

## 五、讲评建议

- 第 1、2、5 题当堂讲，让中等生上黑板做；
- 第 3、4 题重点讲「为什么每一项都要乘」；
- 第 8 题（无解）单独讲，这是思维方式的转变；
- 第 12 题（多解）不建议全班讲，容易造成混乱，可作为课后思考题；
- 第 16 题留给学有余力的学生，下次课单独答疑。
"""

CONVERSATIONS = [
    {
        "title": "期中复习提纲",
        "when": at(0, 10, 12),
        "messages": [
            ("USER", "帮我整理一下初一数学期中复习提纲，重点是一元一次方程和应用题。"),
            ("ASSISTANT", """## 期中复习提纲（一元一次方程 + 应用题）

### 一、必须掌握的三个动作

1. **去分母**：两边同乘最小公倍数，注意常数项也要乘
2. **去括号**：括号前是负号时，每一项都要变号
3. **移项变号**：跨过等号的项必须变号

### 二、常见错误清单

| 错误 | 典型写法 | 纠正 |
| --- | --- | --- |
| 移项不变号 | `2x = 11 + 5` | 应为 `11 - 5` |
| 去分母漏乘 | `x/2 + 1 = 3` 只乘了 `x/2` | 常数项也要乘 |
| 括号前负号 | `-(2x-3) = -2x-3` | 应为 `-2x+3` |

### 三、应用题的四个步骤

> 审题 → 设未知数 → 列方程 → 检验并作答

**最容易失分的是「检验」**：不少学生算出答案就交卷，没有回代检验，
单位、范围、是否符合实际都没看。

### 四、建议的两天安排

- 第一天：只做计算题，限时 20 分钟 15 题，目标是**零失误**
- 第二天：只做应用题，每题写完整的「设—列—解—答」四行

需要我按这个提纲出一份 30 分钟的模拟卷吗？"""),
        ],
    },
    {
        "title": "Excel 算平均分和及格率",
        "when": at(0, 8, 40),
        "messages": [
            ("USER", "49 个学生的分数在 Excel 里，怎么快速算平均分和及格率？"),
            ("ASSISTANT", """假设分数在 `B2:B50`（49 行）。

**平均分**

```
=AVERAGE(B2:B50)
```

**及格率（≥60）**

```
=COUNTIF(B2:B50,">=60")/COUNT(B2:B50)
```

把它设成百分比格式即可。

**如果想一次看到全部指标**

```
=LET(s, B2:B50,
     HSTACK(
       AVERAGE(s),
       COUNTIF(s,">=60")/COUNT(s),
       COUNTIF(s,">=85")/COUNT(s),
       MAX(s),
       MIN(s)
     ))
```

`LET` 让 `s` 只写一次，改区间时只改一处。**没有 `LET` 的旧版 Excel 会把区间重复写 5 遍**，改一个漏一个是最常见的事故。

### 两个容易踩的坑

1. **空单元格会被 `AVERAGE` 忽略，但 `COUNT` 也只数数字** —— 如果有学生缺考填了「缺考」文本，两个公式都自动跳过它，分母变成 48。想按 49 人算，用 `COUNTA(A2:A50)` 当分母。
2. **分数带「分」字**（如 `85分`）会被当成文本，`AVERAGE` 直接返回 `#DIV/0!`。先分列或 `VALUE(SUBSTITUTE(B2,"分",""))`。"""),
        ],
    },
    {
        "title": "家长会发言稿",
        "when": at(-1, 19, 5),
        "messages": [
            ("USER", "下周一家长会，帮我写个发言稿，10 分钟左右"),
            ("ASSISTANT", """## 家长会发言稿（约 10 分钟）

### 开场（1 分钟）

各位家长晚上好，感谢大家抽时间过来。今天我想讲三件事：**这次期中考试看到了什么**、
**接下来两个月我们怎么做**、**需要家长配合的三件小事**。

### 一、期中成绩说明了什么（3 分钟）

班级平均分 78.6，及格率 91.8%。这个数字比上次月考提高了 2.3 分，进步主要来自中等分段。

但有两个问题需要说清楚：

1. 高分段（85 分以上）只有 12 人，**说明「会做基础题」和「能拿高分」之间还有距离**；
2. 4 名学生低于 60 分，其中 3 人的问题集中在校准计算准确率，不是听不懂。

> 我想强调的是：**分数是结果，不是原因。** 我们看分数是为了找到原因，不是为了排名。

### 二、接下来两个月（3 分钟）

- 每周三下午第四节加一节「错题重做」，只做错得最多的 5 道题
- 作业分三层，最后一题全班相同，保证基础薄弱的孩子也能参与讨论
- 每次单元测验后，我会把「错得最多的三道题」发到班级群，家长可以看到孩子卡在哪

### 三、需要家长配合的三件事（2 分钟）

1. **晚上 9 点半之后不要再让孩子做题**，睡眠比多刷两道题重要
2. 检查作业时**不要直接讲答案**，问一句「你这步是怎么想的」效果更好
3. 孩子说「听不懂」时，请告诉我，不要自己找补习班先顶上——我需要知道课堂上哪里没讲清

### 结尾（1 分钟）

我的手机和微信随时可以联系。**孩子的问题越早说越好**，等到期末就只能是结果，不是过程了。

谢谢大家。"""),
        ],
    },
    {
        "title": "排课冲突检查",
        "when": at(-3, 15, 20),
        "messages": [
            ("USER", "下周三下午我有两节课，帮我看下有没有和教研活动冲突"),
            ("ASSISTANT", """按你说的「下周三下午」，我查到这三个时间块：

| 时间 | 事项 | 地点 | 冲突 |
| --- | --- | --- | --- |
| 14:00-15:30 | 教研组例会 | 行政楼 2 楼 | ⚠️ 与你第 6 节重叠 |
| 15:40-16:20 | 初一27班 数学课 | 教学楼 301 | 无 |
| 16:30-17:10 | 社团活动（你带队） | 操场 | 无 |

**冲突点在 14:00-15:30**：教研组例会与第 6 节课完全重叠。

两个处理方式：

1. **调课**：把第 6 节换到周二下午，我可以帮你查周二是否有空位；
2. **请假**：教研组例会可以请一次假，但需要在会前把材料发给组长。

要我先查周二的空位吗？"""),
        ],
    },
    {
        "title": "批改标准的统一",
        "when": at(-1, 11, 0),
        "messages": [
            ("USER", "同一道题几个老师给分不一样，怎么统一标准？"),
            ("ASSISTANT", """关键是**把「给分点」写下来，而不是写「酌情给分」**。

### 一个可操作的流程

1. 抽 10 份卷子，3 位老师各自独立批同一道题
2. 把分歧最大的 3 份摆出来，逐份说「你给几分、为什么」
3. 当场定下**给分点清单**，例如第 18 题：
   - 作出辅助线 1 分
   - 证明两个三角形全等 2 分
   - 得出对应边相等 1 分
   - 结论完整 1 分
4. 按清单重批这 10 份，看是否一致

### 为什么不能写「酌情」

「酌情」意味着标准在每个老师脑子里，而**每个人的「酌情」不一样**。
学生拿到不同的分数时，他学到的不是知识，是「要看遇到哪个老师」。

> 一份能复现的评分标准，比一份「看起来更宽松」的标准更重要。"""),
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
        ("班级公约.md", "初一27班 班级公约", POLICY_MD),
        ("期中成绩分析.md", "期中考试成绩分析", SCORE_MD),
        ("数学教学笔记.md", "数学教学笔记", NOTES_MD),
        ("期中复习题库.md", "期中复习题库", EXAM_MD),
    ]
    for filename, title, content in docs:
        result = upload_document(filename, title, content)
        print(f"  知识库《{title}》{result['chunkCount']} 块 / {result['charCount']} 字")


def seed_memory() -> None:
    items = [
        ("我是江苏省扬州市宝应县的初中数学老师，教初一。", True),
        ("我带初一年级 27 班，班主任是周栩老师，全班 49 名学生。", True),
        ("回答尽量简洁，不要长篇大论，能一句说清就不要三段。", False),
        ("习惯用腾讯文档做面向学生家长的在线收集，很在意填写隐私，"
         "要求「学生只能看到自己的信息」。", False),
        ("批改作业时更看重过程分，愿意为完整的解题步骤给分。", False),
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
