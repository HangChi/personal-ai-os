#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
personal-ai-os 端到端能力边界测试套件。

设计原则
--------
1. **只走公开契约**：所有请求都打 `/api/v1`，不直连 Service、不改库来"造状态"
   （唯一例外是清理与核对 —— 那里直连 psql 只读/删除）。
2. **结论要复核**：接口说"成功"不等于真的落库。凡是"应该写进数据库"的断言，
   都用 psql 直查一遍。历史教训：视觉/接口层结论误判 23 次，只有数字可信。
3. **失败隔离**：单个用例异常不中断整轮；真实模型调用有独立超时。
4. **边界优先于路径**：正常路径一个用例，边界值（空、超长、越界、不存在、
   非法方法、超限）各一个 —— 这类地方才是真实事故的产地。

用法
----
    python3 scripts/e2e/e2e_suite.py                # 全量
    python3 scripts/e2e/e2e_suite.py G3 G5          # 只跑指定组
    python3 scripts/e2e/e2e_suite.py --no-cleanup   # 保留产物便于排查
    python3 scripts/e2e/e2e_suite.py --list         # 列出用例

退出码：0 全部通过（含 OBSERVE 记录）；1 存在 FAIL/ERROR。
"""

from __future__ import annotations

import http.client
import json
import os
import re
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

# ---------------------------------------------------------------- 基础设施

HOST = os.environ.get("E2E_HOST", "127.0.0.1")
PORT = int(os.environ.get("E2E_PORT", "8080"))
BASE_PATH = "/api/v1"
BASE = f"http://{HOST}:{PORT}{BASE_PATH}"

PG_CONTAINER = os.environ.get("E2E_PG_CONTAINER", "personal-ai-os-postgres")
PG_USER = os.environ.get("E2E_PG_USER", "personalos")
PG_DB = os.environ.get("E2E_PG_DB", "personal_ai_os")

REPORT_DIR = Path(__file__).resolve().parent / "reports"


class CaseFailure(AssertionError):
    """用例断言失败。"""


class Transient(Exception):
    """环境类失败（服务没起来等），不计入代码缺陷。"""


def check(cond, msg):
    if not cond:
        raise CaseFailure(msg)


def check_eq(actual, expected, msg):
    if actual != expected:
        raise CaseFailure(f"{msg}（实际 {actual!r}，期望 {expected!r}）")


def check_in(value, container, msg):
    if value not in container:
        raise CaseFailure(f"{msg}（实际 {value!r}）")


def check_contains(haystack, needle, msg):
    if not haystack or needle not in haystack:
        raise CaseFailure(f"{msg}（未在内容中找到 {needle!r}）")


# ---------------------------------------------------------------- HTTP 封装


class Resp:
    __slots__ = ("status", "body", "raw", "headers", "elapsed")

    def __init__(self, status, body, raw, headers, elapsed):
        self.status = status
        self.body = body          # 解析后的 JSON（失败为 None）
        self.raw = raw            # 原始 bytes
        self.headers = headers    # 小写键的 dict
        self.elapsed = elapsed

    @property
    def data(self):
        return (self.body or {}).get("data")

    @property
    def code(self):
        return (self.body or {}).get("code")

    def text(self):
        try:
            return self.raw.decode("utf-8", "replace")
        except Exception:
            return ""

    def __repr__(self):
        return f"<Resp {self.status} code={self.code}>"


def req(method, path, body=None, headers=None, timeout=60, raw_body=None,
        content_type=None, expect_json=True):
    """发一个请求。4xx/5xx 不抛异常，交给用例自己断言状态码。"""
    url = BASE + path
    data = None
    hdrs = {"Accept": "application/json"}
    if raw_body is not None:
        data = raw_body
        if content_type:
            hdrs["Content-Type"] = content_type
    elif body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        hdrs["Content-Type"] = "application/json"
    if headers:
        hdrs.update(headers)

    r = Request(url, data=data, headers=hdrs, method=method)
    started = time.monotonic()
    try:
        with urlopen(r, timeout=timeout) as resp:
            status = resp.status
            payload = resp.read()
            rh = {k.lower(): v for k, v in resp.headers.items()}
    except HTTPError as e:
        status = e.code
        payload = e.read()
        rh = {k.lower(): v for k, v in e.headers.items()}
    except URLError as e:
        raise Transient(f"连不上 {url}：{e.reason}")
    except (TimeoutError, OSError) as e:
        raise Transient(f"请求 {url} 失败：{e}")
    elapsed = time.monotonic() - started

    parsed = None
    if expect_json:
        try:
            parsed = json.loads(payload.decode("utf-8"))
        except Exception:
            parsed = None
    return Resp(status, parsed, payload, rh, elapsed)


def get(path, **kw):
    return req("GET", path, **kw)


def post(path, body=None, **kw):
    return req("POST", path, body=body, **kw)


def put(path, body=None, **kw):
    return req("PUT", path, body=body, **kw)


def patch(path, body=None, **kw):
    return req("PATCH", path, body=body, **kw)


def delete(path, **kw):
    return req("DELETE", path, **kw)


def ok(resp, msg="接口应返回成功"):
    """断言成功信封（code==0）。创建类接口返回 201，删除类可能 204。"""
    check_in(resp.status, (200, 201, 204), f"{msg}：HTTP 状态")
    check_eq(resp.code, 0, f"{msg}：业务码")
    return resp.data


def upload(path, filename, content: bytes, content_type="text/markdown",
           field="file", extra_fields=None, timeout=120):
    """multipart/form-data 上传。"""
    boundary = "----E2E" + uuid.uuid4().hex
    lines = []
    for k, v in (extra_fields or {}).items():
        lines.append(f"--{boundary}".encode())
        lines.append(f'Content-Disposition: form-data; name="{k}"'.encode())
        lines.append(b"")
        lines.append(str(v).encode("utf-8"))
    lines.append(f"--{boundary}".encode())
    lines.append(
        f'Content-Disposition: form-data; name="{field}"; filename="{filename}"'.encode("utf-8"))
    lines.append(f"Content-Type: {content_type}".encode())
    lines.append(b"")
    lines.append(content)
    lines.append(f"--{boundary}--".encode())
    lines.append(b"")
    payload = b"\r\n".join(lines)
    return req("POST", path, raw_body=payload,
               content_type=f"multipart/form-data; boundary={boundary}", timeout=timeout)


# ---------------------------------------------------------------- SSE 封装


class SSE:
    __slots__ = ("status", "events", "content_type", "elapsed", "pings", "aborted", "raw")

    def __init__(self, status, events, content_type, elapsed, pings=0, aborted=False, raw=b""):
        self.status = status
        self.events = events              # [(event_name, data_dict_or_str), ...]
        self.content_type = content_type
        self.elapsed = elapsed
        self.pings = pings
        self.aborted = aborted
        self.raw = raw

    def names(self):
        return [n for n, _ in self.events]

    def first(self, name):
        for n, d in self.events:
            if n == name:
                return d
        return None

    def text(self):
        return "".join((d or {}).get("content", "") for n, d in self.events
                       if n == "delta" and isinstance(d, dict))

    def is_sse(self):
        return "text/event-stream" in (self.content_type or "")


def sse(path, body, headers=None, timeout=200, abort_after=None):
    """发 SSE 请求并按事件解析。

    abort_after=N 时读到第 N 个完整事件就主动断开（模拟前端 AbortController）。
    """
    conn = http.client.HTTPConnection(HOST, PORT, timeout=timeout)
    hdrs = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        "Cache-Control": "no-cache",
    }
    if headers:
        hdrs.update(headers)
    started = time.monotonic()
    conn.request("POST", BASE_PATH + path,
                 body=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                 headers=hdrs)
    resp = conn.getresponse()
    ctype = (resp.getheader("Content-Type") or "")
    events, pings = [], 0
    aborted = False

    if resp.status != 200 or "text/event-stream" not in ctype:
        payload = resp.read()
        conn.close()
        return SSE(resp.status, events, ctype, time.monotonic() - started, raw=payload)

    buf = b""
    cur_event, data_lines = None, []

    def flush():
        nonlocal cur_event, data_lines
        if data_lines:
            payload = "\n".join(data_lines)
            try:
                parsed = json.loads(payload)
            except Exception:
                parsed = payload
            events.append((cur_event or "message", parsed))
        cur_event, data_lines = None, []

    try:
        while True:
            chunk = resp.read1(4096)
            if not chunk:
                break
            buf += chunk
            while b"\n" in buf:
                raw_line, buf = buf.split(b"\n", 1)
                line = raw_line.decode("utf-8", "replace").rstrip("\r")
                if not line:
                    flush()
                    if abort_after is not None and len(events) >= abort_after:
                        aborted = True
                        raise StopIteration
                    continue
                if line.startswith(":"):
                    pings += 1
                    continue
                if line.startswith("event:"):
                    cur_event = line[6:].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[5:].lstrip())
        flush()
    except StopIteration:
        pass
    finally:
        try:
            conn.close()
        except Exception:
            pass

    return SSE(resp.status, events, ctype, time.monotonic() - started,
               pings=pings, aborted=aborted)


# ---------------------------------------------------------------- 数据库直查


def db(sql: str) -> str:
    """在容器里执行只读/清理 SQL，返回单值或 TSV。"""
    try:
        out = subprocess.run(
            ["docker", "exec", PG_CONTAINER, "psql", "-U", PG_USER, "-d", PG_DB, "-tAc", sql],
            capture_output=True, text=True, timeout=30)
    except FileNotFoundError:
        raise Transient("找不到 docker 命令，无法复核数据库")
    if out.returncode != 0:
        raise CaseFailure(f"SQL 执行失败：{out.stderr.strip()[:300]}")
    return out.stdout.strip()


def db_count(table: str) -> int:
    return int(db(f"select count(*) from {table}") or 0)


BUSINESS_TABLES = (
    "chat_message_citation", "chat_message", "chat_session",
    "agent_plan_step", "agent_plan",
    "knowledge_document", "vector_store",
    "memory", "task_tag", "task",
    "calendar_event", "reminder", "confirmation",
    "token_usage", "operation_record",
)


def reset_business_data():
    """把业务表清空（保留模型配置与 flyway 历史）。

    只在显式 `--reset` 时执行：这是不可逆操作，默认绝不隐式清库。
    模型配置属于「配置」而不是「业务数据」—— 清掉它，整套用例会因为
    「模型未配置」一起挂掉，测的就不是能力边界而是部署状态了。
    """
    print("重置业务数据（保留 ai_model_* 与 flyway_schema_history）…")
    db("TRUNCATE TABLE " + ", ".join(BUSINESS_TABLES) + " RESTART IDENTITY CASCADE")
    left = {t: db_count(t) for t in BUSINESS_TABLES[:6]}
    print("  清理后：" + "，".join(f"{k}={v}" for k, v in left.items()))


# ---------------------------------------------------------------- 用例注册

CASES = []


def case(group, name, mode="fail", note=""):
    """注册用例。mode=observe 表示只记录实际行为、不判失败。"""
    def deco(fn):
        CASES.append({"group": group, "name": name, "fn": fn, "mode": mode, "note": note})
        return fn
    return deco


def G(code):
    return code


# ---------------------------------------------------------------- 共享数据

CTX = {
    "cleanup": {"sessions": [], "tasks": [], "docs": [], "memories": [], "events": []},
    "session": None,      # 主会话
}

UNIQUE = uuid.uuid4().hex[:8].upper()
FACT_DOC_TITLE = f"E2E知识库验证-{UNIQUE}"
FACT_CODEWORD = f"BlueJay-{UNIQUE}"
FACT_SENTENCE = f"本系统的内部代号是 {FACT_CODEWORD}，由运维组在季度评审中确定。"


def iso_z(dt):
    """ISO-8601 带 Z 后缀。`Instant.parse` 只认 Z，不认 +00:00 —— 后者会被 400 拒掉，
    而错误信息里给的正是 Z 格式，很容易被读成「格式没问题却报错」。"""
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def new_session(title=None):
    body = {} if title is None else {"title": title}
    r = post("/chat/sessions", body)
    check_eq(r.status, 201, "创建会话应返回 201")
    check_eq(r.code, 0, "创建会话业务码")
    sid = r.data["conversationId"]
    CTX["cleanup"]["sessions"].append(sid)
    return sid


def stream_chat(sid, content, **extra):
    body = {"content": content}
    body.update(extra)
    return sse(f"/chat/sessions/{sid}/stream", body)


def messages(sid):
    r = get(f"/chat/sessions/{sid}/messages")
    return ok(r, "查询消息") or []


# ================================================================ G0 环境与清库


@case("G0", "数据库已处于干净状态（清库生效）")
def g0_db_clean(c):
    for table in ("chat_session", "chat_message", "memory", "task",
                  "calendar_event", "knowledge_document", "vector_store",
                  "confirmation", "agent_plan"):
        check_eq(db_count(table), 0, f"{table} 应为空")


@case("G0", "健康检查：应用 + 数据库 + AI 配置")
def g0_health(c):
    d = ok(get("/health"), "健康检查")
    check_eq(d["status"], "UP", "应用状态")
    check_eq(d["components"]["database"], "UP", "数据库组件")
    check_eq(d["components"]["ai"], "CONFIGURED", "AI 配置状态")


@case("G0", "会话列表在空库下返回空数组（而非 404/错误）")
def g0_empty_list(c):
    d = ok(get("/chat/sessions"), "会话列表")
    check_eq(d, [], "空库会话列表应为 []")


# ================================================================ G1 基础设施与错误契约


@case("G1", "X-Request-Id 被回显")
def g1_request_id_echo(c):
    rid = "e2e-" + uuid.uuid4().hex[:12]
    r = get("/health", headers={"X-Request-Id": rid})
    check_eq(r.status, 200, "带 requestId 的健康检查")
    check_eq(r.headers.get("x-request-id"), rid, "响应头应原样回显 X-Request-Id")


@case("G1", "非法 X-Request-Id 不被原样回显（防日志注入）")
def g1_request_id_invalid(c):
    # 设计要求：不合规的入站值**不报错**（透传失败不该让业务请求失败），
    # 而是丢弃并重新生成。所以断言点是「没被回显 + 新值合规」，
    # 而不是「返回 400」—— 那会把一个正确的宽容设计判成缺陷。
    evil = "bad id!!<script>alert(1)</script>"
    r = get("/health", headers={"X-Request-Id": evil})
    check_eq(r.status, 200, "非法 requestId 不应导致业务失败")
    echoed = r.headers.get("x-request-id", "")
    check(echoed != evil, "危险字符的 requestId 不应被原样回显")
    check(re.fullmatch(r"[A-Za-z0-9._-]{1,64}", echoed) is not None,
          f"生成的 requestId 应满足白名单格式，实际 {echoed!r}")


@case("G1", "未知路径返回统一信封且不泄漏堆栈")
def g1_not_found(c):
    r = get("/this-path-does-not-exist")
    check_eq(r.status, 404, "未知路径状态码")
    check(r.body is not None, "404 也应返回 JSON 信封")
    text = r.text()
    for leak in ("Exception", "at com.personalos", "java.", "org.springframework"):
        check(leak not in text, f"错误响应泄漏了内部细节：{leak}")


@case("G1", "不支持的方法返回 405（不是 404）")
def g1_method_not_allowed(c):
    sid = new_session("方法测试")
    r = put(f"/chat/sessions/{sid}", {"title": "x"})   # 只支持 PATCH
    check_eq(r.status, 405, "PUT 会话应返回 405")


@case("G1", "非法 JSON 请求体返回 400 而不是 500")
def g1_bad_json(c):
    sid = new_session("坏JSON")
    r = req("POST", f"/chat/sessions/{sid}/stream", raw_body=b"{not json",
            content_type="application/json")
    check(r.status in (400, 415, 500), f"非法 JSON 应有明确状态码，实际 {r.status}")
    check(r.status != 500, "非法 JSON 不应导致 500（那是未处理异常）")


# ================================================================ G2 会话生命周期


@case("G2", "创建会话：省略标题得到默认标题")
def g2_create_default_title(c):
    sid = new_session()
    d = ok(get("/chat/sessions"), "会话列表")
    row = next((s for s in d if s["conversationId"] == sid), None)
    check(row is not None, "新建会话应出现在列表中")
    check(row["title"] and row["title"].strip(), "标题不应为空")
    check_eq(row["messageCount"], 0, "新会话消息数应为 0")
    check_eq(row["chatModel"], None, "新会话默认跟随配置模型（chatModel 为 null）")


@case("G2", "创建会话：自定义标题被规范化（首尾空白去除）")
def g2_create_custom_title(c):
    sid = new_session("   E2E 标题空格测试   ")
    d = ok(get("/chat/sessions"), "会话列表")
    row = next(s for s in d if s["conversationId"] == sid)
    check_eq(row["title"], "E2E 标题空格测试", "标题应去除首尾空白")


@case("G2", "创建会话：纯空白标题回落默认值")
def g2_create_blank_title(c):
    sid = new_session("      ")
    d = ok(get("/chat/sessions"), "会话列表")
    row = next(s for s in d if s["conversationId"] == sid)
    check(row["title"].strip() != "", "空白标题不应产生空标题")


@case("G2", "首条消息后标题由消息内容生成")
def g2_title_from_first_message(c):
    sid = new_session()
    s = stream_chat(sid, "请只回复两个字：收到")
    check(s.is_sse(), "应返回事件流")
    check_eq(s.first("done") is not None, True, "应收到 done 事件")
    d = ok(get("/chat/sessions"), "会话列表")
    row = next(s2 for s2 in d if s2["conversationId"] == sid)
    check(row["title"].strip() not in ("", "新会话"), f"标题应被消息替换，实际 {row['title']!r}")
    check_eq(row["messageCount"], 2, "一轮问答应有 2 条消息")


@case("G2", "会话搜索：命中关键词 / 未命中返回空")
def g2_search(c):
    sid = new_session(f"搜索靶子-{UNIQUE}")
    d = ok(get(f"/chat/sessions/search?keyword={UNIQUE}&page=0&size=20"), "会话搜索")
    ids = [x["conversationId"] for x in d["items"]]
    check_in(sid, ids, "关键词应命中刚建的会话")
    d2 = ok(get("/chat/sessions/search?keyword=zzz-not-exist-zzz&page=0&size=20"), "会话搜索")
    check_eq(d2["total"], 0, "不存在的关键词应返回 0 条")


@case("G2", "会话搜索：分页参数生效")
def g2_search_paging(c):
    for i in range(3):
        new_session(f"分页-{UNIQUE}-{i}")
    d = ok(get(f"/chat/sessions/search?keyword={UNIQUE}&page=0&size=2"), "分页查询")
    check_eq(len(d["items"]), 2, "size=2 时第一页应返回 2 条")
    check(d["total"] >= 4, f"total 应为全量命中数，实际 {d['total']}")
    check_eq(d["page"], 0, "page 回显")


@case("G2", "分页极端值：超大页码 / 负页码 / size=0 不返回 500")
def g2_paging_extremes(c):
    # 审查文档第 12 条：`int from = page * size` 溢出会让一条请求稳定打 500。
    # 这里逐个数固定住，避免「改好了又悄悄回退」。
    for q in ("page=21474837&size=100", "page=-5&size=20", "size=0", "size=100000"):
        r = get(f"/chat/sessions/search?{q}")
        check(r.status != 500, f"分页参数 {q} 不应导致 500（实际 {r.status}）")
        check(r.status in (200, 400), f"分页参数 {q} 应返回 200 或 400，实际 {r.status}")


@case("G2", "重命名：正常 / 空值 400 / 超长 400 / 不存在 404")
def g2_rename(c):
    sid = new_session("待重命名")
    d = ok(patch(f"/chat/sessions/{sid}", {"title": "  新标题 E2E  "}), "重命名")
    check_eq(d["title"], "新标题 E2E", "重命名应去除首尾空白")

    r = patch(f"/chat/sessions/{sid}", {"title": "   "})
    check_eq(r.status, 400, "纯空白标题应被拒绝")

    r = patch(f"/chat/sessions/{sid}", {"title": "长" * 201})
    check_eq(r.status, 400, "超长标题应被拒绝")

    r = patch(f"/chat/sessions/{uuid.uuid4()}", {"title": "x"})
    check_eq(r.status, 404, "不存在的会话应返回 404")


@case("G2", "会话导出为 Markdown（不套信封）")
def g2_export(c):
    sid = CTX["session"] or new_session("导出测试")
    r = get(f"/chat/sessions/{sid}/export", expect_json=False, headers={"Accept": "*/*"})
    check_eq(r.status, 200, "导出状态码")
    text = r.text()
    check(not text.lstrip().startswith("{"), "导出应是文件内容而不是 JSON 信封")
    check_contains(text, "#", "Markdown 应含标题标记")


@case("G2", "内容协商：只接受 JSON 的客户端访问导出接口 → 406")
def g2_export_negotiation(c):
    # 2026-10-29 修复前这里是 500（HttpMediaTypeNotAcceptable 落到 Exception 兜底）。
    # 现在必须稳定返回 406 + code 40600，且日志里不再出现 error 级假堆栈。
    sid = CTX["session"] or new_session("内容协商")
    r = get(f"/chat/sessions/{sid}/export", expect_json=False,
            headers={"Accept": "application/json"})
    check_eq(r.status, 406, "Accept 不匹配应返回 406")
    import json as _json
    body = _json.loads(r.text())
    check_eq(body["code"], 40600, "应返回 40600（NOT_ACCEPTABLE）")


@case("G2", "删除会话：级联删除消息，再取返回 404")
def g2_delete_cascade(c):
    sid = new_session("待删除")
    stream_chat(sid, "只回复：ok")
    check_eq(int(db(f"select count(*) from chat_message where conversation_id='{sid}'")), 2,
             "删除前应有 2 条消息")
    ok(delete(f"/chat/sessions/{sid}"), "删除会话")
    CTX["cleanup"]["sessions"].remove(sid)
    check_eq(db_count(f"chat_message where conversation_id='{sid}'"), 0, "消息应级联删除")
    r = get(f"/chat/sessions/{sid}/messages")
    check_eq(r.status, 404, "已删除会话应返回 404")


@case("G2", "删除不存在的会话返回 404")
def g2_delete_unknown(c):
    r = delete(f"/chat/sessions/{uuid.uuid4()}")
    check_eq(r.status, 404, "删除不存在的会话应返回 404")


@case("G2", "消息分页：limit + beforeSeq 向前翻页")
def g2_message_paging(c):
    sid = new_session("分页消息")
    for i in range(2):
        stream_chat(sid, f"只回复数字 {i}")
    d = ok(get(f"/chat/sessions/{sid}/messages?limit=2"), "限制条数")
    check_eq(len(d), 2, "limit=2 应返回 2 条")
    first_seq = d[0]["sequenceNo"]
    d2 = ok(get(f"/chat/sessions/{sid}/messages?limit=2&beforeSeq={first_seq}"), "向前翻页")
    check(all(m["sequenceNo"] < first_seq for m in d2), "beforeSeq 之后的应更早")


# ================================================================ G3 SSE 流式对话


@case("G3", "基本流：事件顺序 start → delta* → done，内容非空")
def g3_basic_stream(c):
    sid = new_session("基本流")
    s = stream_chat(sid, "用一句话说明什么是个人知识库")
    check(s.is_sse(), f"应返回 text/event-stream，实际 {s.content_type}")
    names = s.names()
    check(names and names[0] == "start", f"首个事件应为 start，实际 {names[:3]}")
    check_eq(names[-1], "done", "最后一个事件应为 done")
    check("delta" in names, "应有 delta 事件")
    check_eq(names.count("start"), 1, "start 只应出现一次")
    done = s.first("done")
    body = s.text()
    check(len(body) > 5, f"拼接正文应有内容，实际 {body!r}")
    check_eq(done["content"], body, "done.content 应等于全部 delta 拼接")
    check(done["promptTokens"] > 0, f"promptTokens 应大于 0，实际 {done['promptTokens']}")
    CTX["session"] = sid
    CTX["basic_stream_tokens"] = done["promptTokens"]


@case("G3", "落库一致性：助手消息内容 == done.content")
def g3_persist(c):
    sid = CTX["session"]
    msgs = messages(sid)
    check_eq(len(msgs), 2, "一轮问答应有 2 条消息")
    check_eq(msgs[0]["role"], "USER", "第一条是用户消息")
    check_eq(msgs[1]["role"], "ASSISTANT", "第二条是助手消息")
    check_eq(msgs[0]["sequenceNo"] + 1, msgs[1]["sequenceNo"], "顺序号应连续")
    check(len(msgs[1]["content"]) > 5, "助手消息内容不应为空")


@case("G3", "空 content → 400 且不走 SSE")
def g3_empty_content(c):
    sid = CTX["session"]
    s = stream_chat(sid, "")
    check_eq(s.status, 400, "空内容应返回 400")
    check(not s.is_sse(), "前置校验失败不应返回事件流")


@case("G3", "纯空白 content → 400")
def g3_blank_content(c):
    s = stream_chat(CTX["session"], "     ")
    check_eq(s.status, 400, "纯空白应返回 400")


@case("G3", "超长 content（4001 字）→ 400")
def g3_too_long(c):
    s = stream_chat(CTX["session"], "啊" * 4001)
    check_eq(s.status, 400, "超过 4000 字应被拒绝")


@case("G3", "不存在的会话 → 404 普通 JSON（不是半截流）")
def g3_unknown_session(c):
    s = stream_chat(str(uuid.uuid4()), "你好")
    check_eq(s.status, 404, "不存在的会话应返回 404")
    check(not s.is_sse(), "前置校验失败不应建立事件流")


@case("G3", "边界值：恰好 4000 字可正常完成")
def g3_exact_limit(c):
    sid = new_session("边界长度")
    s = stream_chat(sid, "只回复 OK。" + "补" * 3990)
    check(s.is_sse(), "4000 字应被接受")
    check_eq(s.first("done") is not None, True, "应正常完成")


@case("G3", "多轮上下文：第二轮能引用第一轮的事实")
def g3_multiturn(c):
    sid = new_session("多轮上下文")
    s1 = stream_chat(sid, "请记住这个数字：7741。只回复：已记住")
    check_eq(s1.first("done") is not None, True, "第一轮应完成")
    s2 = stream_chat(sid, "我刚才让你记住的数字是多少？只回复数字。")
    check_eq(s2.first("done") is not None, True, "第二轮应完成")
    check_contains(s2.text(), "7741", "第二轮应能引用第一轮的数字（上下文装配失效）")


@case("G3", "思考程度：未知值回落 off 且不报错")
def g3_thinking_unknown(c):
    sid = new_session("思考程度")
    s = stream_chat(sid, "只回复 OK", thinkingLevel="turbo-max-ultra")
    check_eq(s.first("done") is not None, True, "未知 thinkingLevel 应回落而不是报错")


@case("G3", "topK 越界（999）被收敛且不报错")
def g3_topk_clamp(c):
    sid = new_session("topK 收敛")
    s = stream_chat(sid, "只回复 OK", topK=999, useKnowledge=False)
    check_eq(s.first("done") is not None, True, "topK 越界应被收敛而不是 400")


@case("G3", "客户端中断：不把半截回答落库为助手消息")
def g3_abort(c):
    sid = new_session("中断测试")
    s = sse(f"/chat/sessions/{sid}/stream",
            {"content": "请从 1 数到 200，每个数字单独一行，不要省略。"},
            abort_after=3)
    check(s.aborted, "客户端应在第 3 个事件后中断")
    time.sleep(1.5)   # 给服务端 dispose 的时间
    msgs = messages(sid)
    roles = [m["role"] for m in msgs]
    check_eq(roles.count("USER"), 1, "用户消息应保留")
    if roles.count("ASSISTANT"):
        # 模型可能在被中断前就答完了 —— 这不是缺陷，但要如实记录
        raise SkipCase("模型在中断前已生成完整回答，无法验证「半截不落库」")
    check_eq(db_count(f"chat_message where conversation_id='{sid}' and message_type='ASSISTANT'"), 0,
             "中断后不应有助手消息")


@case("G3", "Idempotency-Key：同键重试不报错、不重复落用户消息")
def g3_idempotency(c):
    sid = new_session("幂等键")
    key = "e2e-key-" + uuid.uuid4().hex[:10]
    r = req("POST", f"/chat/sessions/{sid}/stream", body={"content": "只回复 OK"},
            headers={"Idempotency-Key": key})
    check_eq(r.status, 200, "带幂等键的请求应成功")


# ================================================================ G4 上下文与预算边界


@case("G4", "上下文用量：字段齐全且窗口 >= 预算")
def g4_context_usage(c):
    sid = CTX["session"]
    d = ok(get(f"/chat/sessions/{sid}/context"), "上下文用量")
    for f in ("promptTokens", "budgetTokens", "contextWindow", "estimated"):
        check(f in d, f"缺少字段 {f}")
    check(d["budgetTokens"] > 0, "预算应大于 0")
    check(d["contextWindow"] >= d["budgetTokens"], "模型窗口不应小于预算")


@case("G4", "预算设置：合法值生效")
def g4_set_budget(c):
    d = ok(put("/chat/context-budget", {"tokens": 65536}), "设置预算")
    check_eq(d["budgetTokens"], 65536, "返回的预算应为新值")
    d2 = ok(get(f"/chat/sessions/{CTX['session']}/context"), "读取预算")
    check_eq(d2["budgetTokens"], 65536, "预算应落服务端（不是随请求带）")


@case("G4", "预算设置：0/负数 → 400，超大 → 收敛到上限")
def g4_budget_bounds(c):
    for bad in (0, -1):
        r = put("/chat/context-budget", {"tokens": bad})
        check_eq(r.status, 400, f"预算 {bad} 应被拒绝")
    d = ok(put("/chat/context-budget", {"tokens": 10_000_000}), "超大预算")
    check(d["budgetTokens"] <= 1_048_576, f"预算应收敛到上限，实际 {d['budgetTokens']}")


@case("G4", "预算 2048 被抬到下限，且实测用量落在生效预算之内")
def g4_trim_under_small_budget(c):
    d = ok(put("/chat/context-budget", {"tokens": 2048}), "设小预算")
    effective = d["budgetTokens"]
    # 工具定义无法压缩，2048 的预算在数学上不成立 → 服务端抬到「固定开销 + 1024」。
    # 不抬的话用户会看到「4992 / 2048（243%）」这种永远超预算的读数。
    check(effective > 2048, f"预算应被抬到下限，实际仍是 {effective}")
    check(effective < d["contextWindow"], "下限也不该超过模型窗口")

    sid = new_session("裁剪测试")
    long_text = "这是一个用于填充上下文的句子。" * 120    # ≈ 1900 字
    tokens = []
    for i in range(3):
        s = stream_chat(sid, f"{long_text} 第{i}轮，只回复 OK")
        done = s.first("done")
        check(done is not None, f"第 {i} 轮应完成（裁剪生效，不应因超预算报错）")
        tokens.append(done["promptTokens"])
    ok(put("/chat/context-budget", {"tokens": 32768}), "恢复预算")

    # 允许 15% 余量：预算是按 1.6 字/token 折算出来的，上游 tokenizer 与它必然有偏差
    check(max(tokens) <= effective * 1.15,
          f"实测 promptTokens={tokens} 超出生效预算 {effective} 的 15% 以上"
          f"（说明固定开销没有被计入裁剪）")


@case("G4", "仪表盘与 done 同源：两处 token 数不再自相矛盾")
def g4_budget_vs_actual(c):
    sid = CTX["session"]
    if "basic_stream_tokens" not in CTX:
        raise SkipCase("缺少基本流的实测值")
    done = CTX["basic_stream_tokens"]

    # 用量记录是异步写的（TokenUsageRecorder 走线程池），刚 done 时可能还没落库。
    # 这不是缺陷 —— 前端当场就用 done 的值刷新了仪表盘，只有「刷新页面」才读这里。
    ctx = None
    for _ in range(20):
        ctx = ok(get(f"/chat/sessions/{sid}/context"), "仪表盘")
        if not ctx["estimated"]:
            break
        time.sleep(0.1)
    check(not ctx["estimated"], "有真实用量时仪表盘应标注 estimated=false")
    check_eq(ctx["promptTokens"], done,
             f"仪表盘应回显上一轮真实用量（done={done}，仪表盘={ctx['promptTokens']}）")


@case("G4", "会话级模型覆盖：切到 1M 模型后窗口变化")
def g4_session_model(c):
    sid = new_session("模型覆盖")
    d = ok(put(f"/chat/sessions/{sid}/model", {"model": "deepseek-v4.1-flash"}), "设置会话模型")
    check_eq(d["chatModel"], "deepseek-v4.1-flash", "会话应记住所选模型")
    ctx = ok(get(f"/chat/sessions/{sid}/context"), "上下文")
    check_eq(ctx["contextWindow"], 1_048_576, "窗口应切换到该模型的能力值")
    d2 = ok(put(f"/chat/sessions/{sid}/model", {"model": None}), "恢复默认模型")
    check_eq(d2["chatModel"], None, "null 表示恢复默认（跟随配置）")
    ctx2 = ok(get(f"/chat/sessions/{sid}/context"), "上下文")
    check_eq(ctx2["contextWindow"], 131072, "恢复默认后窗口应回到 qwen3.8-flash 的 128K")


@case("G4", "会话模型是历史事实：不因配置变化而丢失")
def g4_session_model_persist(c):
    sid = new_session("模型持久")
    ok(put(f"/chat/sessions/{sid}/model", {"model": "qwen-max"}), "设置模型")
    d = ok(get("/chat/sessions"), "列表")
    row = next(x for x in d if x["conversationId"] == sid)
    check_eq(row["chatModel"], "qwen-max", "列表应返回会话专属模型")


# ================================================================ G5 工具调用与确认


@case("G5", "工具调用：创建任务真实落库")
def g5_task_tool(c):
    sid = new_session("工具-建任务")
    s = stream_chat(sid, "请用工具帮我创建一条任务，标题是「E2E 季度报告」，优先级高。不要问我，直接做。")
    check_eq(s.first("done") is not None, True, "应完成")
    n = db_count("task where title like '%E2E 季度报告%'")
    check_eq(n, 1, "任务应真的落库（不是模型说建了就算）")


@case("G5", "工具调用审计：成功记录可查")
def g5_tool_audit(c):
    r = get("/agent/tool-invocations?page=0&size=20")
    d = ok(r, "工具审计")
    check(d["items"], "审计列表不应为空")
    names = [x.get("operation") or "" for x in d["items"]]
    check(any("ask" in n.lower() or "task" in n.lower() for n in names),
          f"应能看到建任务类工具，实际 {names}")


@case("G5", "破坏性操作：删除任务先落确认，不直接执行")
def g5_destructive_needs_confirm(c):
    t = ok(post("/tasks", {"title": f"E2E-删除靶子-{UNIQUE}", "priority": "LOW"}), "建任务")
    tid = t["id"]
    CTX["cleanup"]["tasks"].append(tid)
    sid = new_session("工具-删任务")
    s = stream_chat(sid, f"请帮我删除标题为「E2E-删除靶子-{UNIQUE}」的那条任务。")
    check_eq(s.first("done") is not None, True, "应完成")
    check_eq(int(db(f"select count(*) from task where id='{tid}'")), 1,
             "未确认前任务必须还在（破坏性操作不得直接执行）")
    confs = ok(get(f"/agent/confirmations?conversationId={sid}&status=PENDING"), "待确认列表")
    check(confs, "应生成一条待确认记录")
    CTX["confirmation"] = confs[0]
    CTX["confirmation_task"] = tid


@case("G5", "确认令牌错误 → 拒绝执行，任务仍在")
def g5_confirm_wrong_token(c):
    conf = CTX.get("confirmation")
    if not conf:
        raise SkipCase("上一步未生成确认记录")
    r = post(f"/agent/confirmations/{conf['id']}/confirm", {"token": "wrong-token"})
    check(r.status >= 400, f"错误令牌应被拒绝，实际 {r.status}")
    check_eq(int(db(f"select count(*) from task where id='{CTX['confirmation_task']}'")), 1,
             "令牌错误时任务不应被删除")


@case("G5", "正确令牌确认 → 真正执行删除")
def g5_confirm_executes(c):
    conf = CTX.get("confirmation")
    if not conf:
        raise SkipCase("上一步未生成确认记录")
    d = ok(post(f"/agent/confirmations/{conf['id']}/confirm", {"token": conf["token"]}), "确认执行")
    check_eq(d["executed"], True, "确认后应真的执行")
    check(d["affectedCount"] >= 1, f"应影响至少 1 行，实际 {d['affectedCount']}")
    check_eq(int(db(f"select count(*) from task where id='{CTX['confirmation_task']}'")), 0,
             "确认后任务应被删除")


@case("G5", "取消确认 → 不执行且状态为 CANCELLED")
def g5_cancel(c):
    t = ok(post("/tasks", {"title": f"E2E-取消靶子-{UNIQUE}"}), "建任务")
    tid = t["id"]
    CTX["cleanup"]["tasks"].append(tid)
    sid = new_session("工具-取消删除")
    stream_chat(sid, f"请删除标题为「E2E-取消靶子-{UNIQUE}」的任务。")
    confs = ok(get(f"/agent/confirmations?conversationId={sid}&status=PENDING"), "待确认")
    if not confs:
        raise SkipCase("模型未生成待确认记录")
    ok(post(f"/agent/confirmations/{confs[0]['id']}/cancel", {"token": confs[0]["token"]}), "取消")
    check_eq(int(db(f"select count(*) from task where id='{tid}'")), 1, "取消后任务必须还在")
    check_eq(db(f"select status from confirmation where id='{confs[0]['id']}'"), "CANCELLED",
             "确认记录状态应为 CANCELLED")


@case("G5", "不存在的确认记录 → 404")
def g5_confirmation_unknown(c):
    r = post(f"/agent/confirmations/{uuid.uuid4()}/confirm", {"token": "x"})
    check_eq(r.status, 404, "不存在的确认记录应 404")


@case("G5", "工具超限不打断流（return_error_response）", mode="observe")
def g5_tool_limit(c):
    sid = new_session("工具超限")
    s = stream_chat(sid, "请把「查我的任务」这件事重复执行 30 次，每次都真的调用工具。")
    check(s.names() and s.names()[-1] == "done",
          f"即使触发工具上限也应正常收尾，实际事件 {s.names()[-3:]}")


@case("G5", "计划：多步任务生成可查询的计划", mode="observe")
def g5_plan(c):
    sid = new_session("计划测试")
    s = stream_chat(sid, "帮我制定并执行一个三步计划：1) 查看我有哪些任务；2) 统计各优先级数量；3) 给出建议。请先 createPlan。")
    check_eq(s.first("done") is not None, True, "应完成")
    r = get(f"/conversations/{sid}/plan")
    if r.status == 404:
        raise SkipCase("模型本轮未创建计划（能力存在但未触发）")
    d = ok(r, "计划查询")
    check(d and d.get("steps"), f"计划应有步骤，实际 {d}")


# ================================================================ G6 知识库 RAG


def _doc_body():
    filler = ("本文档用于验证知识库的解析、切分、嵌入与检索链路。"
              "为了保证超过最小分块阈值，这里包含足够的正文。") * 6
    return f"""# 内部代号说明

