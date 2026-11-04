"""显式读取获准模型配置；有界转发真实请求，只记录统计和正文哈希。"""
from collections import Counter
from contextlib import contextmanager
from dataclasses import dataclass
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import math
import os
from pathlib import Path
import re
import secrets
import select
import struct
import subprocess
import threading
import time
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


class CurlOpener:
    """沿用采集器已验证的curl网络通路；配置/密钥/正文只经stdin传递。"""
    def open(self, request, timeout):
        config = "\n".join((
            "url = " + json.dumps(request.full_url),
            'request = "POST"',
            "header = " + json.dumps("Content-Type: application/json"),
            "header = " + json.dumps("Authorization: " + request.get_header("Authorization")),
            "data-binary = " + json.dumps(request.data.decode("utf-8")),
        )) + "\n"
        try:
            result = subprocess.run([
                "curl", "--silent", "--show-error", "--compressed", "--proto", "=https",
                "--max-redirs", "0", "--connect-timeout", "10", "--max-time", str(timeout),
                "--max-filesize", str(MAX_RESPONSE), "--retry", "0", "--config", "-",
                "--write-out", "\n%{http_code}",
            ], input=config.encode(), capture_output=True, timeout=timeout + 5)
        except (OSError, subprocess.TimeoutExpired):
            raise ModelFailure("UPSTREAM_CURL_LOCAL_OR_TIMEOUT") from None
        if result.returncode:
            raise ModelFailure("UPSTREAM_CURL_EXIT_" + str(result.returncode))
        raw, separator, status = result.stdout.rpartition(b"\n")
        if not separator or len(raw) > MAX_RESPONSE or not re.fullmatch(rb"[0-9]{3}", status):
            raise ModelFailure("UPSTREAM_CURL_RESPONSE_INVALID")
        response = io.BytesIO(raw)
        response.status = int(status)
        if response.status != 200:
            raise urllib.error.HTTPError("redacted", response.status, "upstream rejected", {}, response)
        return response


class PooledOpener:
    name = "node-pooled-fetch"

    def __init__(self, process):
        self.process = process
        self.lock = threading.Lock()

    def exchange(self, value):
        with self.lock:
            try:
                self.process.stdin.write(json.dumps(value).encode() + b"\n")
                self.process.stdin.flush()
                data = bytearray()
                deadline = time.monotonic() + 95
                while not data.endswith(b"\n"):
                    remaining = deadline - time.monotonic()
                    if remaining <= 0 or not select.select([self.process.stdout], [], [], remaining)[0]:
                        raise ModelFailure("POOLED_TRANSPORT_TIMEOUT")
                    block = os.read(self.process.stdout.fileno(), 65536)
                    if not block or len(data) + len(block) > 48 * 1024 * 1024:
                        raise ModelFailure("POOLED_TRANSPORT_CLOSED_OR_SIZE")
                    data.extend(block)
                return json.loads(data)
            except (OSError, ValueError):
                raise ModelFailure("POOLED_TRANSPORT_PROTOCOL") from None

    def open(self, request, timeout):
        result = self.exchange(dict(url=request.full_url, body=request.data.decode()))
        if result.get("error"):
            code = result["error"]
            if code not in ("REQUEST_REJECTED", "RESPONSE_TOO_LARGE", "ECONNRESET", "ECONNREFUSED",
                            "ETIMEDOUT", "ENOTFOUND", "UND_ERR_CONNECT_TIMEOUT", "UND_ERR_SOCKET",
                            "CERT_HAS_EXPIRED", "ERR_TLS_CERT_ALTNAME_INVALID",
                            "UNABLE_TO_VERIFY_LEAF_SIGNATURE", "NETWORK_OR_TIMEOUT"):
                code = "PROTOCOL"
            raise ModelFailure("UPSTREAM_POOLED_" + code)
        if type(result.get("status")) is not int:
            raise ModelFailure("POOLED_TRANSPORT_STATUS")
        try:
            raw = base64.b64decode(result.get("body", ""), validate=True)
        except (ValueError, TypeError):
            raise ModelFailure("POOLED_TRANSPORT_BODY") from None
        if len(raw) > MAX_RESPONSE:
            raise ModelFailure("POOLED_TRANSPORT_SIZE")
        response = io.BytesIO(raw)
        response.status = result["status"]
        if response.status != 200:
            raise urllib.error.HTTPError("redacted", response.status, "upstream rejected", {}, response)
        return response


@contextmanager
def pooled_transport(config):
    names = ("PATH", "HOME", "TMPDIR", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
             "http_proxy", "https_proxy", "no_proxy")
    environment = {key: os.environ[key] for key in names if key in os.environ}
    script = Path(__file__).with_name("real_model_transport.mjs")
    process = subprocess.Popen(["node", "--use-env-proxy", str(script)], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=environment, bufsize=0)
    try:
        opener = PooledOpener(process)
        if opener.exchange(dict(base_url=config.base_url, api_key=config.api_key)) != {"ready": True}:
            raise ModelFailure("POOLED_TRANSPORT_INIT")
        yield opener
    finally:
        process.stdin.close()
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        process.stdout.close()


