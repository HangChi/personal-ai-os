#!/usr/bin/env python3
"""仅由临时数据库 runner 调用，真实二进制验证 P1 基础进程与退出。"""

from contextlib import ExitStack, contextmanager
import http.cookiejar
import http.client
import json
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def reserve_port():
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        return reservation.getsockname()[1]


def request(port, method, path, body=None, token=None, opener=None):
    headers = {"X-Request-Id": "fixture-foundation-process"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token is not None:
        headers["X-CSRF-TOKEN"] = token
    call = urllib.request.Request(f"http://127.0.0.1:{port}" + path, data=body, headers=headers, method=method)
    # 不读取系统代理、不跟随重定向到其他地址。
    if opener is None:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        response = opener.open(call, timeout=3)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, dict(response.headers), response.read()


def child_environment(fixture, role, port, management, ca_file):
    if fixture.get("GO_TEST_POSTGRES") != "disposable" or fixture.get("GO_TEST_FLYWAY") != "disposable":
        raise ValueError("基础进程只接受本脚本新建并完成真实 Flyway 的数据库")
    if (fixture.get("GO_TEST_POSTGRES_HOST"), fixture.get("GO_TEST_POSTGRES_DB"), fixture.get("GO_TEST_POSTGRES_USER")) != ("127.0.0.1", "personalos_go_test", "go_fixture"):
        raise ValueError("基础进程禁止连接现有数据库")
    env = {key: fixture[key] for key in ("PATH", "HOME", "TMPDIR") if key in fixture}
    env.update({
        "GO_DOTENV_ENABLED": "false", "APP_DOTENV_ENABLED": "false", "SERVER_PORT": str(port),
        "MANAGEMENT_SERVER_PORT": str(management), "APP_RUNTIME_ROLE": role,
        "SPRING_PROFILES_ACTIVE": "monitoring" + (",runtime-api" if role == "api" else ",worker-" + role if role in {"index", "review", "delivery"} else ""),
        "AUTH_ENABLED": "true", "AUTH_PASSWORD": "fixture-process-password", "APP_SECURITY_CSRF_ENABLED": "true",
        "APP_RATE_LIMIT_CAPACITY": "2", "APP_RATE_LIMIT_PERIOD": "1h",
        "POSTGRES_HOST": fixture["GO_TEST_POSTGRES_HOST"], "POSTGRES_PORT": fixture["GO_TEST_POSTGRES_PORT"],
        "POSTGRES_DB": fixture["GO_TEST_POSTGRES_DB"], "POSTGRES_USER": fixture["GO_TEST_POSTGRES_USER"],
        "POSTGRES_PASSWORD": fixture["GO_TEST_POSTGRES_PASSWORD"],
        "GO_POSTGRES_SSL_MODE": fixture["GO_TEST_POSTGRES_SSL_MODE"],
        "GO_POSTGRES_ROOT_CERT": fixture["GO_TEST_POSTGRES_ROOT_CERT"],
        "LOGGING_STRUCTURED_FORMAT_CONSOLE": "ecs",
    })
    if fixture.get("GO_TEST_REDIS") == "disposable":
        env.update({
            "REDIS_SESSION_ENABLED": "true", "REDIS_SESSION_URI": fixture["GO_TEST_REDIS_SESSION_URI"],
            "REDIS_SESSION_NAMESPACE": "fixture:app-sessions", "REDIS_RATE_LIMIT_ENABLED": "true",
            "REDIS_RATE_LIMIT_URI": fixture["GO_TEST_REDIS_RATE_URI"], "REDIS_RATE_LIMIT_NAMESPACE": "fixture:app-rate",
        })
        if fixture.get("GO_TEST_REDIS_CA_PEM"):
            env["GO_REDIS_SESSION_ROOT_CERT"] = str(ca_file)
            env["GO_REDIS_RATE_LIMIT_ROOT_CERT"] = str(ca_file)
    return env


def assert_released(port):
    with socket.socket() as probe:
        probe.settimeout(1)
        if probe.connect_ex(("127.0.0.1", port)) == 0:
            raise AssertionError("基础进程退出后端口未释放")


@contextmanager
def process(binary, fixture, role, directory, ca_file):
    port, management = reserve_port(), reserve_port()
    while management == port:
        management = reserve_port()
    env = child_environment(fixture, role, port, management, ca_file)
    with tempfile.TemporaryFile(mode="w+") as log:
        child = subprocess.Popen([str(binary), "--foundation", "--migrations", str(ROOT / "backend/src/main/resources/db/migration")],
                                 cwd=directory, env=env, stdout=log, stderr=log, text=True)
        try:
            deadline = time.monotonic() + 15
            while True:
                if child.poll() is not None:
                    raise AssertionError("基础进程在 HTTP 启动前退出")
                try:
                    status, _, body = request(management, "GET", "/actuator/health")
                    if status != 200 or json.loads(body) != {"status":"UP","groups":["liveness","readiness"]}:
                        raise AssertionError("基础依赖启动后未健康")
                    break
                except (OSError, http.client.HTTPException, urllib.error.URLError):
                    if time.monotonic() >= deadline:
                        raise TimeoutError("基础进程启动超时")
                    time.sleep(0.05)
            yield port, management
            child.send_signal(signal.SIGTERM)
            code = child.wait(timeout=12)
            if code != 0:
                raise AssertionError("基础进程 SIGTERM 非零退出")
            assert_released(port)
            assert_released(management)
        finally:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
            log.seek(0)
            logs = log.read()
            rows = [json.loads(line) for line in logs.splitlines() if line.strip()]
            if not rows or not all(row.get("ecs.version") and row.get("service.runtime") == "go" and row.get("requestId") for row in rows):
                raise AssertionError("真实基础进程未装配ECS/请求ID日志")
            # 即使失败也不打印原始配置/凭据；只验证日志没有泄漏。
            for key in ("POSTGRES_PASSWORD", "AUTH_PASSWORD", "REDIS_SESSION_URI", "REDIS_RATE_LIMIT_URI"):
                if env.get(key) and env[key] in logs:
                    raise AssertionError("基础进程日志泄漏敏感配置")


def verify_role(role, port, management):
    status, _, body = request(management, "HEAD", "/actuator/health")
    assert status == 403 and body == b"", "管理 HEAD 不应自动允许"
    status, _, _ = request(management, "GET", "/api/v1/auth/csrf")
    assert status == 403, "独立管理端口不能进入业务"
    status, _, body = request(management, "GET", "/actuator/prometheus")
    expected = 4 if role in {"index", "review", "delivery"} else 10
    assert status == 200 and f'personalos_go_database_connections{{state="max"}} {expected}\n'.encode() in body, "profile 未配置实际池上限"
    assert b"http_server_requests_seconds_bucket{" in body and b"go_goroutines " in body, "真实管理端口未采集HTTP直方图/Go运行时"
    status, _, body = request(port, "GET", "/api/v1/health")
    if role in {"index", "review", "delivery"}:
        assert status == 404 and body == b"", "Worker 业务必须空404"
        status, headers, body = request(port, "POST", "/api/v1/auth/csrf")
        assert status == 404 and body == b"" and "Set-Cookie" not in headers, "Worker 不应创建会话"
    else:
        data = json.loads(body)
        assert status == 200 and data["data"]["components"] == {"database": "UP", "ai": "NOT_CONFIGURED"}, "不能以模型假配置冒充健康"
        status, _, _ = request(port, "GET", "/api/v1/tasks")
        assert status == 401, "未登录必须先通过认证"
        status, _, body = request(port, "GET", "/api/v1/auth/me")
        assert status == 200 and json.loads(body)["data"]["username"] is None, "匿名 me 契约"


def verify_auth(first, second=None):
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(), urllib.request.HTTPCookieProcessor(jar))
    status, _, body = request(first, "GET", "/api/v1/auth/csrf", opener=opener)
    assert status == 200
    token = json.loads(body)["data"]["token"]
    previous = {cookie.name: cookie.value for cookie in jar}
    status, _, _ = request(second or first, "POST", "/api/v1/auth/login",
                           b'{"username":"admin","password":"fixture-process-password"}', token, opener)
    assert status == 200, "跨进程共享 CSRF/登录失败"
    assert {cookie.name: cookie.value for cookie in jar} != previous, "登录未轮换会话"
    status, _, body = request(first, "GET", "/api/v1/auth/me", opener=opener)
    assert status == 200 and json.loads(body)["data"]["username"] == "admin", "登录身份未持久化"
    status, _, body = request(first, "GET", "/api/v1/auth/csrf", opener=opener)
    token = json.loads(body)["data"]["token"]
    status, _, _ = request(second or first, "POST", "/api/v1/auth/logout", b"", token, opener)
    assert status == 200, "注销失败"
    status, _, body = request(first, "GET", "/api/v1/auth/me", opener=opener)
    assert status == 200 and json.loads(body)["data"]["username"] is None, "注销后身份仍可用"


