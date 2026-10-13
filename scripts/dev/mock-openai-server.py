#!/usr/bin/env python3
"""最小 OpenAI 兼容流式服务 —— 仅用于本地开发与验证。

用途：在没有 API Key、也不想下载本地大模型时，验证「OpenAI 兼容提供商」
这条链路的端到端行为（Spring AI 的 OpenAI 客户端 → HTTP → SSE → 我们的接口）。

它**支持工具调用**，因此也能用来端到端验证 Agent 链路（模型发起工具调用 →
框架执行工具 → 数据库真实变化 → 二次调用给出自然语言结论）。

它不是产品代码，也不参与构建与测试；集成测试用的是 Java 侧的脚本化桩 ChatModel。

用法：
    python3 scripts/dev/mock-openai-server.py --port 9099

配合启动后端：
    AI_CHAT_PROVIDER=openai \\
    OPENAI_BASE_URL=http://127.0.0.1:9099 \\
    OPENAI_API_KEY=mock-key \\
    OPENAI_CHAT_MODEL=mock-model \\
    ./mvnw spring-boot:run

触发协议（用自然语言前缀模拟"模型决定调用工具"）：
    「创建任务：写周报」        -> 调用 createTask
    「创建任务：写周报|HIGH」   -> 调用 createTask 并指定优先级
    「查看待办」                -> 调用 findTasks
    「删除任务：<uuid>」        -> 调用 deleteTask（注意：需人工确认才会生效）
    其他任意内容                -> 直接文本回复

只依赖 Python 标准库。
"""

import argparse
import json
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PLAIN_CHUNKS = ["这是", "来自", "本地模拟模型", "的流式回复。"]
FINAL_TEXT = "已完成你的请求。"


def pick_tool_call(user_content):
    """按前缀协议决定要调用哪个工具；返回 (tool_name, arguments_dict) 或 None。"""
    text = (user_content or "").strip()

    match = re.match(r"^创建任务[:：](.+)$", text)
    if match:
        body = match.group(1).strip()
        title, _, priority = body.partition("|")
        arguments = {"title": title.strip()}
        if priority.strip():
            arguments["priority"] = priority.strip().upper()
        return "createTask", arguments

    if "查看待办" in text or "有哪些待办" in text:
        return "findTasks", {"status": "TODO"}

    match = re.match(r"^删除任务[:：]\s*([0-9a-fA-F-]{36})$", text)
    if match:
        return "deleteTask", {"taskIds": [match.group(1)]}

    return None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _write_chunk(self, payload: bytes) -> None:
        """按 HTTP/1.1 chunked 传输编码写一段数据。"""
        self.wfile.write(("%X\r\n" % len(payload)).encode("ascii"))
        self.wfile.write(payload)
        self.wfile.write(b"\r\n")
        self.wfile.flush()

    def _sse(self, obj) -> None:
        self._write_chunk(("data: %s\n\n" % json.dumps(obj, ensure_ascii=False)).encode("utf-8"))

    def _chunk(self, created, delta, finish_reason):
        return {
            "id": "chatcmpl-mock",
            "object": "chat.completion.chunk",
            "created": created,
            "model": "mock-model",
            "choices": [{"index": 0, "delta": delta, "finish_reason": finish_reason}],
        }

    def do_POST(self):  # noqa: N802  (BaseHTTPRequestHandler 约定)
        length = int(self.headers.get("Content-Length") or 0)
        raw_body = self.rfile.read(length)
        try:
            request = json.loads(raw_body or b"{}")
        except json.JSONDecodeError:
            request = {}

        if not self.path.endswith("/chat/completions"):
            self.send_error(404, "only /v1/chat/completions is mocked")
            return

        messages = request.get("messages") or []
        # 已经出现过 tool 结果消息 -> 说明工具已执行完，本轮给出自然语言结论
        tool_already_ran = any(message.get("role") == "tool" for message in messages)
        user_content = ""
        for message in reversed(messages):
            if message.get("role") == "user":
                user_content = message.get("content") or ""
                break

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        created = int(time.time())
        tool_call = None if tool_already_ran else pick_tool_call(user_content)

        if tool_call is not None:
            tool_name, arguments = tool_call
            print("[mock] 触发工具调用: %s %s" % (tool_name, json.dumps(arguments, ensure_ascii=False)))
            self._sse(self._chunk(created, {
                "role": "assistant",
                "content": None,
                "tool_calls": [{
                    "index": 0,
                    "id": "call_mock_1",
                    "type": "function",
                    "function": {
                        "name": tool_name,
                        "arguments": json.dumps(arguments, ensure_ascii=False),
                    },
                }],
            }, None))
            self._sse(self._chunk(created, {}, "tool_calls"))
        else:
            text = FINAL_TEXT if tool_already_ran else None
            chunks = [text] if text else PLAIN_CHUNKS
            for index, chunk in enumerate(chunks):
                delta = {"role": "assistant", "content": chunk} if index == 0 else {"content": chunk}
                self._sse(self._chunk(created, delta, None))
                time.sleep(0.06)
            self._sse(self._chunk(created, {}, "stop"))

        self._write_chunk(b"data: [DONE]\n\n")
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    def log_message(self, *args):  # 静默，避免污染验证输出
        pass


def main() -> None:
    parser = argparse.ArgumentParser(description="最小 OpenAI 兼容流式服务（仅供开发验证）")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9099)
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print("mock OpenAI-compatible server listening on http://%s:%d" % (args.host, args.port))
    print("POST /v1/chat/completions  -> SSE（支持 tool_calls）")
    server.serve_forever()


if __name__ == "__main__":
    main()
