#!/usr/bin/env python3
"""最小 OpenAI 兼容流式服务 —— 仅用于本地开发与验证。

用途：在没有 API Key、也不想下载本地大模型时，验证「OpenAI 兼容提供商」
这条链路的端到端行为（Spring AI 的 OpenAI 客户端 → HTTP → SSE → 我们的接口）。

它不是产品代码，也不参与构建与测试；集成测试用的是 Java 侧的桩 ChatModel。

用法：
    python3 scripts/dev/mock-openai-server.py --port 9099

配合启动后端：
    AI_CHAT_PROVIDER=openai \\
    OPENAI_BASE_URL=http://127.0.0.1:9099 \\
    OPENAI_API_KEY=mock-key \\
    OPENAI_CHAT_MODEL=mock-model \\
    ./mvnw spring-boot:run

只依赖 Python 标准库。
"""

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# 逐段返回，便于观察 SSE 的增量效果
REPLY_CHUNKS = ["这是", "来自", "本地模拟模型", "的流式回复。"]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _write_chunk(self, payload: bytes) -> None:
        """按 HTTP/1.1 chunked 传输编码写一段数据。"""
        self.wfile.write(("%X\r\n" % len(payload)).encode("ascii"))
        self.wfile.write(payload)
        self.wfile.write(b"\r\n")
        self.wfile.flush()

    def _sse(self, obj) -> None:
        self._write_chunk(("data: %s\n\n" % json.dumps(obj)).encode("utf-8"))

    def do_POST(self):  # noqa: N802  (BaseHTTPRequestHandler 约定)
        length = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(length)

        if not self.path.endswith("/chat/completions"):
            self.send_error(404, "only /v1/chat/completions is mocked")
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        created = int(time.time())
        for index, chunk in enumerate(REPLY_CHUNKS):
            delta = {"role": "assistant", "content": chunk} if index == 0 else {"content": chunk}
            self._sse({
                "id": "chatcmpl-mock",
                "object": "chat.completion.chunk",
                "created": created,
                "model": "mock-model",
                "choices": [{"index": 0, "delta": delta, "finish_reason": None}],
            })
            time.sleep(0.08)  # 让增量分批到达，模拟真实流式

        self._sse({
            "id": "chatcmpl-mock",
            "object": "chat.completion.chunk",
            "created": created,
            "model": "mock-model",
            "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
        })
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
    print("POST /v1/chat/completions  -> SSE, chunks=%r" % (REPLY_CHUNKS,))
    server.serve_forever()


if __name__ == "__main__":
    main()
