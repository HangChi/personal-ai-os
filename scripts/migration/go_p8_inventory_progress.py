#!/usr/bin/env python3
"""Audit remaining source responsibilities and unchanged assets without approving release gates."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "specs/033-go-rewrite"
COMMON = ["specs/033-go-rewrite/p8-inventory-review.md", "specs/033-go-rewrite/p8-completion.md",
          "specs/033-go-rewrite/validation.md"]

# Each group points to implementation plus executable evidence; original paths below are explicit.
PORTS = {
    "bootstrap": (["backend-go/cmd/personalos/main.go", "backend-go/internal/app/foundation.go", "backend-go/internal/app/foundation_postgres_integration_test.go"], "显式Foundation/CLI替代Spring扫描，五角色资源构建/失败清理/停止。"),
    "context": (["backend-go/internal/platform/config/module_binding.go", "backend-go/internal/business/context/budget.go", "backend-go/internal/business/context/budget_test.go"], "类型化摘要/保护/自动记忆默认值与校验，预算/窗口回落。"),
    "window": (["backend-go/internal/business/model/write.go", "backend-go/internal/business/chat/module.go", "backend-go/internal/business/model/write_integration_test.go"], "按当前生效档案查CHAT窗口≥1024，异常/nil回落；默认模型查询显式注入Budget。"),
    "auth": (["backend-go/internal/platform/auth/service.go", "backend-go/internal/platform/auth/http.go", "backend-go/internal/platform/auth/service_test.go", "backend-go/internal/platform/auth/http_test.go"], "原默认关闭、BCrypt/失败锁定、会话/CSRF/认证保护顺序与错误信封。"),
    "monitor": (["backend-go/internal/app/management.go", "backend-go/internal/app/actuator.go", "backend-go/internal/app/actuator_metrics_integration_test.go", "backend-go/internal/app/foundation_postgres_integration_test.go"], "独立管理端口仅公开GET health/prometheus，环境/应用拒绝，真实采集。"),
    "session": (["backend-go/internal/platform/auth/redis.go", "backend-go/internal/platform/config/session.go", "backend-go/internal/platform/auth/redis_integration_test.go", "backend-go/internal/app/foundation_redis_integration_test.go"], "显式专用Redis/TLS/ACL、30分钟SESSION/Lax/HttpOnly；跨OS共享/旋转/注销/故障恢复。"),
    "executor": (["backend-go/internal/platform/taskexec/executor.go", "backend-go/internal/platform/taskexec/executor_test.go", "backend-go/internal/app/foundation_async_integration_test.go"], "1/2工作单元+32有界队列、满丢弃、仅复制requestId；context替代线程本地MDC/Reactor全局钩子，无跨任务污染。"),
    "dotenv": (["backend-go/internal/platform/config/dotenv.go", "backend-go/internal/platform/config/dotenv_test.go", "backend-go/internal/platform/config/mcp_binding_test.go"], "显式加载顺序、测试/禁用不读本地秘密、原MCP绑定与空覆盖。"),
    "security": (["backend-go/internal/platform/config/security.go", "backend-go/internal/platform/config/security_test.go", "specs/033-go-rewrite/p1-foundation-contract.md"], "原生产安全校验逐项对照；额外prod拒绝/readyz503是未解除发布守卫，非生产就绪。"),
    "rate": (["backend-go/internal/platform/ratelimit/local.go", "backend-go/internal/platform/ratelimit/shared.go", "backend-go/internal/platform/http/rate_limit.go", "backend-go/internal/platform/ratelimit/local_test.go", "backend-go/internal/platform/http/rate_limit_test.go"], "容量/周期正值、平滑复充/时钟回退/并发、按模型路径限流、开关/错误不触下游。"),
    "redisRate": (["backend-go/internal/platform/ratelimit/redis.go", "backend-go/internal/platform/ratelimit/redis_integration_test.go"], "专用Redis独立开关、150ms、TIME/Lua共享预算、不回退、两进程/TTL/关闭。"),
    "coordination": (["backend-go/internal/platform/coordination/lease.go", "backend-go/internal/platform/coordination/postgres_integration_test.go", "backend-go/internal/platform/ratelimit/postgres_integration_test.go"], "数据库时钟共享预算与租约/fencing；停止续约并释放本实例持有资源，失败自然到期。"),
    "config": (["backend-go/internal/platform/config/binding.go", "backend-go/internal/platform/config/binding_test.go", "specs/033-go-rewrite/config-disposition.md"], "原record绑定/默认值/来源及78直接调用逐项处置，业务接线按各阶段实现。"),
    "confirmation": (["backend-go/internal/business/confirmation/confirmation.go", "backend-go/internal/business/confirmation/postgres.go", "backend-go/internal/business/confirmation/p8_transaction_integration_test.go", "backend-go/internal/business/confirmation/p8_lifecycle_integration_test.go", "backend-go/internal/business/task/confirmation_http_test.go"], "动作/状态/DTO/一次令牌/独立事务CAS、错误/取消/过期、HTTP绑定前无副作用，原八事务声明。"),
    "extract": (["backend-go/internal/business/chat/attachments.go", "backend-go/internal/business/chat/pdf.go", "backend-go/internal/business/chat/p8_extract_test.go", "backend-go/internal/business/chat/attachments_test.go"], "文本/扩展名/PDF真实Poppler、坏/空不提取、8000UTF-16上下文截断。"),
    "errors": (["backend-go/internal/platform/http/errors.go", "backend-go/internal/platform/http/errors_test.go", "backend-go/internal/app/foundation_http_test.go"], "MVC异常/错误入口由原生错误边界统一承担，36真实Java响应fixture、日志脱敏/断连/已提交流。"),
    "idempotency": (["backend-go/internal/business/idempotency/idempotency.go", "backend-go/internal/business/idempotency/postgres.go", "backend-go/internal/business/idempotency/postgres_integration_test.go", "backend-go/internal/business/agent/audit.go", "backend-go/internal/app/foundation_p8_integration_test.go"], "来源/结果/重放/持久占位/审计分页前过滤；严格截止批删，R-053保护未知TOOL IN_PROGRESS。"),
    "json": (["backend-go/internal/platform/api/json_encoding.go", "backend-go/internal/platform/http/json.go", "backend-go/internal/business/idempotency/idempotency.go", "backend-go/internal/business/task/patch_http_test.go", "backend-go/internal/app/foundation_task_integration_test.go"], "稳定JSON/UTF标量与字段顺序、原Java摘要/回放；不模拟Jackson容器注入。"),
    "texts": (["backend-go/internal/platform/text/text.go", "backend-go/internal/platform/text/text_test.go", "backend-go/internal/business/task/model.go", "backend-go/internal/business/calendar/p8_text_test.go"], "Java strip/首尾空白/UTF-16/精确错误，任务与日程service真实接线。"),
    "requestID": (["backend-go/internal/platform/http/request_id.go", "backend-go/internal/platform/http/request_id_test.go", "backend-go/internal/platform/observability/logging_test.go"], "64字符安全ID或12hex生成，context与响应一致、日志/异步传播隔离。"),
    "worker": (["backend-go/internal/app/foundation_http.go", "backend-go/internal/app/foundation_http_test.go", "backend-go/internal/app/foundation_postgres_integration_test.go"], "三个Worker业务监听在依赖之前空404，API/combined与独立管理监听按角色保留。"),
    "task": (["backend-go/internal/business/task/model.go", "backend-go/internal/business/task/http.go", "backend-go/internal/business/task/p8_contract_integration_test.go", "backend-go/internal/app/foundation_p8_task_integration_test.go", "backend-go/internal/business/task/p8_batch_integration_test.go"], "原DTO/枚举/省略字段/删除ack/分页/统计，真实HTTP与数据库、previousStatus与标签批取。"),
    "taskConfirmation": (["backend-go/internal/business/task/confirmation.go", "backend-go/internal/business/task/p8_contract_integration_test.go"], "服务端构造删除/截止/优先级载荷，确认前无写，确认后真实值与计数。"),
    "package": (["backend-go/docs/architecture.md", "backend-go/go.mod"], "原package-info仅描述领域边界；Go internal与模块依赖维持职责，无运行钩子需要模拟。"),
    "testUpstream": (["backend-go/internal/app/foundation_p6_integration_test.go", "backend-go/internal/app/foundation_p4_integration_test.go", "specs/033-go-rewrite/p6-test-mapping.md"], "测试专用确定向量/脚本模型由Go httptest端点承担；只为合成fixture，不是生产模型质量证明。"),
    "testHTTP": (["backend-go/internal/app/foundation_http_oracle_test.go", "backend-go/internal/app/foundation_task_integration_test.go"], "测试专用HTTP客户端用net/http+httptest，真实Servlet对照和错误/JSON读取在测试边界。"),
    "instants": (["scripts/migration/run_go_cutover_tests.py", "scripts/migration/test_go_cutover.py"], "原测试辅助按PG微秒舍入比较，包含nanosecond/五位/跨秒/null/一毫秒差异；不新增生产时间API。"),
    "provider": (["backend-go/internal/business/chat/module.go", "backend-go/internal/business/mcp/registry_test.go"], "Spring测试ObjectProvider由显式Go依赖/函数与空/禁用fixture替代，不复制容器接口。"),
}

SOURCE_PORTS = {
    "PersonalOsApplication": "bootstrap",
    "common/ai/ContextProperties": "context",
    "common/ai/config/ContextWindowRegistrar": "window",
    "common/auth/AuthProperties": "auth",
    "common/auth/MonitoringSecurityConfig": "monitor",
    "common/auth/SecurityConfig": "auth",
    "common/auth/SharedSessionConfig": "session",
    "common/config/AppProperties": "config",
    "common/config/ContextTaskConfig": "executor",
    "common/config/DotEnvEnvironmentPostProcessor": "dotenv",
    "common/config/ProductionSecurityValidator": "security",
    "common/config/RateLimitProperties": "rate",
    "common/confirmation/ConfirmationAction": "confirmation",
    "common/confirmation/ConfirmationActionHandler": "confirmation",
    "common/confirmation/ConfirmationDto": "confirmation",
    "common/confirmation/ConfirmationEntity": "confirmation",
    "common/confirmation/ConfirmationExecution": "confirmation",
    "common/confirmation/ConfirmationRepository": "confirmation",
    "common/confirmation/ConfirmationService": "confirmation",
    "common/confirmation/ConfirmationStatus": "confirmation",
    "common/confirmation/ConfirmationTokenRequest": "confirmation",
    "common/document/PlainTextExtractor": "extract",
    "common/exception/ApiErrorController": "errors",
    "common/exception/GlobalExceptionHandler": "errors",
    "common/idempotency/IdempotencyOutcome": "idempotency",
    "common/idempotency/IdempotencyRequest": "idempotency",
    "common/idempotency/IdempotentResult": "idempotency",
    "common/idempotency/OperationRecordRepository": "idempotency",
    "common/idempotency/OperationSource": "idempotency",
    "common/json/JsonCodec": "json",
    "common/package-info": "package",
    "common/text/Texts": "texts",
    "common/web/DatabaseCoordination": "coordination",
    "common/web/MdcPropagatingExecutor": "executor",
    "common/web/MdcSchedulerHook": "executor",
    "common/web/RateLimitFilter": "rate",
    "common/web/RedisRateBucket": "redisRate",
    "common/web/RequestIdFilter": "requestID",
    "common/web/TokenBucketRateLimiter": "rate",
    "common/web/WorkerIsolationFilter": "worker",
    "task/confirmation/TaskConfirmationPayloads": "taskConfirmation",
    "task/dto/CreateTaskRequest": "task",
    "task/dto/DeletionAck": "task",
    "task/dto/TaskDto": "task",
    "task/dto/TaskQuery": "task",
    "task/dto/TaskStatsDto": "task",
    "task/dto/UpdateTaskRequest": "task",
    "task/entity/TaskPriority": "task",
    "task/entity/TaskStatus": "task",
    "task/package-info": "package",
}
SUPPORT_PORTS = {
    "common/web/MdcPropagatingExecutorTest": "executor",
    "common/web/MdcSchedulerHookTest": "executor",
    "support/DeterministicEmbeddingModel": "testUpstream",
    "support/HttpTestClient": "testHTTP",
    "support/Instants": "instants",
    "support/ScriptedToolCallingChatModel": "testUpstream",
    "support/StaticObjectProvider": "provider",
}
PRODUCTION = {"compose.production.yml", "deploy/Caddyfile", "backend/src/main/resources/application-prod.yml"}
PUSH = {"frontend/public/sw.js", "frontend/src/notifications/web-push.ts", "frontend/test/component/web-push.test.ts", "frontend/test/service-worker.test.mjs"}
OPERATIONS = {
    "compose.integrations.yml": "compose", "compose.local-web.yml": "compose", "compose.local.yml": "local",
    "compose.monitoring.local.yml": "monitor", "compose.monitoring.yml": "monitor", "compose.yml": "compose",
    "deploy/Caddyfile.local": "compose", "deploy/monitoring/prometheus.local.yml": "monitor",
    "deploy/monitoring/prometheus.yml": "monitor", "deploy/monitoring/worker-targets/.gitkeep": "monitor",
    "scripts/dev/backup-db.sh": "backup", "scripts/dev/backup.py": "backup", "scripts/dev/test_backup.py": "backup",
    "scripts/dev/generate-push-keys.py": "pushKeys", "scripts/dev/local-runtime.py": "local",
    "scripts/dev/local-stack.sh": "local", "scripts/dev/local-workers.py": "local",
    "scripts/dev/mock-openai-server.py": "fixture", "scripts/dev/seed-demo-data.py": "manualSeed",
    "scripts/dev/ui-audit.mjs": "browser", "scripts/dev/verify-frontend.js": "browser",
    "scripts/e2e/e2e_suite.py": "e2e", ".github/workflows/ci.yml": "ci",
}
ASSETS = {
    "compose": ("implemented", ["backend-go/deploy/compose.yml", "backend-go/deploy/compose.distributed.yml", "scripts/migration/run_go_compose_tests.py"], "保留原Java资产，独立Go combined/distributed/Flyway/Caddy新项目实测，不替换现用配置。"),
    "local": ("implemented", ["scripts/migration/go_runtime.py", "scripts/migration/run_go_local_tests.py"], "独立Go私有状态/五角色/身份核对/停止/discovery实测；不读取原PID或.env。"),
    "monitor": ("implemented", ["scripts/migration/go_monitoring_assets.py", "scripts/migration/run_go_compose_tests.py", "scripts/migration/run_go_local_tests.py", "backend-go/deploy/compose.alerting.yml", "scripts/migration/p8_alert_fixture.py", "specs/033-go-rewrite/p8-alerting.json", "specs/033-go-rewrite/p8-alerting-distributed.json"], "独立Go指标/面板/六告警、distributed与local发现真实采集；combined与distributed实际2m停机触发Alertmanager、本地503重试与同fingerprint恢复通知通过，真实外部渠道最终送达非此证明。"),
    "backup": ("implemented", ["scripts/migration/go_backup.py", "scripts/migration/run_go_cutover_tests.py", "specs/033-go-rewrite/p8-cutover.json"], "复用原tar/SHA/custom dump，合成Java→Go→Java隔离演练；真实用户备份门禁另列。"),
    "pushKeys": ("implemented", ["scripts/migration/run_go_local_tests.py"], "复用原生成器，仅临时目录0600/拒绝覆盖实测；不等于真实浏览器送达。"),
    "fixture": ("implemented", ["scripts/migration/p8_model_fixture.py", "scripts/migration/run_go_e2e_tests.py", "scripts/migration/run_go_cutover_tests.py"], "原模拟上游保留，Go隔离runner明确合成输入/模型；未连接用户模型。"),
    "manualSeed": ("implemented", ["scripts/migration/run_go_demo_seed_tests.py", "scripts/migration/test_go_demo_seed.py", "specs/033-go-rewrite/p8-demo-seed.json", "backend-go/docs/development.md"], "复用原七组配方，仅新随机TLS容器/空库/独立Go；认证CSRF、实际API/SQL回读、向量/状态/31天用量/审计及退出连接归零通过。禁止原main/reset，不操作现有库。"),
    "browser": ("implemented", ["specs/033-go-rewrite/p8-e2e/browser-go.json", "specs/033-go-rewrite/p8-e2e/README.md"], "保留原辅助工具；原前端全检查/真实Chrome10页三宽度与流程报告，不改前端源码。"),
    "e2e": ("in_progress", ["scripts/migration/run_go_e2e_tests.py", "scripts/migration/test_go_e2e_isolation.py", "specs/033-go-rewrite/p8-e2e/README.md"], "原断言保留：两端完整84项同为81通过/1预算失败/1原case跳过/1工具超限观察；失败预算finally恢复，计划依真实回执推进，不能标全绿。"),
    "ci": ("implemented", [".github/workflows/go.yml", "backend-go/docs/development.md"], "原CI冻结，独立Go候选工作流及本地命令验证；未push/运行远端CI。"),
}


def source_port(entry):
    path = entry.get("path", "")
    if "/com/personalos/" not in path or not path.endswith(".java"):
        return None
    name = path.split("/com/personalos/", 1)[1][:-5]
    table = SUPPORT_PORTS if "/src/test/" in path else SOURCE_PORTS
    return table.get(name)


def disposition(entry, old):
    path, category = entry["path"], entry.get("category")
    port = source_port(entry)
    if port:
        evidence, note = PORTS[port]
        return "implemented", "explicit-go-responsibility", evidence, note
    if old.get("status") in {"implemented", "verified"} and not old.get("note", "").startswith("P8职责处置："):
        return old["status"], "prior-stage-evidence", old.get("evidence", []), old.get("note", "")
    if category in {"java_source", "java_test"}:
        raise ValueError("Unreviewed Java responsibility: " + path)
    if path in PRODUCTION:
        return "in_progress", "production-gate-open", ["backend-go/deploy/compose.prod.yml", "backend-go/deploy/Caddyfile.prod", "scripts/migration/p8_https_fixture.py", "specs/033-go-rewrite/p8-https.json"], "新随机项目私有CA验证可信HTTPS/拒绝未知CA与错误域名、安全Cookie/跳转端口、自动续期/重启保留；不等于公网ACME/生产批准，prod拒绝与readyz503保持。"
    if path in PUSH:
        return "in_progress", "real-push-unreceived", ["specs/033-go-rewrite/p8-e2e/push-go.json", "specs/033-go-rewrite/p8-e2e/push-go-gcm.json", "specs/033-go-rewrite/p8-e2e/push-go-headed.json", "specs/033-go-rewrite/p8-e2e/push-go-headed-normalized.json", "specs/033-go-rewrite/p8-e2e/README.md"], "原源码/单测保持；headless曾订阅超时，有界面新profile两轮订阅/控制/FCM接受但15秒无真实push；GCM无接收/解密记录且连接OTHER，不推断根因，synthetic控制不替代真实送达。"
    if path in OPERATIONS:
        status, evidence, note = ASSETS[OPERATIONS[path]]
        return status, "explicit-asset-adaptation", evidence, note
    if path.startswith("scripts/eval/"):
        public_evidence = ["scripts/migration/fetch_go_juejin_corpus.py",
                           "scripts/migration/test_go_juejin_corpus.py",
                           "scripts/migration/run_go_eval_tests.py",
                           "specs/033-go-rewrite/p8-eval-juejin-20261104-212152.json",
                           "scripts/migration/run_go_real_eval_tests.py",
                           "scripts/migration/java_embedding_content.py",
                           "specs/033-go-rewrite/p8-eval-real-20261104-222729.json",
                           "specs/033-go-rewrite/p8-real-model-review.json"]
        if path == "scripts/eval/fetch-corpus-juejin.py":
            return "in_progress", "retained-live-evaluation-open", public_evidence, "原提取器固定SHA复用，用户授权后独立采集器按原清单取得16篇公开正文并记录来源/时间/哈希，第17篇无有效正文即停。全文仅本地，未操作原fetch/main；已用获准真实模型跑两端84查询子集，仍缺10篇、历史快照/标注认证及完整质量验收。"
        if path in {"scripts/eval/queries.py", "scripts/eval/rebuild-corpus.py", "scripts/eval/retrieval-eval.py", "scripts/eval/noise-floor.py"}:
            return "in_progress", "retained-live-evaluation-open", ["scripts/migration/go_eval_adapter.py", "scripts/migration/test_go_eval_adapter.py", "specs/033-go-rewrite/p8-eval-tooling.json", "specs/033-go-rewrite/p8-upload-binding.json"] + public_evidence, "原26标题/129查询及评估算法固定SHA不改；16篇公开正文在Java/Go各新TLS库真实模型完整执行84查询、两次问答/持久分块引用和50次噪声请求，142条1024维向量的预期输入SHA按真实Java格式核验。Go嵌入元数据遗漏已修复；两端R@5=100%，严格MRR Go0.9375/Java0.94345，唯一名次差在原0.007判平内，判平MRR同0.94643。仍缺10篇/45查询、历史认证/人工事实与多阶段RAG完整验收，严格退化不被PASS掩盖，五门禁保持。"
        return "in_progress", "retained-live-evaluation-open", ["scripts/migration/compare_go_p6.py", "specs/033-go-rewrite/p6-eval-results.json"] + public_evidence, "原在线评估工具保留；获准真实模型已完成16篇/84查询双端检索与传统知识问答引用子集，严格与原判平指标独立记录，不替代Agent/多阶段RAG/人工事实正确性验收。缺10篇/历史26篇认证及完整在线质量，继续开放。"
    if path.startswith("scripts/e2e/reports/"):
        return "implemented", "retained-historical-report", ["specs/033-go-rewrite/p8-e2e/README.md"], "原Java历史报告按哈希保留，只作历史资料，不冒充本次Go结果。"
    if category == "sql_migration":
        return "implemented", "retained-original-sql", ["backend-go/internal/platform/migration/postgres_integration_test.go", "scripts/migration/run_go_postgres_tests.py", "specs/033-go-rewrite/p8-cutover.json"], "原42 SQL逐文件SHA不变，真实Flyway竞争/validate/零重复及合成升级/回退；Go只验证，不生成历史。"
    if category in {"documentation", "specification"}:
        return "implemented", "retained-specification-or-history", ["backend-go/docs/architecture.md", "backend-go/docs/development.md", "specs/033-go-rewrite/feature-parity.md"], "原业务规格/仓库规则/历史文档保留，不替换为Go说明；当前Go操作与已知差异见新docs/迁移记录，无运行时需要移植。"
    if category in {"frontend_source", "frontend_test", "frontend_build"}:
        return "implemented", "retained-frontend", ["specs/033-go-rewrite/p8-e2e/browser-go.json", "specs/033-go-rewrite/p8-e2e/README.md", "specs/033-go-rewrite/validation.md"], "原前端逐文件SHA不变；lint/format/type-check/1151逻辑/串行195组件/build及真实Chrome报告。预算同基线失败/原跳过/真实Push另列，不据此宣布全部E2E通过。"
    if category in {"java_resource", "java_test_resource"}:
        return "implemented", "explicit-config-adaptation", ["backend-go/internal/platform/config/baseline.json", "backend-go/internal/platform/config/properties_test.go", "backend-go/internal/platform/config/security_test.go", "specs/033-go-rewrite/config-disposition.md"], "原配置资源保留；Go对照ConfigData/Binder/default/profile及显式启动，Spring factories由原生dotenv入口替代。生产发布另设门禁。"
    if category == "java_build":
        return "implemented", "parallel-go-build", ["backend-go/go.mod", "backend-go/Dockerfile", ".github/workflows/go.yml"], "原Maven/Java构建冻结且全量verify通过，Go固定模块/镜像/候选CI并行；无删除或替换Java。"
    if category == "operations_and_repository" and (path.startswith(".specify/") or path in {".editorconfig", ".env.example", ".gitignore", "LICENSE"}):
        return "implemented", "retained-repository-metadata", ["scripts/migration/go_inventory.py", "backend-go/docs/development.md"], "原仓库元数据/工作流模板/示例按固定基线保留；示例不是本机.env，不载入实际凭据。"
    raise ValueError("Unreviewed asset responsibility: " + path)


def apply(inventory, before):
    result = copy.deepcopy(before)
    rows = []
    for entry in inventory["files"]:
        path = ROOT / entry["path"]
        # This only hashes fixed tracked baseline files, never discovers local configuration.
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            raise ValueError("Baseline file drift: " + entry["path"])
        old = result["items"].get(entry["id"], {})
        status, kind, evidence, note = disposition(entry, old)
        for proof in evidence:
            if not (ROOT / proof).is_file():
                raise ValueError("Missing inventory evidence: " + proof)
        if kind != "prior-stage-evidence":
            status = "verified" if old.get("status") == "verified" else status
            evidence = list(dict.fromkeys(old.get("evidence", []) + evidence + COMMON))
            result["items"][entry["id"]] = dict(old, status=status, evidence=evidence, note="P8职责处置：" + note)
        rows.append(dict(id=entry["id"], path=entry["path"], sha256=entry["sha256"], disposition=kind,
                         status=result["items"][entry["id"]]["status"]))
    for group in ("http_routes", "runtime_hooks"):
        for entry in inventory[group]:
            old = result["items"].get(entry["id"], {})
            if old.get("status") in {"implemented", "verified"}:
                continue
            port = source_port(entry)
            if not port:
                # HealthController already had a file-level P1 implementation; this last route was unrecorded.
                if entry.get("route") == "/api/v1/health":
                    evidence = ["backend-go/internal/platform/health/http.go", "backend-go/internal/platform/health/http_test.go", "backend-go/internal/app/foundation_http_oracle_test.go"]
                    note = "公开业务health统一信封与真实Servlet对照，管理端口职责独立。"
                else:
                    raise ValueError("Unreviewed runtime responsibility: " + entry["id"])
            else:
                evidence, note = PORTS[port]
            for proof in evidence:
                if not (ROOT / proof).is_file():
                    raise ValueError("Missing runtime evidence: " + proof)
            result["items"][entry["id"]] = dict(old, status="implemented",
                evidence=list(dict.fromkeys(old.get("evidence", []) + evidence + COMMON)),
                note="P8显式运行职责：" + entry.get("annotation", entry.get("route", "")) + "；" + note)
    audit = dict(baseline_commit=inventory["baseline_commit"], scope="Every baseline file, original hash and reviewed disposition; implemented is not release verification.",
                 release_gates_changed=False, files=rows)
    return result, audit


def update(write=False):
    inventory = json.loads((SPEC / "inventory.json").read_text())
    before = json.loads((SPEC / "progress.json").read_text())
    after, audit = apply(inventory, before)
    outputs = {SPEC / "progress.json": after, SPEC / "p8-inventory-disposition.json": audit}
    for path, value in outputs.items():
        content = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
        if write:
            path.write_text(content)
        elif not path.is_file() or path.read_text() != content:
            raise ValueError("Inventory disposition differs; review then run --write: " + path.name)
    print("P8 inventory: all original files hashed and disposed; real Push/production/live evaluation remain open; no release approval")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    update(parser.parse_args().write)