def verify_failed_start(binary, fixture, directory, ca_file):
    migrations = str(ROOT / "backend/src/main/resources/db/migration")
    for variant in ("missing-database", "production", "bad-migrations", "management-conflict"):
        port, management = reserve_port(), reserve_port()
        while management == port:
            management = reserve_port()
        env = child_environment(fixture, "api", port, management, ca_file)
        path = migrations
        with socket.socket() as blocker:
            if variant == "missing-database":
                del env["POSTGRES_HOST"]
            elif variant == "production":
                env["SPRING_PROFILES_ACTIVE"] = "prod"
            elif variant == "bad-migrations":
                path = str(Path(directory, "empty-migrations"))
                Path(path).mkdir(exist_ok=True)
            else:
                blocker.bind(("127.0.0.1", management))
                blocker.listen()
            result = subprocess.run([str(binary), "--foundation", "--migrations", path], cwd=directory, env=env,
                                    capture_output=True, text=True, timeout=15)
            assert result.returncode != 0, "基础启动失败被误报为成功"
            for key in ("POSTGRES_PASSWORD", "AUTH_PASSWORD", "REDIS_SESSION_URI", "REDIS_RATE_LIMIT_URI"):
                assert not env.get(key) or env[key] not in result.stdout + result.stderr, "启动错误泄漏凭据"
            assert_released(port)
        assert_released(management)
    print("PASS 基础启动失败: 缺项/生产/迁移目录/管理端口冲突均拒绝且释放资源", flush=True)


