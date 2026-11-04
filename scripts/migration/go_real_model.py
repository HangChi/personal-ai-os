"""显式读取获准模型配置；有界转发真实请求，只记录统计和正文哈希。"""
from collections import Counter
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import re
import secrets
import threading
import urllib.error
import urllib.parse
import urllib.request

MODEL_KEYS = frozenset(("AI_CHAT_PROVIDER", "AI_EMBEDDING_PROVIDER", "OPENAI_BASE_URL",
                       "OPENAI_API_KEY", "OPENAI_CHAT_MODEL", "OPENAI_EMBEDDING_MODEL",
                       "OPENAI_ENABLE_THINKING"))
MAX_RESPONSE = 32 * 1024 * 1024


class ModelFailure(ValueError):
    """错误只能包含固定代码，不能携带端点、请求、上游正文或密钥。"""


@dataclass(frozen=True, repr=False)
class ModelConfig:
    base_url: str
    api_key: str
    chat_model: str
    embedding_model: str
    enable_thinking: bool

    def __repr__(self):
        return "ModelConfig(redacted)"

    def metadata(self):
        return dict(provider="openai-compatible", endpoint_host=urllib.parse.urlsplit(self.base_url).hostname,
                    chat_model=self.chat_model, embedding_model=self.embedding_model,
                    enable_thinking=self.enable_thinking, api_key_configured=True)


def load_config(path, system=None):
    """只接收显式文件，只保留模型键；不加载数据库、认证或通知配置。"""
    path = Path(path)
    if not path.is_absolute() or path.is_symlink() or path.resolve() != path:
        raise ModelFailure("MODEL_CONFIG_PATH")
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(descriptor, "rb") as source:
            data = source.read(1024 * 1024 + 1)
        if len(data) > 1024 * 1024:
            raise ModelFailure("MODEL_CONFIG_SIZE")
        text = data.decode("utf-8")
    except (OSError, UnicodeError):
        raise ModelFailure("MODEL_CONFIG_UNREADABLE") from None
    values = {}
    for raw in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = raw.strip()
        if line.startswith("export "):
            line = line[7:].strip()
        key, separator, value = line.partition("=")
        key = key.strip()
        if not separator or key not in MODEL_KEYS:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] in ("'", '"') and value[-1] == value[0]:
            value = value[1:-1]
        values[key] = value
    system = os.environ if system is None else system
    for key in MODEL_KEYS:
        if key in system:
            values[key] = system[key]
    if any(values.get(key, "ollama") != "openai" for key in ("AI_CHAT_PROVIDER", "AI_EMBEDDING_PROVIDER")):
        raise ModelFailure("REAL_EVAL_REQUIRES_CONFIGURED_OPENAI_COMPATIBLE_MODELS")
    base = values.get("OPENAI_BASE_URL", "").strip().rstrip("/")
    try:
        url = urllib.parse.urlsplit(base)
        safe = (url.scheme == "https" and bool(url.hostname) and not url.username and not url.password
                and not url.query and not url.fragment and url.port in (None, 443))
    except ValueError:
        safe = False
    if not safe:
        raise ModelFailure("MODEL_ENDPOINT_REQUIRES_HTTPS_WITHOUT_USERINFO_QUERY")
    key = values.get("OPENAI_API_KEY", "").strip()
    if not key or not key.isascii() or any(character.isspace() for character in key):
        raise ModelFailure("MODEL_CREDENTIAL_INVALID")
    names = [values.get(name, "").strip() for name in ("OPENAI_CHAT_MODEL", "OPENAI_EMBEDDING_MODEL")]
    if any(not re.fullmatch(r"[A-Za-z0-9_./:-]{1,160}", name) for name in names):
        raise ModelFailure("MODEL_NAME_INVALID")
    thinking = values.get("OPENAI_ENABLE_THINKING", "false").lower()
    if thinking not in ("true", "false"):
        raise ModelFailure("MODEL_THINKING_INVALID")
    return ModelConfig(base, key, names[0], names[1], thinking == "true")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