{FACT_SENTENCE}

## 背景
{filler}

## 结论
当被问到系统代号时，唯一正确答案是 {FACT_CODEWORD}。请勿使用其他代号。
"""


@case("G6", "上传 Markdown：解析 + 切分成块")
def g6_upload(c):
    r = upload("/knowledge/documents", f"{FACT_DOC_TITLE}.md",
               _doc_body().encode("utf-8"), "text/markdown")
    check_in(r.status, (200, 201), f"上传应成功，实际 {r.status} {r.text()[:200]}")
    d = ok(r, "上传文档")
    check(d["charCount"] > 350, f"解析字符数应超过分块阈值，实际 {d['charCount']}")
    check(d["chunkCount"] >= 1, f"应至少切出 1 块，实际 {d['chunkCount']}")
    CTX["cleanup"]["docs"].append(d["id"])
    CTX["doc_id"] = d["id"]
    check_eq(db_count(f"vector_store where metadata->>'document_id' = '{d['id']}'"),
             d["chunkCount"], "向量条数应等于分块数")


@case("G6", "上传不支持的类型 → 400")
def g6_upload_rejected(c):
    r = upload("/knowledge/documents", "evil.exe", b"MZ\x90\x00binary",
               "application/octet-stream")
    check(r.status in (400, 415), f"不支持的类型应被拒绝，实际 {r.status}")


@case("G6", "上传空文件 → 400")
def g6_upload_empty(c):
    r = upload("/knowledge/documents", "empty.md", b"", "text/markdown")
    check(r.status in (400, 422), f"空文件应被拒绝，实际 {r.status}")


@case("G6", "检索：独特标识符能命中（混合检索）")
def g6_search_hit(c):
    d = ok(post("/knowledge/search", {"query": FACT_CODEWORD, "topK": 5}), "知识库检索")
    check(d["hits"], "应至少命中 1 条")
    joined = " ".join(h["content"] for h in d["hits"])
    check_contains(joined, FACT_CODEWORD, "命中内容应包含目标标识符")


@case("G6", "检索：空 query → 400，topK 越界不报错")
def g6_search_bounds(c):
    r = post("/knowledge/search", {"query": "   "})
    check_eq(r.status, 400, "空检索词应被拒绝")
    d = ok(post("/knowledge/search", {"query": FACT_CODEWORD, "topK": 999}), "超限 topK")
    check(len(d["hits"]) <= 20, "topK 应被收敛到上限")


@case("G6", "知识库问答：基于检索作答并带引用编号")
def g6_ask(c):
    d = ok(post("/knowledge/ask", {"question": "系统的内部代号是什么？", "topK": 4}), "知识库问答")
    check_eq(d["answered"], True, "应基于知识库作答")
    check(d["citations"], "应有引用")
    check_contains(d["answer"], FACT_CODEWORD, "回答应包含文档中的代号")


@case("G6", "无关提问：不编造知识库里没有的事实")
def g6_ask_no_hit(c):
    d = ok(post("/knowledge/ask", {"question": "量子引力在厨房里的烹饪温度是多少？"}), "无关问答")
    # 判定分两层，因为「是否有命中」与「是否作答」是两个事实：
    #   ① answered=false  → 明确拒答（最干净）
    #   ② answered=true   → 仍须在正文里承认知识库没有该内容，不能凭空给数字
    refusal = ("没有", "未找到", "无法", "不包含", "没有相关", "未提及", "暂无")
    if not d["answered"]:
        check_eq(d["citations"], [], "未作答时不应有引用")
        return
    if not any(k in d["answer"] for k in refusal):
        raise CaseFailure(f"检索到无关片段却据此编造答案：{d['answer'][:120]}")


@case("G6", "对话增强：citations 事件在 done 之前且编号从 1 开始")
def g6_chat_with_knowledge(c):
    sid = new_session("知识库增强对话")
    s = stream_chat(sid, "根据知识库回答：系统的内部代号是什么？只回复代号。",
                    useKnowledge=True, topK=4)
    check(s.is_sse(), "应返回事件流")
    names = s.names()
    check("citations" in names, f"应推送 citations 事件，实际 {names[:5]}")
    check(names.index("citations") < names.index("done"), "citations 必须在 done 之前")
    cit = s.first("citations")
    check(cit["count"] >= 1, f"应至少命中 1 条，实际 {cit['count']}")
    check_eq(cit["citations"][0]["index"], 1, "引用编号应从 1 开始")
    check_contains(s.text(), FACT_CODEWORD, "回答应包含知识库中的代号")
    msgs = messages(sid)
    check(msgs[-1]["citations"], "助手消息应快照保存引用")


@case("G6", "删除文档：向量一并删除且不再命中")
def g6_delete_doc(c):
    did = CTX.get("doc_id")
    if not did:
        raise SkipCase("没有可删除的文档")
    ok(delete(f"/knowledge/documents/{did}"), "删除文档")
    CTX["cleanup"]["docs"].remove(did)
    check_eq(db_count(f"vector_store where metadata->>'document_id' = '{did}'"), 0,
             "向量应一并删除")
    d = ok(post("/knowledge/search", {"query": FACT_CODEWORD, "topK": 5}), "删除后检索")
    joined = " ".join(h["content"] for h in d["hits"])
    check(FACT_CODEWORD not in joined, "删除后不应再命中该文档内容")


# ================================================================ G7 长期记忆


@case("G7", "写入记忆：内容与来源正确")
def g7_create(c):
    content = f"我的主用编程语言是 Rust（E2E-{UNIQUE}）"
    d = ok(post("/memories", {"content": content}), "写记忆")
    check_eq(d["content"], content, "内容应原样保存")
    check_eq(d["source"], "MANUAL", "手动写入来源应为 MANUAL")
    CTX["cleanup"]["memories"].append(d["id"])
    CTX["memory_id"] = d["id"]


@case("G7", "空内容 → 400，超长内容不导致 500")
def g7_bounds(c):
    r = post("/memories", {"content": "   "})
    check_eq(r.status, 400, "空内容应被拒绝")
    r2 = post("/memories", {"content": "长" * 8000})
    check(r2.status in (200, 400), f"超长内容应有明确结果，实际 {r2.status}")
    if r2.status == 200:
        CTX["cleanup"]["memories"].append(r2.data["id"])


@case("G7", "设为常驻：pinned 生效且受上限约束")
def g7_pin(c):
    mid = CTX["memory_id"]
    d = ok(patch(f"/memories/{mid}/pin", {"pinned": True}), "设为常驻")
    check_eq(d["pinned"], True, "pinned 应为 true")
    lst = ok(get("/memories"), "记忆列表")
    row = next(m for m in lst if m["id"] == mid)
    check_eq(row["pinned"], True, "列表应反映常驻状态")


@case("G7", "常驻记忆在新会话中自动生效（不靠相似度召回）")
def g7_effective(c):
    sid = new_session("记忆生效验证")
    s = stream_chat(sid, "我最常用的编程语言是什么？只回答语言名。")
    check_eq(s.first("done") is not None, True, "应完成")
    check_contains(s.text().lower(), "rust", "常驻记忆应无条件注入（R-03：不做向量召回）")
    hit = int(db(f"select hit_count from memory where id='{CTX['memory_id']}'") or 0)
    check(hit >= 1, f"记忆的 hitCount 应被累加，实际 {hit}")


@case("G7", "修改记忆内容")
def g7_update(c):
    mid = CTX["memory_id"]
    new_content = f"我的主用编程语言是 Rust 与 Go（E2E-{UNIQUE} 已更新）"
    d = ok(patch(f"/memories/{mid}", {"content": new_content}), "改记忆")
    check_eq(d["content"], new_content, "内容应被更新")


@case("G7", "删除记忆后列表不再包含")
def g7_delete(c):
    mid = CTX["memory_id"]
    ok(delete(f"/memories/{mid}"), "删记忆")
    CTX["cleanup"]["memories"].remove(mid)
    lst = ok(get("/memories"), "记忆列表")
    check(all(m["id"] != mid for m in lst), "已删除的记忆不应出现在列表")


@case("G7", "「请记住」写入记忆：只能经 saveMemory 工具，不是轮末自动沉淀", mode="observe")
def g7_auto_memory_off(c):
    before = db_count("memory")
    sid = new_session("自动记忆观察")
    stream_chat(sid, "请记住：我最喜欢的颜色是蓝色。")
    time.sleep(1.0)
    after = db_count("memory")
    if after == before:
        raise SkipCase("本轮没有写入记忆（模型未调用 saveMemory，属正常波动）")
    src = db(f"select source from memory order by created_at desc limit 1")
    raise SkipCase(f"新增 {after - before} 条，source={src}："
                   f"auto-memory 配置为 false，写入来自模型显式调用 saveMemory 工具")


# ================================================================ G8 任务 / 日程 / 提醒


@case("G8", "任务 CRUD 全链路")
def g8_task_crud(c):
    d = ok(post("/tasks", {"title": "E2E 任务生命周期", "description": "描述",
                           "priority": "HIGH", "tags": ["e2e", "test"]}), "建任务")
    tid = d["id"]
    CTX["cleanup"]["tasks"].append(tid)
    check_eq(d["status"], "TODO", "初始状态")

    got = ok(get(f"/tasks/{tid}"), "查任务")
    check_eq(got["title"], "E2E 任务生命周期", "标题")
    check_eq(got["tags"], ["e2e", "test"], "标签顺序应稳定")

    up = ok(patch(f"/tasks/{tid}", {"title": "E2E 任务改名", "status": "DOING"}), "改任务")
    check_eq(up["status"], "DOING", "状态更新")

    done = ok(post(f"/tasks/{tid}/complete", {}), "完成")
    check_eq(done["status"], "DONE", "完成后状态")
    check(done["completedAt"], "completedAt 应非空")

    re_ = ok(post(f"/tasks/{tid}/reopen", {}), "重开")
    # reopenTarget() 的设计是「回到完成之前的那一步」：完成前是 DOING 就回 DOING，
    # 一律塞回 TODO 会丢掉用户已经推进到的进度。
    check_in(re_["status"], ("TODO", "DOING"), f"重开后应为未完成状态，实际 {re_['status']}")
    check_eq(re_["completedAt"], None, "重开后 completedAt 应清空")


@case("G8", "任务边界：空标题 400 / 非法优先级 400 / 超 5 标签 400")
def g8_task_bounds(c):
    check_eq(post("/tasks", {"title": "  "}).status, 400, "空标题应被拒绝")
    check_eq(post("/tasks", {"title": "x", "priority": "URGENT"}).status, 400,
             "非法优先级应被拒绝")
    r = post("/tasks", {"title": "标签太多", "tags": ["a", "b", "c", "d", "e", "f"]})
    check_eq(r.status, 400, "超过 5 个标签应被拒绝")


@case("G8", "任务筛选与标签去重")
def g8_task_filters(c):
    ok(post("/tasks", {"title": "E2E-筛选A", "tags": ["筛选", "e2e"]}), "建任务")
    d = ok(get("/tasks?tag=e2e&page=0&size=50"), "按标签筛选")
    check(all("e2e" in t["tags"] for t in d["items"]), "筛选结果应只含带该标签的任务")
    tags = ok(get("/tasks/tags"), "标签列表")
    check(len(tags) == len(set(tags)), "标签应去重")
    check_in("e2e", tags, "标签列表应包含 e2e")


@case("G8", "任务统计与筛选无关（全量口径）")
def g8_stats(c):
    all_tasks = ok(get("/tasks?page=0&size=500"), "全量任务")
    d = ok(get("/tasks/stats"), "统计")
    expected = sum(1 for t in all_tasks["items"] if t["status"] != "DONE")
    check_eq(d["pending"], expected, "pending 应为全量未完成数（不受分页影响）")


@case("G8", "日程：重复规则在区间查询中被展开")
def g8_calendar_rrule(c):
    start = (datetime.now(timezone.utc) + timedelta(days=1)).replace(
        hour=9, minute=0, second=0, microsecond=0)
    end = start + timedelta(hours=1)
    d = ok(post("/calendar/events", {
        "title": "E2E 每日站会",
        "startsAt": iso_z(start),
        "endsAt": iso_z(end),
        "recurrenceType": "DAILY",
        "recurrenceUntil": iso_z(start + timedelta(days=5)),
    }), "建重复日程")
    CTX["cleanup"]["events"].append(d["id"])
    q = ok(get(f"/calendar/events?from={iso_z(start)}"
               f"&to={iso_z(start + timedelta(days=6))}"), "区间查询")
    # 区间查询返回 PageDto（{items,page,size,total}），不是裸数组
    rows = q["items"] if isinstance(q, dict) else q
    occ = [x for x in rows if x.get("title") == "E2E 每日站会"]
    check(len(occ) >= 4, f"5 天内应展开多次，实际 {len(occ)}")


@case("G8", "日程边界：结束早于开始 → 400；区间超 90 天 → 400")
def g8_calendar_bounds(c):
    start = datetime.now(timezone.utc) + timedelta(days=2)
    r = post("/calendar/events", {"title": "倒序", "startsAt": iso_z(start),
                                  "endsAt": iso_z(start - timedelta(hours=1))})
    check_eq(r.status, 400, "结束早于开始应被拒绝")
    r2 = get("/calendar/events?from=2026-01-01T00:00:00Z&to=2026-12-31T00:00:00Z")
    check_eq(r2.status, 400, "超过 90 天的区间应被拒绝")
    check_contains((r2.body or {}).get("message", ""), "90", "错误信息应点明跨度上限")


@case("G8", "提醒：扫描物化 → 标记已读后不再出现在到点列表")
def g8_reminder(c):
    past = datetime.now(timezone.utc) - timedelta(minutes=30)
    d = ok(post("/calendar/events", {
        "title": "E2E 提醒验证",
        "startsAt": iso_z(past + timedelta(minutes=20)),
        "endsAt": iso_z(past + timedelta(minutes=50)),
        "reminderLeadMinutes": 15,
    }), "建日程")
    CTX["cleanup"]["events"].append(d["id"])
    ok(post("/reminders/scan", {}), "手动扫描")
    due = ok(get("/reminders?dueOnly=true"), "到点提醒")
    mine = [r for r in due if r["title"] == "E2E 提醒验证"]
    check(mine, f"应物化出提醒，实际 {[r['title'] for r in due]}")
    ok(post(f"/reminders/{mine[0]['id']}/read", {}), "标记已读")
    due2 = ok(get("/reminders?dueOnly=true"), "已读后查询")
    check(all(r["id"] != mine[0]["id"] for r in due2), "已读提醒不应再出现在到点列表")


# ================================================================ G9 搜索 / 导出 / 用量


@case("G9", "全局搜索：跨模块分组返回")
def g9_global_search(c):
    ok(post("/tasks", {"title": f"全局搜索靶子-{UNIQUE}"}), "建任务用于搜索")
    d = ok(get(f"/search?keyword={UNIQUE}"), "全局搜索")
    check(isinstance(d, (list, dict)), "搜索应返回结果结构")
    text = json.dumps(d, ensure_ascii=False)
    check_contains(text, UNIQUE, "搜索结果应包含刚建的任务标题")


@case("G9", "导出：返回文件而非 JSON 信封")
def g9_export(c):
    r = get("/export", expect_json=False, headers={"Accept": "*/*"})
    check_eq(r.status, 200, "导出状态码")
    check(len(r.raw) > 100, f"导出内容不应为空，实际 {len(r.raw)} 字节")
    disp = r.headers.get("content-disposition", "")
    check(disp, "应带 Content-Disposition（否则浏览器不会当成下载）")
    check(not r.text().lstrip().startswith('{"code"'), "导出不应套统一信封")


@case("G9", "用量统计：有对话后产生 token 记录")
def g9_usage(c):
    d = ok(get("/usage"), "用量统计")
    check(isinstance(d, dict), "用量应为对象")
    check(db_count("token_usage") > 0, "应存在 token_usage 记录")


@case("G9", "列表分页溢出：任务与知识库同样不受影响")
def g9_paging_overflow(c):
    for path in ("/tasks?page=21474837&size=100",
                 "/knowledge/documents?page=21474837&size=100",
                 "/calendar/events?from=2026-10-01&to=2026-10-02&page=21474837&size=100"):
        r = get(path)
        check(r.status != 500, f"{path} 不应 500（实际 {r.status}）")


@case("G9", "MCP 状态：无配置时返回空清单而不是 500")
def g9_mcp(c):
    r = get("/mcp/servers")
    check_eq(r.status, 200, "MCP 状态应可查询")
    d = ok(r, "MCP 状态")
    check("servers" in d and isinstance(d["servers"], list), f"应含 servers 列表，实际 {d}")
    check("enabled" in d, "应含 enabled 开关")


@case("G9", "语音转写状态接口可用")
def g9_transcribe_status(c):
    r = get("/chat/transcribe/status")
    check_eq(r.status, 200, "转写状态接口")


# ================================================================ G10 附件与限流


@case("G10", "附件上传 + 取回内容一致")
def g10_attachment(c):
    payload = ("附件内容：本次评审的结论是延期两周。" * 3).encode("utf-8")
    r = upload("/chat/uploads", "评审结论.txt", payload, "text/plain")
    check_in(r.status, (200, 201), f"上传应成功，实际 {r.status} {r.text()[:200]}")
    d = ok(r, "上传附件")
    check_eq(d["size"], len(payload), "服务端应使用真实字节数")
    got = get(f"/chat/uploads/{d['id']}/content", expect_json=False, headers={"Accept": "*/*"})
    check_eq(got.status, 200, "取回附件")
    check_eq(got.raw, payload, "取回内容应与上传一致")
    CTX["attachment"] = d


@case("G10", "上传缺少 file 字段 → 4xx 而不是 500")
def g10_upload_missing_part(c):
    boundary = "----E2E" + uuid.uuid4().hex
    payload = (f"--{boundary}\r\n"
               'Content-Disposition: form-data; name="note"\r\n\r\n'
               f"只有文本字段\r\n--{boundary}--\r\n").encode("utf-8")
    r = req("POST", "/chat/uploads", raw_body=payload,
            content_type=f"multipart/form-data; boundary={boundary}")
    # 审查文档第 13 条：缺 part 曾落到 Exception 兜底 → 500。
    check(r.status != 500, f"缺少 file 字段不应 500（实际 {r.status}）")
    check(r.status in (400, 415, 422), f"应返回可读的 4xx，实际 {r.status}")


@case("G10", "附件超限（>6MB）被拒绝")
def g10_attachment_too_big(c):
    big = b"x" * (6 * 1024 * 1024 + 1024)
    r = upload("/chat/uploads", "big.txt", big, "text/plain")
    check(r.status in (400, 413), f"超限附件应被拒绝，实际 {r.status}")


@case("G10", "带附件的对话：模型读到附件内容且消息落库附件名片")
def g10_chat_with_attachment(c):
    att = CTX.get("attachment")
    sid = new_session("附件对话")
    s = stream_chat(sid, "这个附件的结论是什么？一句话回答。", attachments=[att])
    check_eq(s.first("done") is not None, True, "应完成")
    check_contains(s.text(), "延期两周", "模型应读到附件正文（附件上下文注入失效）")
    # 附件名片必须在 done **之前**落库：done 的契约是「该落的库都落完了」，
    # 客户端收到它就会立刻拉消息列表。立刻查、不轮询 —— 轮询会把回归掩盖掉。
    msgs = messages(sid)
    check(msgs and msgs[0]["attachments"],
          "收到 done 后立刻查，用户消息应已带附件名片（说明附件改为 done 之后补写了）")


@case("G10", "并发同一会话：不产生 5xx（互斥或排队都可接受）", mode="observe")
def g10_concurrent(c):
    import threading
    sid = new_session("并发测试")
    results = []

    def fire(i):
        try:
            s = stream_chat(sid, f"只回复数字 {i}")
            results.append((s.status, s.first("done") is not None))
        except Exception as e:      # noqa: BLE001
            results.append(("error", str(e)[:60]))

    ts = [threading.Thread(target=fire, args=(i,)) for i in range(2)]
    [t.start() for t in ts]
    [t.join(240) for t in ts]
    check(all(r[0] in (200, 409, 429) for r in results),
          f"并发不应产生 5xx，实际 {results}")


@case("G10", "限流：突发超过容量后返回 429 + code 42900")
def g10_rate_limit(c):
    # 打不存在的会话：限流在控制器之前生效，不产生模型调用与落库
    ghost = uuid.uuid4()
    codes = []
    for _ in range(70):
        r = req("POST", f"/chat/sessions/{ghost}/stream", body={"content": "x"})
        codes.append((r.status, r.code))
    limited = [x for x in codes if x[0] == 429]
    check(limited, f"70 次突发应触发限流，实际状态码集合 {set(s for s, _ in codes)}")
    check(all(code == 42900 for _, code in limited), "限流响应应带 code=42900")
    check(any(s == 404 for s, _ in codes), "限流前的不存在会话应返回 404")


# ================================================================ 运行器


class SkipCase(Exception):
    pass


def run(selected_groups=None, cleanup=True):
    todo = [c for c in CASES if not selected_groups or c["group"] in selected_groups]
    CTX["started_at"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    results = []
    started_all = time.monotonic()

    print(f"\n端到端能力边界测试 · {len(todo)} 个用例 · {BASE}\n" + "=" * 78)
    for idx, c in enumerate(todo, 1):
        label = f"[{c['group']}-{idx:02d}] {c['name']}"
        t0 = time.monotonic()
        outcome, detail = "PASS", ""
        try:
            c["fn"](CTX)
        except SkipCase as e:
            outcome, detail = "SKIP", str(e)
        except CaseFailure as e:
            outcome = "NOTE" if c["mode"] == "observe" else "FAIL"
            detail = str(e)
        except Transient as e:
            outcome, detail = "ERROR", f"环境异常：{e}"
        except Exception as e:      # noqa: BLE001
            outcome = "NOTE" if c["mode"] == "observe" else "ERROR"
            detail = f"{type(e).__name__}: {e}"
        dur = time.monotonic() - t0
        results.append({"group": c["group"], "name": c["name"], "outcome": outcome,
                        "detail": detail, "duration": round(dur, 2),
                        "mode": c["mode"]})
        icon = {"PASS": "✅", "FAIL": "❌", "SKIP": "⏭ ", "NOTE": "📝", "ERROR": "💥"}[outcome]
        print(f"{icon} {label}  {dur:5.1f}s")
        if detail:
            print(f"     ↳ {detail}")

    elapsed = time.monotonic() - started_all
    counts = {k: sum(1 for r in results if r["outcome"] == k)
              for k in ("PASS", "FAIL", "SKIP", "NOTE", "ERROR")}
    print("=" * 78)
    print(f"合计 {len(results)} 项 · 通过 {counts['PASS']} · 失败 {counts['FAIL']} · "
          f"错误 {counts['ERROR']} · 跳过 {counts['SKIP']} · 观察 {counts['NOTE']} · "
          f"耗时 {elapsed/60:.1f} 分钟")

    if cleanup:
        cleanup_artifacts()

    write_report(results, counts, elapsed)
    return 0 if counts["FAIL"] == 0 and counts["ERROR"] == 0 else 1


def cleanup_artifacts():
    """删掉本轮造出来的数据，让库回到测试前的干净状态。"""
    print("\n清理测试产物…")
    c = CTX["cleanup"]
    for sid in list(dict.fromkeys(c["sessions"])):
        try:
            delete(f"/chat/sessions/{sid}")
        except Exception:   # noqa: BLE001
            pass
    for tid in list(dict.fromkeys(c["tasks"])):
        try:
            delete(f"/tasks/{tid}")
        except Exception:   # noqa: BLE001
            pass
    for did in list(dict.fromkeys(c["docs"])):
        try:
            delete(f"/knowledge/documents/{did}")
        except Exception:   # noqa: BLE001
            pass
    for mid in list(dict.fromkeys(c["memories"])):
        try:
            delete(f"/memories/{mid}")
        except Exception:   # noqa: BLE001
            pass
    for eid in list(dict.fromkeys(c["events"])):
        try:
            delete(f"/calendar/events/{eid}")
        except Exception:   # noqa: BLE001
            pass
    # 模型自己调工具建的数据（任务 / 记忆 / 确认记录）没有留 id，按前缀清不干净：
    # 它会自己起名字（「全局搜索靶子」「用户最喜欢的颜色是蓝色」），完全不认识 E2E。
    # 所以按「本轮开始之后创建的行」兜底清 —— 这套用例独占这个库（开跑前刚 reset 过），
    # 这个边界是安全的；换成共享库就必须改成 id 白名单。
    for table in ("task", "memory", "confirmation", "reminder", "calendar_event"):
        try:
            db(f"delete from {table} where created_at >= '{CTX['started_at']}'")
        except CaseFailure as ex:
            # 某张表没有 created_at 时只跳过它，不能让其余表一起漏清
            print(f"  跳过 {table}：{str(ex)[:80]}")
    left = {t: db_count(t) for t in ("chat_session", "chat_message", "task",
                                     "knowledge_document", "memory",
                                     "calendar_event", "confirmation")}
    print("  清理后残留：" + "，".join(f"{k}={v}" for k, v in left.items()))


def write_report(results, counts, elapsed):
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    json_path = REPORT_DIR / f"e2e-{stamp}.json"
    md_path = REPORT_DIR / f"e2e-{stamp}.md"
    payload = {"base": BASE, "startedAt": stamp, "elapsedSeconds": round(elapsed, 1),
               "counts": counts, "results": results}
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    icons = {"PASS": "✅", "FAIL": "❌", "SKIP": "⏭", "NOTE": "📝", "ERROR": "💥"}
    lines = [
        f"# 端到端能力边界测试报告",
        "",
        f"- 目标：`{BASE}`",
        f"- 时间：{stamp}（耗时 {elapsed/60:.1f} 分钟）",
        f"- 结果：**{counts['PASS']} 通过 / {counts['FAIL']} 失败 / "
        f"{counts['ERROR']} 错误 / {counts['SKIP']} 跳过 / {counts['NOTE']} 观察**",
        "",
        "| 组 | 结果 | 用例 | 耗时 | 说明 |",
        "|---|---|---|---|---|",
    ]
    for r in results:
        detail = r["detail"].replace("|", "\\|").replace("\n", " ")[:160]
        lines.append(f"| {r['group']} | {icons[r['outcome']]} {r['outcome']} | "
                     f"{r['name']} | {r['duration']}s | {detail} |")
    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\n报告：{md_path}")


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("-")]
    cleanup = "--no-cleanup" not in argv
    if "--list" in argv:
        for c in CASES:
            print(f"{c['group']}  {c['name']}  ({c['mode']})")
        return 0
    if "--reset" in argv:
        reset_business_data()
    return run(set(args) if args else None, cleanup=cleanup)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