def run(binary, fixture):
    with tempfile.TemporaryDirectory(prefix="personalos-go-foundation-process-") as directory:
        # 只创建/接触自己的合成文件，验证默认不读取。
        Path(directory, ".env").write_text("SPRING_PROFILES_ACTIVE=prod\n")
        ca_file = Path(directory, "redis-ca.crt")
        ca_file.write_text(fixture.get("GO_TEST_REDIS_CA_PEM", ""))
        with ExitStack() as stack:
            first = stack.enter_context(process(binary, fixture, "combined", directory, ca_file))
            second = stack.enter_context(process(binary, fixture, "api", directory, ca_file))
            verify_role("combined", *first)
            verify_role("api", *second)
            for port, expected in ((first[0], 403), (second[0], 403), (first[0], 429)):
                status, _, _ = request(port, "POST", "/api/v1/settings/model/test", b"")
                assert status == expected, "跨 OS 预算或保护顺序不一致"
            verify_auth(first[0], second[0] if fixture.get("GO_TEST_REDIS") == "disposable" else None)
        print("PASS 基础 combined/api: 双 OS 进程共享预算、认证/CSRF/轮换/注销、双端口与 SIGTERM", flush=True)
        for role in ("index", "review", "delivery"):
            with process(binary, fixture, role, directory, ca_file) as addresses:
                verify_role(role, *addresses)
            print(f"PASS 基础 {role}: 角色隔离、profile池上限、管理探针与 SIGTERM", flush=True)
        verify_failed_start(binary, fixture, directory, ca_file)
