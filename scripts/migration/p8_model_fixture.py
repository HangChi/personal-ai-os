"""Deterministic protocol fixture for the unchanged E2E suite; no DB or expected-result access."""
import importlib.util
import json
from pathlib import Path
import re
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("original_mock_model", ROOT / "scripts/dev/mock-openai-server.py")
original = importlib.util.module_from_spec(spec)
spec.loader.exec_module(original)


def text_content(message):
    content = message.get("content") or ""
    if isinstance(content, list):
        return "\n".join(part.get("text", "") for part in content if isinstance(part, dict))
    return str(content)


def task_plan_next(messages):
    """Advance only the supported read/count/advise plan, from successful receipts."""
    calls = {}
    receipts = []
    for message in messages:
        for call in message.get("tool_calls", []):
            calls[call["id"]] = call.get("function", {}).get("name")
        if message.get("role") != "tool":
            continue
        try:
            result = json.loads(text_content(message))
        except ValueError:
            return None
        if not isinstance(result, dict) or result.get("status") != "OK":
            return None
        receipts.append((message.get("name") or calls.get(message.get("tool_call_id")), result.get("data")))
    plans = [data for name, data in receipts if name in ("createPlan", "startPlanStep", "completePlanStep")]
    if not plans or not isinstance(plans[-1], dict):
        return None
    plan = plans[-1]
    steps = plan.get("steps", [])
    if (len(steps) != 3 or not all(isinstance(step, dict) for step in steps)
            or not all(word in step.get("title", "") for word, step in zip(("任务", "优先级", "建议"), steps))):
        return None
    step = next((step for step in steps if step.get("status") not in ("DONE", "SKIPPED")), None)
    if step is None or plan.get("status") != "ACTIVE":
        return None
    seq = step.get("seq")
    if step.get("status") == "PENDING":
        return "startPlanStep", {"seq": seq}
    if step.get("status") != "RUNNING":
        return None
    queries = [data for name, data in receipts if name == "findTasks"]
    if not queries:
        return ("findTasks", {"limit": 100}) if seq == 1 else None
    tasks = queries[-1]
    if not isinstance(tasks, list) or not all(isinstance(task, dict) for task in tasks):
        return None
    counts = {priority: sum(task.get("priority") == priority for task in tasks) for priority in ("HIGH", "MEDIUM", "LOW")}
    summaries = {
        1: "本次查询返回 %d 个任务（最多100个），后续统计仅限本次结果。" % len(tasks),
        2: "本次返回任务的优先级数量：" + json.dumps(counts, ensure_ascii=False),
        3: "建议先核对本次返回的 %d 个HIGH任务的截止时间，再安排其他任务；未修改任务。" % counts["HIGH"],
    }
    return ("completePlanStep", {"seq": seq, "summary": summaries[seq]}) if seq in summaries else None


def choose(messages):
    last = next((index for index in range(len(messages) - 1, -1, -1) if messages[index].get("role") == "user"), -1)
    user = text_content(messages[last]) if last >= 0 else ""
    results = [message for message in messages[last + 1:] if message.get("role") == "tool"]
    memory = re.fullmatch(r"请(?:长期)?记住[：:]\s*(.+)", user, re.S)
    if memory and memory[1].strip() and not results:
        return "saveMemory", {"content": memory[1].strip()}
    quoted = re.search("「([^」]+)」", user)
    if "createPlan" in user and not results:
        numbered = re.split(r"[0-9]+[)）.、]\s*", user)
        steps = [step.split("。", 1)[0].strip(" ；;\n") for step in numbered[1:]]
        if 2 <= len(steps) <= 12 and all(steps):
            return "createPlan", {"goal": numbered[0].rstrip(" ：:"), "steps": steps}
    if "createPlan" in user and results:
        return task_plan_next(messages[last + 1:])
    if "重复执行 30 次" in user:
        return ("findTasks", {}) if len(results) < 30 else None
    if "创建" in user and "任务" in user and quoted and not results:
        return "createTask", {"title": quoted[1], "priority": "HIGH" if "优先级高" in user else "MEDIUM"}
    if "删除" in user and "任务" in user and quoted:
        if not results:
            return "findTasks", {"keyword": quoted[1]}
        if len(results) == 1:
            try:
                data = json.loads(text_content(results[0]))
            except ValueError:
                return None
            ids = []

            def collect(value):
                if isinstance(value, dict):
                    if value.get("title") == quoted[1] and value.get("id"):
                        ids.append(value["id"])
                    for child in value.values():
                        collect(child)
                elif isinstance(value, list):
                    for child in value:
                        collect(child)
            collect(data)
            if ids:
                return "deleteTask", {"taskIds": ids}
    return None