class RealModel:
    def __init__(self, config, opener=None):
        self.config = config
        self.opener = opener or CurlOpener()
        self.lock = threading.Lock()
        self.calls = Counter()
        self.inputs = Counter()
        self.vectors = Counter()
        self.failures = Counter()
        self.routes = Counter()
        self.usage = Counter()
        self.last_upstream_rejection = None
        self.last_transport_failure = None
        self.last_rejected_embedding_shape = None
        self.characters = 0
        self.max_calls, self.max_chat_calls, self.max_characters = 600, 12, 1_000_000

    def __repr__(self):
        return "RealModel(redacted)"

    def snapshot(self):
        with self.lock:
            return dict(calls=dict(self.calls), failures=dict(self.failures), input_characters=self.characters,
                        routes=dict(self.routes),
                        transport="node-pooled-fetch" if isinstance(self.opener, PooledOpener) else "curl-or-test-injection",
                        usage=dict(self.usage), successful_embedding_inputs=sum(self.inputs.values()),
                        last_upstream_rejection=self.last_upstream_rejection,
                        last_transport_failure=self.last_transport_failure,
                        last_rejected_embedding_shape=self.last_rejected_embedding_shape,
                        unique_embedding_inputs=len(self.inputs), unique_embedding_vectors=len(self.vectors),
                        limits=dict(requests=self.max_calls, chat_requests=self.max_chat_calls,
                                    input_characters=self.max_characters, chat_max_tokens=1024,
                                    upstream_timeout_seconds=90, retries=0))

    def embedding_receipts(self):
        with self.lock:
            return self.inputs.copy()

    def forward(self, path, payload):
        # Java SDK appends /embeddings directly; Go uses the /v1 route.
        aliases = {"/embeddings": "/v1/embeddings", "/chat/completions": "/v1/chat/completions"}
        with self.lock:
            self.routes[path if path in (*aliases, *aliases.values()) else "REJECTED"] += 1
        path = aliases.get(path, path)
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
                    or payload.get("encoding_format", "float") not in ("float", "base64")
                    or payload.get("dimensions", 1024) != 1024):
                with self.lock:
                    encoding = payload.get("encoding_format", "MISSING")
                    self.last_rejected_embedding_shape = dict(
                        input_count=len(inputs) if isinstance(inputs, list) else None,
                        encoding=encoding if encoding in ("float", "base64", "MISSING", None) else "OTHER",
                        dimensions_present="dimensions" in payload,
                        dimensions_null=payload.get("dimensions") is None,
                        dimensions_1024=payload.get("dimensions") == 1024,
                        unknown_fields=len(set(payload) - {"model", "input", "encoding_format", "dimensions"}))
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
            with self.opener.open(request, timeout=90) as response:
                if response.status != 200:
                    raise ModelFailure("UPSTREAM_STATUS_" + str(response.status))
                raw = response.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                raise ModelFailure("UPSTREAM_RESPONSE_SIZE")
            result = json.loads(raw)
        except urllib.error.HTTPError as error:
            status = error.code
            try:
                detail = json.loads(error.read(65536))
                detail = detail.get("error", detail) if isinstance(detail, dict) else {}
                message = detail.get("message", "") if isinstance(detail, dict) else ""
                code = detail.get("code", "") if isinstance(detail, dict) else ""
                if not isinstance(message, str):
                    message = ""
                if code not in ("InvalidParameter", "InvalidApiKey", "AccessDenied", "Throttling",
                                "Throttling.RateQuota", "insufficient_quota", "invalid_api_key",
                                "rate_limit_exceeded", "BadRequest"):
                    code = "UNSPECIFIED"
                with self.lock:
                    self.last_upstream_rejection = dict(
                        status=status, code=code, input_count=len(inputs),
                        maximum_input_characters=max((len(text) for text in inputs), default=0),
                        input_characters=characters,
                        mentions_token="token" in message.lower(),
                        mentions_batch="batch" in message.lower(),
                        mentions_length="length" in message.lower(),
                        batch_limits=[int(value) for value in re.findall(
                            r"(?:maximum|max|at most|larger than|less than|up to|exceed)\s*:?\s*(\d{1,4})",
                            message.lower())] if "batch" in message.lower() else [],
                        numeric_range=[int(value) for value in re.findall(
                            r"\[\s*(\d{1,7})\s*,\s*(\d{1,7})\s*\]", message)[0]]
                            if re.search(r"\[\s*\d{1,7}\s*,\s*\d{1,7}\s*\]", message) else None)
            except (OSError, ValueError, TypeError):
                pass
            error.close()
            raise ModelFailure("UPSTREAM_HTTP_" + str(status)) from None
        except (OSError, ValueError) as error:
            if isinstance(error, ModelFailure):
                raise
            cause = getattr(error, "reason", error)
            name = type(cause).__name__
            if name not in ("TimeoutError", "SSLError", "SSLCertVerificationError", "ConnectionResetError",
                            "ConnectionRefusedError", "RemoteDisconnected", "JSONDecodeError", "URLError"):
                tunnel = re.search(r"Tunnel connection failed:\s*([0-9]{3})", str(cause))
                name = "PROXY_TUNNEL_HTTP_" + tunnel[1] if tunnel else "OTHER_NETWORK_OR_JSON"
            with self.lock:
                self.last_transport_failure = dict(category=name, input_count=len(inputs),
                                                   input_characters=characters)
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
                if payload.get("encoding_format") == "base64":
                    try:
                        if not isinstance(vector, str):
                            raise ValueError()
                        decoded = base64.b64decode(vector, validate=True)
                        if len(decoded) != 1024 * 4:
                            raise ValueError()
                        vector = list(struct.unpack("<1024f", decoded))
                    except (ValueError, TypeError):
                        raise ModelFailure("UPSTREAM_EMBEDDING_BASE64_INVALID") from None
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
