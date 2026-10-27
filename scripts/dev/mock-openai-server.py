#!/usr/bin/env python3
"""最小 OpenAI 兼容流式服务 —— 仅用于本地开发与验证。

用途：在没有 API Key、也不想下载本地大模型时，验证「OpenAI 兼容提供商」
这条链路的端到端行为（Spring AI 的 OpenAI 客户端 → HTTP → SSE → 我们的接口）。

它**支持工具调用**，因此也能用来端到端验证 Agent 链路（模型发起工具调用 →
框架执行工具 → 数据库真实变化 → 二次调用给出自然语言结论）。

它**也实现了 `/v1/embeddings`**，用确定性词袋哈希生成 1024 维单位向量
（与 Flyway V4 的 `vector(1024)` 一致）。这样在没有 Ollama、没有 API Key 的机器上
也能把知识库链路真正跑起来：上传 → 分块 → 向量化 → 入库 → 检索 → 引用。

⚠️ **它不是语义模型，相似度尺度与真实模型差一个量级**：800 字的分块与一句问句之间，
真实模型能给出 0.5–0.8，这里只有 0.1–0.3（词袋的固有性质：长文本方向分散）。
所以用 mock 联调知识库时，**必须同时把阈值调低**：

    AI_KNOWLEDGE_SIMILARITY_THRESHOLD=0.05

排序仍然是正确的（相关 > 无关），足以验证链路通不通、命中顺序对不对；
但它**不能用来评估真实嵌入质量**，也不能用来看默认阈值 0.3 下会不会命中。

它不是产品代码，也不参与构建与测试；集成测试用的是 Java 侧的脚本化桩 ChatModel。

端点：
    POST /v1/chat/completions   按 `stream` 字段决定返回 SSE（对话）还是 JSON（问答等 `.call()`）
    POST /v1/embeddings         确定性词袋向量，1024 维

用法：
    python3 scripts/dev/mock-openai-server.py --port 9099

配合启动后端（对话 + 嵌入都指向本服务）：
    AI_CHAT_PROVIDER=openai \\
    AI_EMBEDDING_PROVIDER=openai \\
    AI_KNOWLEDGE_SIMILARITY_THRESHOLD=0.05 \\
    OPENAI_BASE_URL=http://127.0.0.1:9099 \\
    OPENAI_API_KEY=mock-key \\
    OPENAI_CHAT_MODEL=mock-model \\
    OPENAI_EMBEDDING_MODEL=mock-embedding \\
    ./mvnw spring-boot:run

只验证知识库（对话仍走别处）时，把 `AI_EMBEDDING_PROVIDER` 单独设为 `openai` 并保留
上面的 `OPENAI_BASE_URL` 即可；`OPENAI_EMBEDDING_MODEL` 随便填，mock 不校验模型名。

触发协议（用自然语言前缀模拟"模型决定调用工具"）：
    「创建任务：写周报」        -> 调用 createTask
    「创建任务：写周报|HIGH」   -> 调用 createTask 并指定优先级
    「查看待办」                -> 调用 findTasks
    「删除任务：<uuid>」        -> 调用 deleteTask（注意：需人工确认才会生效）
    「MCP列目录」               -> 调用 mcp_sandbox_list_directory（只读，直接执行）
    「MCP写文件：<路径>|<内容>」 -> 调用 mcp_sandbox_write_file（变更类，需人工确认）

注意 MCP 两条前缀里的 `sandbox` 是 `.env` 里配置的 server 名——
工具名必须与 MCP_SERVERS_<名>_* 的 `<名>` 一致，否则模型会调用一个不存在的工具。

只依赖 Python 标准库。
"""

import argparse
import hashlib
import json
import math
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PLAIN_CHUNKS = ["这是", "来自", "本地模拟模型", "的流式回复。"]
FINAL_TEXT = "已完成你的请求。"

# 每个 SSE 帧之间的人为延迟（秒），默认 0 —— 正常联调不该变慢。
#
# 设为正值是为了验证**长流**：后端的 SSE 心跳间隔是 15 秒，而模拟模型输出
# 通常在 1 秒内结束，那样永远看不到心跳帧，也就无法确认它真的被刷出去了。
#
#     python3 scripts/dev/mock-openai-server.py --chunk-delay-ms 2000
#
# 配合 PLAIN_CHUNKS 的四段内容，整条流会持续约 8 秒以上，足以跨过心跳周期。
CHUNK_DELAY_SECONDS = 0.0