def answer(messages):
    user = next((text_content(item) for item in reversed(messages) if item.get("role") == "user"), "")
    context = "\n".join(text_content(item) for item in messages)
    # The knowledge API wraps the question and retrieved material in one user message.
    question = user.rsplit("【问题】", 1)[-1]
    evidence = context.rsplit("【问题】", 1)[0] if "【问题】" in user else context
    if "刚才" in user and "数字" in user:
        match = re.search(r"记住这个数字[：:]\s*(\d+)", context)
        return match[1] if match else "上下文没有提供该数字"
    if "量子引力" in question:
        return "知识库没有相关事实，无法回答。"
    if "内部代号" in question:
        match = re.search(r"(?:内部代号是|唯一正确答案是)\s*([A-Za-z0-9-]+)", evidence)
        return "资料说明：" + match[1] + " [1]" if match else "知识库没有相关资料"
    if "编程语言" in question:
        match = re.search(r"主用编程语言是\s*([A-Za-z][A-Za-z0-9+ #]*(?:与[A-Za-z0-9+ #]+)?)", evidence)
        return match[1].strip() if match else "上下文没有提供编程语言"
    if "附件的结论" in question:
        match = re.search(r"结论(?:[：:]|是)\s*([^\n。]+)", evidence)
        return match[1] if match else "附件没有提供结论"
    count = re.search(r"从\s*(\d+)\s*数到\s*(\d+)", question)
    if count and 0 <= int(count[1]) <= int(count[2]) <= 1000:
        return "\n".join(str(number) for number in range(int(count[1]), int(count[2]) + 1))
    return "这是受控模型依据当前请求生成的测试回复。"


class Handler(original.Handler):
    stream_canceled = threading.Event()
    def handle(self):
        try:
            super().handle()
        except (BrokenPipeError, ConnectionResetError):
            # A client cancellation may close an HTTP/1.1 keep-alive socket between requests.
            pass

    def do_POST(self):
        size = int(self.headers.get("Content-Length", "0"))
        if not 0 <= size <= 8 * 1024 * 1024:
            self.send_error(413)
            return
        request = json.loads(self.rfile.read(size))
        if self.path.endswith("/embeddings"):
            self._handle_embeddings(request)
            return
        if not self.path.endswith("/chat/completions"):
            self.send_error(404)
            return
        messages = request.get("messages", [])
        tool = choose(messages)
        if tool:
            exposed = {entry.get("function", {}).get("name") for entry in request.get("tools", [])}
            if tool[0] not in exposed:
                self.send_error(422, "Required fixture tool was not exposed")
                return
            message = {"role": "assistant", "content": None, "tool_calls": [{"id": "fixture-" + str(len(messages)),
                "type": "function", "function": {"name": tool[0], "arguments": json.dumps(tool[1])}}]}
        else:
            message = {"role": "assistant", "content": answer(messages)}
        # Fixture usage is tokenizer-independent; production token budgeting has separate Java oracles.
        prompt_tokens = sum(len(original.tokenize(text_content(item))) for item in messages)
        usage = {"prompt_tokens": prompt_tokens, "completion_tokens": len(original.tokenize(message.get("content") or ""))}
        usage["total_tokens"] = sum(usage.values())
        created = int(time.time())
        finish = "tool_calls" if tool else "stop"
        if not request.get("stream"):
            payload = json.dumps({"id": "fixture", "created": created, "model": request.get("model"), "choices": [
                {"index": 0, "message": message, "finish_reason": finish}], "usage": usage}, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        try:
            if tool:
                delta = dict(message)
                delta["tool_calls"][0]["index"] = 0
                self._sse(self._chunk(created, delta, None))
            else:
                text = message["content"]
                for index in range(0, len(text), 4):
                    self._sse(self._chunk(created, {"content": text[index:index + 4]}, None))
                    time.sleep(.02)
            ending = self._chunk(created, {}, finish)
            ending["usage"] = usage
            self._sse(ending)
            self._write_chunk(b"data: [DONE]\n\n")
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            self.stream_canceled.set()
            pass
