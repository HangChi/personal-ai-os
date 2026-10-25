#!/usr/bin/env python3
"""清空知识库并按当前切分策略重新入库（评测用）。

为什么必须重建：切分策略变了，旧向量是用旧切分产生的。
不重建就对比新旧指标，等于把「切分改动」和「检索改动」的效应混在一起。

用法：
  python3 scripts/eval/rebuild-corpus.py
"""
import json
import pathlib
import subprocess
import urllib.request

BASE = "http://127.0.0.1:8081/api/v1"

# 与 fetch-corpus-juejin.py 中的标题保持一致
CORPUS = [
    ("juejin-7632034549904310310.md", "PostgreSQL 索引：从 B-tree 到 BRIN"),
    ("juejin-7650134533660508187.md", "Vue3 响应式原理：从 Proxy 到 EffectScope"),
    ("juejin-7616257286160646153.md", "Java 虚拟线程：让 Java 轻功水上漂"),
    ("juejin-7271659132381216787.md", "Redis 缓存机制"),
    ("juejin-7569377216896057395.md", "HTTP 一篇说清楚"),
    ("juejin-7692646947434643502.md", "MySQL 事务、索引与锁"),
    ("juejin-7681202345805119488.md", "GMP 调度器：Go 并发的心脏"),
    ("juejin-7577229187897098275.md", "Go 并发模式选择指南"),
    ("juejin-7687592289394704399.md", "HTTP 渐进：编码、缓存与断点续传"),
    ("juejin-7696709714028281906.md", "MySQL 学习记：从增删改查到慢查询"),
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
        # 用 Idempotency-Key 保持一致：删除是写操作
        req = urllib.request.Request(f"{BASE}/knowledge/documents/{doc['id']}", method="DELETE")
        req.add_header("Idempotency-Key", f"rebuild-{doc['id']}")
        try:
            urllib.request.urlopen(req, timeout=60)
            print(f"  删除 {doc['title'][:30]}")
        except Exception as exc:  # noqa: BLE001
            print(f"  删除失败 {doc['title'][:30]}: {exc}")


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

    print("2) 按当前切分策略重新入库")
    total_chunks = 0
    for filename, title in CORPUS:
        path = pathlib.Path("/tmp") / filename
        if not path.exists():
            print(f"  !! 缺少语料 {path}")
            continue
        doc = upload(path, title)
        total_chunks += doc["chunkCount"]
        print(f"  {doc['title'][:30]:32s} {doc['chunkCount']:3d} 块  {doc['charCount']:6d} 字符")

    print(f"\n完成：{len(CORPUS)} 篇，共 {total_chunks} 块")


if __name__ == "__main__":
    main()
