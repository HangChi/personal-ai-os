#!/usr/bin/env python3
"""为固定配置索引逐条记录装配阶段，未知来源/动态键/缺证据失败；默认只比较。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

from go_inventory import closing, masked_java

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "specs/033-go-rewrite"

# 逐个审查调用方后显式分配，不通过目录名或@Value数量猜测业务完成。
RULES = {
    "WebPushSettings.java": ("P5", "T505", "Web Push开关/公私钥/subject已接Foundation；真实Chrome订阅并收到解密后的提醒"),
    "ReminderWebhookWorker.java": ("P5", "T506", "Webhook目的地址/签名/本地HTTP开关已接delivery角色；回环验证签名、重试与禁重定向"),
    "ChatAttachmentJanitor.java": ("P4", "T408", "附件清理开关已接Foundation/角色；cron默认本地04:30，关闭不清理；扩展L/W/#显式拒绝"),
    "ChatAttachmentService.java": ("P4", "T408", "持久目录与6MB业务限制已接上传/真实大小/本地与S3；区别于8MB容器限制"),
    "ChatCompletionService.java": ("P4", "T404", "流超时与取消已接真实模型流，部分回复与空输出有落库断言"),
    "S3StorageConfig.java": ("P4", "T408", "S3开关/连接/凭据/命名空间/清理与大小已接官方SDK及一次性MinIO；关闭不创建客户端"),
    "SseHeartbeatScheduler.java": ("P4", "T404", "心跳间隔已接两种业务SSE，默认15s，非正或不足1ms启动拒绝；真实5ms心跳/停止有断言"),
    "RedisCacheConfig.java": ("P3", "T304", "第三专用Redis客户端已接CLI/Foundation；哈希namespace、150ms超时、TLS/最小ACL与故障回退已验收"),
    "ModelConfigService.java": ("P3", "T300", "配置逐字段回落/原始环境来源已接持久化、启动覆盖和运行时工厂"),
    "ModelKeyConverter.java": ("P3", "T301", "原AES-GCM格式及三态密钥写入已接仓储；仅用合成密钥双向Java对照"),
    "SecurityConfig.java": ("P1", "T105", "Foundation安全链已读取canonical Secure/CSRF；保留历史spring/server路径差异"),
    "SharedSessionConfig.java": ("P1", "T105", "独立Redis URI与Secure已装配；SESSION/Lax/HttpOnly/30分钟保持固定策略"),
    "ProductionSecurityValidator.java": ("P1", "T104", "原安全守卫已逐项对照；P8切换门禁另阻止所有prod启动"),
    "RuntimeRole.java": ("P1", "T108", "五角色与profile/双端口/池上限已装配，业务Worker按所属阶段实现"),
    "DataRetentionService.java": ("P8", "T801", "用量/操作记录保留期与清理调度随运维生命周期验收"),
    "ModelConcurrencyGate.java": ("P1", "T107", "共享租约/角色API预留容量已装配，超100明确拒绝"),
    "RedisRateBucket.java": ("P1", "T107", "独立URI/namespace、Lua/TIME/150ms/错误指标已装配"),
    "IngestionReviewWorker.java": ("P6", "T606", "证据超时随review租约与业务调度迁移"),
    "KnowledgeIndexJobs.java": ("P6", "T602", "索引任务开关随持久队列迁移，不启动空Worker"),
    "RabbitJobsConfig.java": ("P6", "T604", "Rabbit连接与durable队列随Outbox/信号迁移"),
    "WorkSignalRelay.java": ("P6", "T604", "队列名随可靠信号重发迁移"),
    "CrossEncoderReranker.java": ("P6", "T605", "reranker端点/凭据/模型/协议随真实协议适配迁移"),
    "RagRetrievalPipeline.java": ("P6", "T605", "RAG开关与总预算/证据预算随真实检索流水线迁移"),
    "SearchConfig.java": ("P6", "T603", "可选OpenSearch端点/index/凭据随真实检索迁移"),
    "RedisCandidateCache.java": ("P6", "T607", "候选缓存namespace与全部动态配置指纹键随generation失效迁移"),
    "VectorStoreInspector.java": ("P6", "T601", "向量表/schema检查随向量仓储迁移；P1只验证原DDL"),
    "McpStatusService.java": ("P7", "T702", "对外MCP开关/名称/版本/端点随协议入口迁移"),
    "MemoryEmbeddingService.java": ("P5", "T501", "模型名已接记忆向量写入、同模型排序、近重复取代和回填"),
    "EmbeddingCacheConfig.java": ("P3", "T304", "默认观测/显式缓存、容量/TTL/模型选项指纹及Redis共享已接真实嵌入适配器"),
    "MonitoringSecurityConfig.java": ("P1", "T108", "local.management.port由独立监听器结构保证；不依据用户Host/转发头授予管理权限"),
    "DotEnvEnvironmentPostProcessor.java": ("P1", "T104", "默认禁读；显式开关/来源优先级/晚于ConfigData已验证"),
}

DYNAMIC_KEYS = {
    "EmbeddingCacheConfig.java": ["spring.ai.model.embedding", "spring.ai.openai.base-url", "spring.ai.openai.embedding.model", "spring.ai.ollama.base-url", "spring.ai.ollama.embedding.model", "app.embedding-cache.revision"],
    "RedisCandidateCache.java": ["spring.ai.model.embedding", "spring.ai.openai.base-url", "spring.ai.openai.embedding.model", "spring.ai.ollama.base-url", "spring.ai.ollama.embedding.model", "app.embedding-cache.revision", "app.knowledge.search.endpoint", "app.knowledge.search.index"],
    "ModelConfigService.java": ["OPENAI_BASE_URL", "OPENAI_API_KEY", "OPENAI_CHAT_MODEL", "OPENAI_EMBEDDING_MODEL"],
}

P1_EVIDENCE = {
    "T104": ["backend-go/internal/platform/config/properties.go", "backend-go/internal/platform/config/security_test.go", "backend-go/internal/platform/config/properties_test.go", "backend-go/cmd/personalos/startup.go"],
    "T105": ["backend-go/internal/platform/auth/http.go", "backend-go/internal/platform/config/session.go", "backend-go/internal/app/foundation.go", "backend-go/internal/app/foundation_redis_integration_test.go"],
    "T107": ["backend-go/internal/platform/ratelimit/redis.go", "backend-go/internal/platform/modelgate/gate.go", "backend-go/internal/app/foundation.go", "backend-go/internal/app/foundation_network_integration_test.go"],
    "T108": ["backend-go/internal/app/management.go", "backend-go/internal/app/serve.go", "scripts/migration/smoke_go_foundation.py"],
}


P3_EVIDENCE = {
 "T300": ["backend-go/internal/business/model/config.go", "backend-go/internal/business/model/write.go", "backend-go/internal/business/model/module.go", "backend-go/internal/business/model/write_integration_test.go"],
 "T301": ["backend-go/internal/business/model/keys.go", "backend-go/internal/business/model/write_integration_test.go", "scripts/migration/java_model_config_fixture.py"],
 "T304": ["backend-go/internal/business/model/embedding.go", "backend-go/internal/business/model/module.go", "backend-go/cmd/personalos/startup.go", "backend-go/internal/business/model/redis_integration_test.go", "specs/033-go-rewrite/p3-completion.md"],
}

P4_EVIDENCE = {
    "T404": ["backend-go/internal/business/chat/module.go", "backend-go/internal/business/chat/module_http.go", "backend-go/internal/business/chat/scheduling.go", "backend-go/internal/business/chat/scheduling_test.go", "backend-go/internal/business/chat/execution_integration_test.go"],
    "T408": ["backend-go/internal/business/chat/module.go", "backend-go/internal/business/chat/attachments.go", "backend-go/internal/business/chat/s3.go", "backend-go/internal/business/chat/scheduling.go", "backend-go/internal/business/chat/s3_integration_test.go", "backend-go/internal/app/foundation_p4_integration_test.go"],
}


P5_EVIDENCE = {
    "T501": ["backend-go/internal/app/foundation_p5.go", "backend-go/internal/business/memory/vector.go", "backend-go/internal/app/foundation_p5_integration_test.go"],
    "T505": ["backend-go/internal/app/foundation_p5.go", "backend-go/internal/business/calendar/push.go", "scripts/migration/chrome_p5_web_push.mjs"],
    "T506": ["backend-go/internal/app/foundation_p5.go", "backend-go/internal/business/calendar/webhook.go", "backend-go/internal/app/foundation_p5_integration_test.go"],
}

def source_dynamic_keys(filename, source):
    """只展开已审查的固定基线形态；必须逐项相等，不能仅验证子集。"""
    mask = masked_java(source)
    if filename in ("EmbeddingCacheConfig.java", "RedisCandidateCache.java"):
        streams = list(re.finditer(r"\bStream\.of\s*\(", mask))
        if len(streams) != 1:
            raise ValueError("动态Stream来源变化，需人工审查")
        opening = mask.index("(", streams[0].start())
        arguments = source[opening + 1:closing(mask, opening) - 1]
        literal = r'"(?:\\.|[^"\\])*"'
        if not re.fullmatch(r"\s*" + literal + r"(?:\s*,\s*" + literal + r")*\s*", arguments):
            raise ValueError("动态Stream不再是纯字面量集合")
        return [json.loads(value) for value in re.findall(literal, arguments)]
    if filename == "ModelConfigService.java":
        constants = {}
        for match in re.finditer(r"\bString\s+(KEY_\w+)\s*=", mask):
            value = re.match(r'\s*("(?:\\.|[^"\\])*")\s*;', source[match.end():])
            if value is None or match[1] in constants:
                raise ValueError("模型来源常量变化，需人工审查")
            constants[match[1]] = json.loads(value[1])
        names = []
        for match in re.finditer(r"\bsourceOf\s*\(", mask):
            opening = mask.index("(", match.start())
            arguments = mask[opening + 1:closing(mask, opening) - 1]
            key = re.search(r",\s*(KEY_\w+)\s*$", arguments)
            if key:
                names.append(key[1])
        if not names or set(names) != set(constants):
            raise ValueError("模型动态来源调用或常量集合不一致")
        return [constants[name] for name in names]
    raise ValueError("动态来源没有显式展开规则")


def generate():
    inventory_path = SPEC / "config-inventory.json"
    inventory = json.loads(inventory_path.read_text())
    rows, used = [], set()
    phase_evidence = {"P1": P1_EVIDENCE, "P3": P3_EVIDENCE, "P4": P4_EVIDENCE, "P5": P5_EVIDENCE}
    for kind in ("value_injections", "property_lookups"):
        for entry in inventory[kind]:
            filename = Path(entry["path"]).name
            if filename not in RULES:
                raise ValueError("配置来源未逐项分配：" + filename)
            used.add(filename)
            phase, task, note = RULES[filename]
            evidence = phase_evidence[phase][task] if phase in phase_evidence else ["specs/033-go-rewrite/tasks.md", entry["path"]]
            for path in evidence:
                if not (ROOT / path).is_file():
                    raise ValueError("配置证据路径不存在：" + path)
            keys = DYNAMIC_KEYS.get(filename, []) if entry.get("dynamic_key") else []
            if entry.get("dynamic_key"):
                if not keys:
                    raise ValueError("动态键集合未审查")
                source = subprocess.run(["git", "show", inventory["baseline_commit"] + ":" + entry["path"]], cwd=ROOT, check=True, capture_output=True, text=True).stdout
                if keys != source_dynamic_keys(filename, source):
                    raise ValueError("动态键集合与原调用方不一致")
            rows.append(dict(kind=kind, **entry, phase=phase, task=task, status="wired" if phase in phase_evidence else "deferred", dynamic_keys=keys, note=note, evidence=evidence))
    if set(RULES) != used or len(rows) != 78:
        raise ValueError("配置规则有遗漏或多余，必须显式审查")
    return dict(schema_version=1, baseline_commit=inventory["baseline_commit"], inventory_sha256=hashlib.sha256(inventory_path.read_bytes()).hexdigest(), items=rows)


def render(data):
    lines = ["# 配置调用方逐项处置（P1/P3/P4/P5收口）", "", "固定Java基线：`" + data["baseline_commit"] + "`。70处@Value与8处getProperty全部列出；wired只表示所列阶段的该调用方已装配，deferred是明确的后续业务任务，不表示已实现。", "",
             "12类record的全部67字段已有真实Binder对照。Auth/RateLimit/App进入P1组合；Agent/Plan/Context/Protection及AI提示/预算已接P4，AI模型配置/协议已接P3，Calendar/Memory已接P5，Knowledge属于P6，MCP属于P7；不创建业务空壳。", "",
             "| 来源与行号 | 原表达式/键 | 阶段/任务 | 状态与处置 |", "| --- | --- | --- | --- |"]
    for row in data["items"]:
        key = row.get("expression") or row.get("literal_key") or "动态：" + ", ".join(row["dynamic_keys"])
        lines.append(f"| `{row['path']}:{row['line']}` | `{key}` | {row['phase']}/{row['task']} | {row['status']}：{row['note']} |")
    lines += ["", "## 附加边界", "", "- datasource/Hikari/Flyway/Servlet/管理/日志的支持与拒绝列表见`p1-foundation-contract.md`；未知扩展在创建连接前拒绝，不静默接受。", "- prod原安全校验已完成；全量迁移切换属于P8，Go基础运行时仍额外拒绝prod，readyz仍503。", "- 原spring.servlet.session.cookie配置路径差异继续保留；实际消费的是server.servlet.session.cookie。", "- 完整机器可读来源、动态键集合与对应阶段证据见同名JSON；台账不改变任何全量迁移条目的verified状态。", "- 复验：`python3 scripts/migration/go_config_disposition.py`，默认比较；仅显式`--write`重建。", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    data = generate()
    for path, content in {SPEC / "config-disposition.json": json.dumps(data, ensure_ascii=False, indent=2)+"\n", SPEC / "config-disposition.md": render(data)}.items():
        if args.write:
            path.write_text(content)
        elif not path.is_file() or path.read_text() != content:
            raise SystemExit("配置处置台账变化，审查后显式--write：" + str(path))
    print("配置处置台账通过：78项，无遗漏/未知动态键")


if __name__ == "__main__":
    main()
