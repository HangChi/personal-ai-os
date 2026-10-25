#!/usr/bin/env python3
"""清空知识库并按当前切分策略重新入库（评测用）。

为什么必须重建：切分策略变了，旧向量是用旧切分产生的。
不重建就对比新旧指标，等于把「切分改动」和「检索改动」的效应混在一起。

语料：26 篇掘金文章，主题互不重叠（PostgreSQL / Vue3 / Java 虚拟线程 / Redis /
HTTP×2 / MySQL×2 / Go×2 / Docker×2 / 浏览器渲染×2 / Kafka×2 / Python 并发×2 /
TypeScript×2 / 分布式×2 / 动态规划×2 / Nginx×2）。

同主题 2 篇是刻意的：主题词重叠会互相干扰，测出来的区分度比清一色不同主题更严格。

用法：
  python3 scripts/eval/rebuild-corpus.py
"""
import json
import pathlib
import subprocess
import urllib.request

BASE = "http://127.0.0.1:8081/api/v1"

# (post_id, 标题) —— 顺序即上传顺序，与 queries.py 的标注一一对应
CORPUS = [
    # ---- 第一批（2026-10-25）----
    ("7632034549904310310", "PostgreSQL 索引：从 B-tree 到 BRIN"),
    ("7650134533660508187", "Vue3 响应式原理：从 Proxy 到 EffectScope"),
    ("7616257286160646153", "Java 虚拟线程：让 Java 轻功水上漂"),
    ("7271659132381216787", "Redis 缓存机制"),
    ("7569377216896057395", "HTTP 一篇说清楚"),
    ("7692646947434643502", "MySQL 事务、索引与锁"),
    ("7681202345805119488", "GMP 调度器：Go 并发的心脏"),
    ("7577229187897098275", "Go 并发模式选择指南"),
    ("7687592289394704399", "HTTP 渐进：编码、缓存与断点续传"),
    ("7696709714028281906", "MySQL 学习记：从增删改查到慢查询"),
    # ---- 第二批（2026-10-26，为打破 R@5 天花板）----
    ("7590681158717472808", "Docker 与虚拟机的区别：Namespace 和 Cgroup"),
    ("7693201755920465939", "Docker 深度解析：UnionFS 与核心组件"),
    ("7694832291801677878", "浏览器渲染管道：重排、重绘与合成层"),
    ("7631430251667243023", "浏览器渲染原理：从输入到像素"),
    ("7628488008481751083", "Kafka 实战总结：架构与吞吐优化"),
    ("7623271328049676330", "Kafka 为什么快：PageCache、零拷贝与 Segment"),
    ("7651932218912522280", "Python 并发选型：GIL、多线程、多进程与协程"),
    ("7664457295027814451", "Python 异步陷阱：谁在帮你并发"),
    ("7681235828687175730", "TypeScript 泛型与条件类型"),
    ("7650146543883239487", "TypeScript 类型体操技巧"),
    ("7694101953675460648", "分布式一致性：CAP、Raft 与脑裂"),
    ("7607112994062630939", "系统性地理解分布式系统"),
    ("7591207451778875418", "动态规划五部曲实战"),
    ("7472359618326052901", "动态规划基础与核心思想"),
    ("7672711130193936424", "Nginx 完整配置参考手册"),
    ("7677521340606300170", "Nginx 生产级配置优化"),
]


def request(path, method="GET", body=None):
    req = urllib.request.Request(f"{BASE}{path}", method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
        req.data = json.dumps(body).encode()
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.load(resp)


def delete_all():
    docs = request("/knowledge/documents")["data"]
    for doc in docs:
        req = urllib.request.Request(f"{BASE}/knowledge/documents/{doc['id']}", method="DELETE")
        req.add_header("Idempotency-Key", f"rebuild-{doc['id']}")
        try:
            urllib.request.urlopen(req, timeout=60)
            print(f"  删除 {doc['title'][:34]}")
        except Exception as exc:  # noqa: BLE001
            print(f"  删除失败 {doc['title'][:34]}: {exc}")


def upload(path, title):
    result = subprocess.run(
        ["curl", "-s", "--noproxy", "*", "-X", "POST", f"{BASE}/knowledge/documents",
         "-F", f"file=@{path}", "-F", f"title={title}"],
        capture_output=True, text=True, check=True)
    data = json.loads(result.stdout)
    if data.get("code") != 0:
        raise RuntimeError(f"上传失败 {title}: {data}")
    return data["data"]


def main():
    print("1) 清空现有文档")
    delete_all()

    print(f"2) 按当前切分策略重新入库（共 {len(CORPUS)} 篇）")
    total_chunks = 0
    uploaded = 0
    for post_id, title in CORPUS:
        path = pathlib.Path("/tmp") / f"juejin-{post_id}.md"
        if not path.exists():
            print(f"  !! 缺少语料 {path}")
            continue
        doc = upload(path, title)
        total_chunks += doc["chunkCount"]
        uploaded += 1
        print(f"  {doc['title'][:34]:36s} {doc['chunkCount']:3d} 块  {doc['charCount']:6d} 字符")

    print(f"\n完成：{uploaded} 篇，共 {total_chunks} 块")
    if uploaded != len(CORPUS):
        print(f"⚠️ 预期 {len(CORPUS)} 篇，实际 {uploaded} 篇 —— 评测前请补齐语料")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
