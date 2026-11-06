"""Observe synthetic context boundaries without changing the frozen E2E assertions."""
import hashlib
import copy
import json
import threading
import time

from p8_model_fixture import text_content


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def canonical_digest(value):
    return digest(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")))


class RequestObservations:
    def __init__(self):
        self.lock = threading.Lock()
        self.rows = []

    def __call__(self, request):
        messages = request.get("messages", [])
        row = dict(message_roles=[message.get("role") for message in messages],
                   user_sha256=[digest(text_content(message)) for message in messages if message.get("role") == "user"],
                   tools=len(request.get("tools", [])), stream=request.get("stream") is True)
        row["messages"] = [dict(role=message.get("role"), chars=len(text_content(message)),
                                sha256=digest(text_content(message))) for message in messages]
        row["tool_definitions_sha256"] = canonical_digest(request.get("tools", []))
        row["tool_schemas"] = []
        for tool in request.get("tools", []):
            function = tool.get("function", {})
            row["tool_schemas"].append(dict(name=function.get("name"), sha256=canonical_digest(function),
                present_fields=[key for key in ("name", "description", "parameters", "strict") if key in function],
                parameters_sha256=canonical_digest(function.get("parameters")),
                description_sha256=canonical_digest(function.get("description")),
                strict=function.get("strict") if type(function.get("strict")) is bool else None))
        with self.lock:
            if len(self.rows) >= 32:
                raise AssertionError("Context probe exceeded its 32 synthetic request limit")
            self.rows.append(row)

    def snapshot(self):
        with self.lock:
            return copy.deepcopy(self.rows)


def require_rejection(stream, calls):
    error = stream.first("error")
    if (not isinstance(error, dict) or error.get("code") != 41301
            or stream.first("done") is not None or stream.first("delta") is not None or calls != 0):
        raise AssertionError("Oversized current input was not rejected locally with 41301 and zero model calls")
    return dict(error_code=41301, done=False, delta=False, upstream_calls=0)


def require_trimmed(observation, history, current, persisted):
    expected = history + [current]
    actual = [row["content"] for row in persisted if row.get("role") == "USER"]
    if actual != expected:
        raise AssertionError("Context trimming changed or deleted persisted user history")
    sent = observation["user_sha256"]
    hashes = [digest(value) for value in expected]
    if (not sent or sent[-1] != hashes[-1] or len(sent) >= len(hashes)
            or any(value not in hashes for value in sent)
            or sent != hashes[len(hashes) - len(sent):]):
        raise AssertionError("Model input did not retain the current user and a trimmed ordered suffix")
    if not observation["stream"] or not observation["tools"]:
        raise AssertionError("Context probe did not use the actual streaming tool-enabled model boundary")
    return dict(persisted_user_messages=len(actual), upstream_user_messages=len(sent),
                removed_from_request=len(hashes) - len(sent), current_input_sha256=hashes[-1],
                persisted_history_unchanged=True, ordered_suffix=True)


def verify_context_boundaries(suite, observations, backend):
    suite.CTX["started_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    original_stream = suite.stream_chat
    failures = []
    try:
        if any(suite.db_count(table) for table in ("chat_session", "chat_message")):
            raise AssertionError("Context probe requires its new owned database to be empty")

        def observed_stream(sid, content, **kwargs):
            before = len(observations.snapshot())
            response = original_stream(sid, content, **kwargs)
            failures.append(dict(session=sid, content=content, response=response,
                                 upstream_calls=len(observations.snapshot()) - before))
            return response

        suite.stream_chat = observed_stream
        original_failure = None
        try:
            suite.g4_trim_under_small_budget(suite.CTX)
        except AssertionError as error:
            original_failure = str(error)
        finally:
            suite.stream_chat = original_stream
            suite.ok(suite.put("/chat/context-budget", {"tokens": 32768}), "Restore original budget")
        if (original_failure != "第 0 轮应完成（裁剪生效，不应因超预算报错）" or len(failures) != 1):
            raise AssertionError("Frozen G4 failure changed; inspect it before accepting this diagnostic")
        failed = failures[0]
        rejection = require_rejection(failed["response"], failed["upstream_calls"])
        persisted = suite.messages(failed["session"])
        if (len(persisted) != 2 or persisted[0].get("role") != "USER"
                or persisted[0].get("content") != failed["content"]
                or persisted[1].get("role") != "ASSISTANT"
                or persisted[1].get("content") != "（本条回答因连接中断未说完）"):
            print("Rejected request persisted roles: " + json.dumps([row.get("role") for row in persisted]), flush=True)
            raise AssertionError("Rejected request lost exact user input or the Java interruption marker")
        rejected_messages = persisted
        rejection.update(input_chars=len(failed["content"]), input_sha256=digest(failed["content"]),
                         original_case_outcome="FAIL unchanged", original_failure=original_failure,
                         persisted_user_messages=1, interruption_markers=1, generated_assistant_messages=0)

        # Build real persisted history through completed HTTP turns, below the 20-message cap.
        sid = suite.new_session("P8 context history boundary")
        history = ["P8 history %d: %s 只回复 OK" % (index, "这是用于验证旧历史裁剪的合成句子。" * 60)
                   for index in range(6)]
        for content in history:
            response = original_stream(sid, content)
            if response.first("done") is None or response.first("error") is not None:
                raise AssertionError("Context history setup did not finish")
        seeded = suite.messages(sid)
        if len(seeded) != 12 or [row["content"] for row in seeded if row["role"] == "USER"] != history:
            raise AssertionError("Context history setup did not persist six complete exact turns")
        budget = suite.ok(suite.put("/chat/context-budget", {"tokens": 2048}))
        if not 2048 < budget["budgetTokens"] < budget["contextWindow"]:
            raise AssertionError("Small budget did not retain the original fixed-overhead minimum")
        before = len(observations.snapshot())
        current = "P8 current context boundary: 只回复 OK"
        response = original_stream(sid, current)
        calls = observations.snapshot()[before:]
        if response.first("done") is None or response.first("error") is not None or len(calls) != 1:
            raise AssertionError("Short current input with long history did not complete in one model call")
        persisted = suite.messages(sid)
        if len(persisted) != 14 or persisted[:12] != seeded:
            raise AssertionError("History trimming changed persisted message records")
        trimming = require_trimmed(calls[0], history, current, persisted)
        trimming.update(effective_budget=budget["budgetTokens"], upstream_calls=1,
                        persisted_messages=14, done=True, request_observation=calls[0])

        suite.ok(suite.put("/chat/context-budget", {"tokens": 32768}))
        before = len(observations.snapshot())
        recovery = original_stream(failed["session"], "P8 recovery after context rejection: 只回复 OK")
        recovered = suite.messages(failed["session"])
        if (recovery.first("done") is None or recovery.first("error") is not None
                or len(observations.snapshot()) - before != 1 or len(recovered) != 4
                or recovered[:2] != rejected_messages
                or recovered[0]["content"] != failed["content"]):
            raise AssertionError("Context rejection did not recover with its original history intact")
        report = dict(backend=backend, result="PASS", scope="synthetic model, owned TLS database, unchanged Java baseline",
                      original_e2e_approved=False, release_gates_changed=False,
                      oversized_current_input=rejection, old_history_trimming=trimming,
                      restored_budget_recovery=dict(done=True, upstream_calls=1, persisted_messages=4,
                                                    rejected_input_unchanged=True),
                      synthetic_model_requests=len(observations.snapshot()))
    finally:
        suite.stream_chat = original_stream
        suite.ok(suite.put("/chat/context-budget", {"tokens": 32768}), "Final budget reset")
        suite.cleanup_artifacts()
    if any(suite.db_count(table) for table in ("chat_session", "chat_message")):
        raise AssertionError("Context probe cleanup left owned chat records")
    report["cleanup_empty"] = True
    suite.REPORT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    path = suite.REPORT_DIR / ("context-boundary-" + stamp + ".json")
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print("P8 context boundary report: " + str(path.relative_to(suite.REPORT_DIR.parent.parent)), flush=True)