class RealModel:
    def __init__(self, config, opener=None):
        self.config = config
        self.opener = opener or urllib.request.build_opener(NoRedirect())
        self.lock = threading.Lock()
        self.calls = Counter()
        self.inputs = Counter()
        self.vectors = Counter()
        self.failures = Counter()
        self.usage = Counter()
        self.characters = 0
        self.max_calls, self.max_chat_calls, self.max_characters = 600, 12, 1_000_000

    def __repr__(self):
        return "RealModel(redacted)"

    def snapshot(self):
        with self.lock:
            return dict(calls=dict(self.calls), failures=dict(self.failures), input_characters=self.characters,
                        usage=dict(self.usage), successful_embedding_inputs=sum(self.inputs.values()),
                        unique_embedding_inputs=len(self.inputs), unique_embedding_vectors=len(self.vectors),
                        limits=dict(requests=self.max_calls, chat_requests=self.max_chat_calls,
                                    input_characters=self.max_characters, chat_max_tokens=1024,
                                    upstream_timeout_seconds=25, retries=0))

    def embedding_receipts(self):
        with self.lock:
            return self.inputs.copy()

    def forward(self, path, payload):
        try:
            return self._forward(path, payload)
        except ModelFailure as error:
            with self.lock:
                self.failures[str(error)] += 1
            raise

    def _forward(self, path, payload):
        payload = dict(payload)
        if path == "/v1/embeddings":
            kind, model = "embedding", self.config.embedding_model
            inputs = payload.get("input")
            inputs = [inputs] if isinstance(inputs, str) else inputs
            if (not isinstance(inputs, list) or not 1 <= len(inputs) <= 64
                    or any(not isinstance(text, str) or not text.strip() for text in inputs)
                    or not set(payload) <= {"model", "input", "encoding_format", "dimensions"}
                    or payload.get("encoding_format", "float") != "float"
                    or payload.get("dimensions", 1024) != 1024):
                raise ModelFailure("EMBEDDING_REQUEST_INVALID")
            characters = sum(len(text) for text in inputs)
        elif path == "/v1/chat/completions":
            kind, model = "chat", self.config.chat_model
            messages = payload.get("messages")
            if (payload.get("stream", False) is not False or payload.get("tools")
                    or not isinstance(messages, list) or not messages
                    or any(not isinstance(row, dict) or row.get("role") not in ("system", "user", "assistant")
                           or not isinstance(row.get("content"), str) for row in messages)
                    or not set(payload) <= {"model", "messages", "stream", "temperature", "max_tokens",
                                           "enable_thinking", "response_format", "top_p"}):
                raise ModelFailure("CHAT_REQUEST_INVALID")
            characters = sum(len(row["content"]) for row in messages)
            maximum = payload.get("max_tokens", 1024)
            if type(maximum) is not int or maximum <= 0:
                raise ModelFailure("CHAT_TOKEN_LIMIT_INVALID")
            payload["max_tokens"] = min(maximum, 1024)
            payload["enable_thinking"] = self.config.enable_thinking
            inputs = []
        else:
            raise ModelFailure("MODEL_ROUTE_REJECTED")
        if payload.get("model") != model:
            raise ModelFailure("MODEL_IDENTITY_MISMATCH")
        with self.lock:
            if (sum(self.calls.values()) >= self.max_calls or self.characters + characters > self.max_characters
                    or (kind == "chat" and self.calls["chat"] >= self.max_chat_calls)):
                raise ModelFailure("MODEL_TEST_BUDGET_EXHAUSTED")
            self.calls[kind] += 1
            self.characters += characters
        base = self.config.base_url
        if not base.endswith("/v1"):
            base += "/v1"
        request = urllib.request.Request(base + path[3:], json.dumps(payload).encode(),
            {"Content-Type": "application/json", "Authorization": "Bearer " + self.config.api_key}, method="POST")
        try:
            with self.opener.open(request, timeout=25) as response:
                if response.status != 200:
                    raise ModelFailure("UPSTREAM_STATUS_" + str(response.status))
                raw = response.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                raise ModelFailure("UPSTREAM_RESPONSE_SIZE")
            result = json.loads(raw)
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            raise ModelFailure("UPSTREAM_HTTP_" + str(status)) from None
        except (OSError, ValueError) as error:
            if isinstance(error, ModelFailure):
                raise
            raise ModelFailure("UPSTREAM_NETWORK_OR_JSON") from None
        if not isinstance(result, dict):
            raise ModelFailure("UPSTREAM_RESPONSE_INVALID")
        if kind == "embedding":
            rows = result.get("data")
            if (not isinstance(rows, list) or len(rows) != len(inputs)
                    or any(not isinstance(row, dict) or type(row.get("index")) is not int for row in rows)
                    or sorted(row["index"] for row in rows) != list(range(len(inputs)))):
                raise ModelFailure("UPSTREAM_EMBEDDING_COUNT_OR_INDEX")
            for row in rows:
                vector = row.get("embedding")
                if (not isinstance(vector, list) or len(vector) != 1024
                        or any(type(value) not in (int, float) or not math.isfinite(value) for value in vector)
                        or not any(value != 0 for value in vector)):
                    raise ModelFailure("UPSTREAM_EMBEDDING_DIMENSION_OR_VALUE")
            with self.lock:
                self.inputs.update(hashlib.sha256(text.encode()).hexdigest() for text in inputs)
                self.vectors.update(hashlib.sha256(json.dumps(row["embedding"]).encode()).hexdigest() for row in rows)
        else:
            choices = result.get("choices")
            if (not isinstance(choices, list) or not choices or not isinstance(choices[0], dict)
                    or not isinstance(choices[0].get("message"), dict)
                    or not isinstance(choices[0]["message"].get("content"), str)
                    or not choices[0]["message"]["content"].strip()):
                raise ModelFailure("UPSTREAM_CHAT_CONTENT_MISSING")
        usage = result.get("usage")
        if isinstance(usage, dict):
            with self.lock:
                for key in ("prompt_tokens", "completion_tokens", "total_tokens"):
                    value = usage.get(key)
                    if type(value) is int and value >= 0:
                        self.usage[key] += value
        return result


@contextmanager
def serve(model):
    token = secrets.token_urlsafe(32)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            try:
                if not secrets.compare_digest(self.headers.get("Authorization", ""), "Bearer " + token):
                    raise ModelFailure("LOCAL_MODEL_AUTH_REJECTED")
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 4 * 1024 * 1024:
                    raise ModelFailure("LOCAL_MODEL_REQUEST_SIZE")
                result = model.forward(self.path, json.loads(self.rfile.read(size)))
                status = 200
            except (ValueError, TypeError):
                result, status = {"error": {"message": "Real model test request failed; inspect redacted counters"}}, 502
            raw = json.dumps(result).encode()
            try:
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
            except OSError:
                pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = False
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield dict(OPENAI_BASE_URL="http://127.0.0.1:" + str(server.server_port), OPENAI_API_KEY=token,
                   OPENAI_CHAT_MODEL=model.config.chat_model, OPENAI_EMBEDDING_MODEL=model.config.embedding_model,
                   OPENAI_ENABLE_THINKING=str(model.config.enable_thinking).lower())
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