# 必须与迁移里的 vector(1024) 一致，否则应用启动期的维度守卫会直接失败
EMBEDDING_DIMENSIONS = 1024


def tokenize(text):
    """切出用于哈希的语义单元：拉丁按词，中文按**单字**。

    为什么是单字而不是 bigram：实测在 800 字的真实分块上，bigram 的相关问句余弦只有
    0.24，单字能到 0.36（重叠的 token 更多）；两者对无关文本都接近 0。
    单字放弃了一部分词序信息，但 mock 的目标是「链路可验证 + 排序正确」，不是语义质量。
    """
    lowered = (text or "").lower()
    tokens = ["w:" + word for word in re.findall(r"[a-z0-9]+", lowered)]
    tokens.extend("z:" + char for char in re.findall(r"[\u4e00-\u9fff]", lowered))
    return tokens


def embed_text(text, dimensions):
    """确定性词袋嵌入：token 哈希到维度上带符号累加，最后 L2 归一化。

    归一化是为了让余弦相似度等价于点积，与 `vector_cosine_ops` 索引的语义对齐。
    """
    vector = [0.0] * dimensions
    tokens = tokenize(text) or ["empty"]

    for token in tokens:
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        index = int.from_bytes(digest[:4], "big") % dimensions
        vector[index] += 1.0 if digest[4] % 2 == 0 else -1.0

    norm = math.sqrt(sum(value * value for value in vector))
    if norm == 0.0:
        # 所有 token 互相抵消（极罕见）：退化成一个固定方向，保证不是零向量
        vector[0] = 1.0
        return vector
    return [value / norm for value in vector]


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

    # ---- MCP 外部工具（阶段 6）----
    # 前缀里的 server 名与真实配置一致（见 .env 的 MCP_SERVERS_<名>_*），
    # 因此这里的工具名就是模型真正会看到的装配名（mcp_<server>_<tool>）。
    if "MCP列目录" in text:
        return "mcp_sandbox_list_directory", {"path": "."}

    match = re.match(r"^MCP写文件[:：]\s*([^|]+)\|(.+)$", text, re.DOTALL)
    if match:
        # write_file 是变更类工具（不在 AUTO_TOOLS 清单里）→ 应触发人工确认
        return "mcp_sandbox_write_file", {
            "path": match.group(1).strip(),
            "content": match.group(2).strip(),
        }

    return None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _write_chunk(self, payload: bytes) -> None:
        """按 HTTP/1.1 chunked 传输编码写一段数据。"""
        self.wfile.write(("%X\r\n" % len(payload)).encode("ascii"))
        self.wfile.write(payload)
        self.wfile.write(b"\r\n")
        self.wfile.flush()
        # 放在 flush 之后：先让客户端真的收到这一帧，延迟才代表「模型在思考」
        if CHUNK_DELAY_SECONDS > 0:
            time.sleep(CHUNK_DELAY_SECONDS)

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

    def _handle_embeddings(self, request) -> None:
        """实现 OpenAI `/v1/embeddings` 的最小响应格式。

        `input` 允许是字符串或字符串数组（OpenAI 两种都收）。
        `dimensions` 若被显式传入则按其返回，但收敛到 [1, 4096]，
        避免客户端传入荒谬值时分配巨大数组。
        """
        raw_input = request.get("input")
        if isinstance(raw_input, str):
            inputs = [raw_input]
        elif isinstance(raw_input, list):
            # 也接受 token id 数组：直接转成字符串参与哈希，保证不 500
            inputs = [item if isinstance(item, str) else str(item) for item in raw_input]
        else:
            inputs = [""]

        try:
            dimensions = int(request.get("dimensions") or EMBEDDING_DIMENSIONS)
        except (TypeError, ValueError):
            dimensions = EMBEDDING_DIMENSIONS
        dimensions = max(1, min(dimensions, 4096))

        data = []
        total_tokens = 0
        for index, text in enumerate(inputs):
            total_tokens += len(text)
            data.append({
                "object": "embedding",
                "index": index,
                "embedding": embed_text(text, dimensions),
            })
            print("[mock] 嵌入 %d 字符 -> %d 维" % (len(text), dimensions))

        body = json.dumps({
            "object": "list",
            "data": data,
            "model": request.get("model") or "mock-embedding",
            "usage": {"prompt_tokens": total_tokens, "total_tokens": total_tokens},
        }).encode("utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _handle_chat_json(self, request, tool_call, tool_already_ran) -> None:
        """非流式（`stream` 为假）的 `/v1/chat/completions`。

        知识库问答 `KnowledgeQaService.ask` 用的是 `chatClient.call()`，是**非流式**调用。
        只实现 SSE 会让它拿到 `data: {...}` 后直接 JSON 解析失败
        （`Unrecognized token 'data'`），表现为问答接口 500 —— 与嵌入无关的另一个盲区。
        """
        created = int(time.time())

        if tool_call is not None:
            tool_name, arguments = tool_call
            print("[mock] 触发工具调用（非流式）: %s %s" % (tool_name, json.dumps(arguments, ensure_ascii=False)))
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [{
                    "id": "call_mock_1",
                    "type": "function",
                    "function": {
                        "name": tool_name,
                        "arguments": json.dumps(arguments, ensure_ascii=False),
                    },
                }],
            }
            finish_reason = "tool_calls"
        else:
            message = {
                "role": "assistant",
                "content": FINAL_TEXT if tool_already_ran else "这是来自本地模拟模型的回复。",
            }
            finish_reason = "stop"

        prompt_chars = sum(len(str(item.get("content") or "")) for item in (request.get("messages") or []))
        body = json.dumps({
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "created": created,
            "model": request.get("model") or "mock-model",
            "choices": [{"index": 0, "message": message, "finish_reason": finish_reason}],
            "usage": {
                "prompt_tokens": prompt_chars,
                "completion_tokens": len(message.get("content") or ""),
                "total_tokens": prompt_chars + len(message.get("content") or ""),
            },
        }).encode("utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):  # noqa: N802  (BaseHTTPRequestHandler 约定)
        length = int(self.headers.get("Content-Length") or 0)
        raw_body = self.rfile.read(length)
        try:
            request = json.loads(raw_body or b"{}")
        except json.JSONDecodeError:
            request = {}

        if self.path.endswith("/embeddings"):
            self._handle_embeddings(request)
            return

        if not self.path.endswith("/chat/completions"):
            self.send_error(404, "only /v1/chat/completions and /v1/embeddings are mocked")
            return

        messages = request.get("messages") or []
        # 已经出现过 tool 结果消息 -> 说明工具已执行完，本轮给出自然语言结论
        tool_already_ran = any(message.get("role") == "tool" for message in messages)
        user_content = ""
        for message in reversed(messages):
            if message.get("role") == "user":
                user_content = message.get("content") or ""
                break

        tool_call = None if tool_already_ran else pick_tool_call(user_content)

        # 非流式调用（知识库问答、以及任何 `.call()`）走 JSON；流式（对话）走 SSE
        if not request.get("stream"):
            self._handle_chat_json(request, tool_call, tool_already_ran)
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        created = int(time.time())

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
    parser.add_argument(
        "--chunk-delay-ms",
        type=int,
        default=0,
        help="每个 SSE 帧之间人为延迟的毫秒数（验证长流与心跳用，默认 0）",
    )
    args = parser.parse_args()

    global CHUNK_DELAY_SECONDS
    CHUNK_DELAY_SECONDS = max(args.chunk_delay_ms, 0) / 1000.0

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print("mock OpenAI-compatible server listening on http://%s:%d" % (args.host, args.port))
    print("POST /v1/chat/completions  -> SSE（stream=true）或 JSON（stream=false，支持 tool_calls）")
    print("POST /v1/embeddings        -> JSON（%d 维确定性词袋向量）" % EMBEDDING_DIMENSIONS)
    server.serve_forever()


if __name__ == "__main__":
    main()
