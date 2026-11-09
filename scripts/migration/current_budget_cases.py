"""Current budget acceptance; the frozen differential E2E case remains unchanged."""
from contextlib import contextmanager
import json

from p8_context_probe import RequestObservations, digest, require_rejection, require_trimmed
from p8_model_fixture import Handler


@contextmanager
def observe_requests():
    previous = Handler.request_observer
    observations = RequestObservations()
    Handler.request_observer = observations
    try:
        yield observations
    finally:
        Handler.request_observer = previous


def small_budget(suite):
    budget = suite.ok(suite.put("/chat/context-budget", {"tokens": 2048}))
    if not 2048 < budget["budgetTokens"] < budget["contextWindow"]:
        raise AssertionError("Small budget must retain the fixed-overhead minimum within the model window")
    return budget["budgetTokens"]


def history_trimming(suite):
    """Discard old request context, retaining exact current input and stored history."""
    with observe_requests() as observations:
        try:
            suite.ok(suite.put("/chat/context-budget", {"tokens": 32768}))
            session = suite.new_session("Current budget: trim persisted history")
            history = ["Budget history %d: %s 只回复 OK" %
                       (index, "这是用于验证旧历史裁剪的合成句子。" * 60) for index in range(6)]
            for content in history:
                response = suite.stream_chat(session, content)
                if response.first("done") is None or response.first("error") is not None:
                    raise AssertionError("History setup must complete all six HTTP turns")
            seeded = suite.messages(session)
            if (len(seeded) != 12 or
                    [row["content"] for row in seeded if row["role"] == "USER"] != history):
                raise AssertionError("History setup must persist six exact user/assistant pairs")
            effective = small_budget(suite)
            before = len(observations.snapshot())
            current = "Budget current input: 只回复 OK"
            response = suite.stream_chat(session, current)
            calls = observations.snapshot()[before:]
            if (response.first("done") is None or response.first("error") is not None
                    or len(calls) != 1):
                raise AssertionError("Short current input must complete with exactly one model request")
            persisted = suite.messages(session)
            if len(persisted) != 14 or persisted[:12] != seeded:
                raise AssertionError("Request trimming must not edit stored message records")
            result = require_trimmed(calls[0], history, current, persisted)
            return dict(result, result="PASS", effective_budget=effective, upstream_calls=1,
                        seeded_turns=6, done=True, error=False, request_observation=calls[0])
        finally:
            suite.ok(suite.put("/chat/context-budget", {"tokens": 32768}), "Restore budget after history case")


def current_input_rejection(suite):
    """Reject oversized current input locally, preserving input and failure receipt."""
    with observe_requests() as observations:
        try:
            effective = small_budget(suite)
            session = suite.new_session("Current budget: reject oversized current input")
            content = "这是一个用于填充上下文的句子。" * 120 + " 第0轮，只回复 OK"
            response = suite.stream_chat(session, content)
            result = require_rejection(response, len(observations.snapshot()))
            persisted = suite.messages(session)
            if (len(persisted) != 2 or persisted[0].get("role") != "USER"
                    or persisted[0].get("content") != content
                    or persisted[1].get("role") != "ASSISTANT"
                    or persisted[1].get("content") != "（本条回答因连接中断未说完）"):
                raise AssertionError("Local rejection must preserve exact input and only its interruption receipt")
            return dict(result, result="PASS", effective_budget=effective, input_sha256=digest(content),
                        input_chars=len(content), persisted_user_messages=1,
                        interruption_markers=1, generated_assistant_messages=0)
        finally:
            suite.ok(suite.put("/chat/context-budget", {"tokens": 32768}), "Restore budget after rejection case")


def install_current_budget_cases(suite, backend):
    """Explicitly version the current suite while retaining every other original case."""
    matches = [case for case in suite.CASES if case["fn"] is suite.g4_trim_under_small_budget]
    if len(matches) != 1:
        raise ValueError("Expected exactly one frozen G4 budget case")
    original = matches[0]
    report = dict(suite_variant="current-budget-v1", backend=backend,
                  replaced_case=dict(group=original["group"], name=original["name"]),
                  frozen_e2e_modified=False, original_e2e_approved=False,
                  release_gates_changed=False, cases={})

    def recorded(identity, check):
        def run(context):
            result = check(suite)
            report["cases"][identity] = result
            suite.REPORT_DIR.mkdir(parents=True, exist_ok=True)
            (suite.REPORT_DIR / "current-budget-contract.json").write_text(
                json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        return run

    current = [
        dict(group="G4", name="旧历史过长：裁剪请求后成功，当前输入和存储历史保持原样",
             mode="fail", fn=recorded("history_trimming", history_trimming)),
        dict(group="G4", name="当前输入自身超限：本地41301、零模型调用、原文和中断回执保留",
             mode="fail", fn=recorded("current_input_rejection", current_input_rejection)),
    ]
    updated = []
    for case in suite.CASES:
        updated.extend(current if case is original else [case])
    suite.CASES = updated
    return current
