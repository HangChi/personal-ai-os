#!/usr/bin/env python3
"""RAG 检索评测：Recall@5 / MRR / Accuracy@1 / 文档多样性@5。

相关文档由**人工标注**（每篇掘金文章的主题是已知的，查询按主题归属）。
查询刻意用自然问法、不抄标题 —— 抄标题只能测字面匹配，测不出语义检索。

## 关于 Precision@5

**不用它。** 每个查询只标注了 1 篇相关文档，所以 Precision@5 的理论上限是 0.2，
数值本身没有意义。更要紧的是：在「按片段取 topK」的实现下，同一文档的重复片段
会把 Precision 刷高（出现 4 次 = 0.8），等于**奖励重复**。2026-10-25 引入
文档级去重后，这个指标从 48% 掉到 19%，而实际检索质量是变好的 —— 正是这个缺陷的证明。

替代指标用「文档多样性@5」= top5 里不同文档数 / 5。它才真正衡量
「这个位置是不是被同一篇文档的相邻片段占掉了」。
"""
import json
import urllib.request

BASE = "http://127.0.0.1:8081/api/v1"
TOP_K = 5

# (查询, 标注的相关文档标题)
QUERIES = [
    # —— PostgreSQL 索引 ——
    ("索引在什么情况下会失效", "PostgreSQL 索引：从 B-tree 到 BRIN"),
    ("BRIN 索引适合什么样的数据", "PostgreSQL 索引：从 B-tree 到 BRIN"),
    # —— Vue3 响应式 ——
    ("Vue3 为什么放弃 Object.defineProperty", "Vue3 响应式原理：从 Proxy 到 EffectScope"),
    ("computed 是怎么做缓存的", "Vue3 响应式原理：从 Proxy 到 EffectScope"),
    # —— Java 虚拟线程 ——
    ("虚拟线程有哪些坑", "Java 虚拟线程：让 Java 轻功水上漂"),
    ("在 synchronized 块里阻塞会发生什么", "Java 虚拟线程：让 Java 轻功水上漂"),
    # —— Redis ——
    ("缓存雪崩是什么原因造成的", "Redis 缓存机制"),
    ("Redis 为什么比直接查数据库快", "Redis 缓存机制"),
    # —— HTTP 一篇说清楚 ——
    ("强缓存和协商缓存应该怎么配合使用", "HTTP 一篇说清楚"),
    ("HTTP/3 相比 HTTP/2 解决了什么问题", "HTTP 一篇说清楚"),
    # —— MySQL 事务、索引与锁 ——
    ("MVCC 是怎么减少读写之间的阻塞的", "MySQL 事务、索引与锁"),
    ("联合索引设计要注意什么", "MySQL 事务、索引与锁"),
    # —— Go GMP ——
    ("goroutine 为什么比操作系统线程轻量", "GMP 调度器：Go 并发的心脏"),
    ("Work Stealing 是怎么回事", "GMP 调度器：Go 并发的心脏"),
    # —— Go 并发模式 ——
    ("并发度失控会引发什么问题", "Go 并发模式选择指南"),
    ("I/O 密集型任务适合哪种并发模式", "Go 并发模式选择指南"),
    # —— HTTP 渐进 ——
    ("gzip 对哪类资源几乎没有收益", "HTTP 渐进：编码、缓存与断点续传"),
    ("ETag 和 Last-Modified 有什么区别", "HTTP 渐进：编码、缓存与断点续传"),
    # —— MySQL 学习记 ——
    ("EXPLAIN 的 type 列有哪些取值", "MySQL 学习记：从增删改查到慢查询"),
    ("InnoDB 默认的事务隔离级别是什么", "MySQL 学习记：从增删改查到慢查询"),
]


def search(query, top_k=TOP_K):
    body = json.dumps({"query": query, "topK": top_k}).encode()
    req = urllib.request.Request(f"{BASE}/knowledge/search", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)["data"]["hits"]


def main():
    hits_at_5 = 0
    reciprocal_ranks = []
    diversities = []
    top1 = 0
    failures = []

    print(f"查询数 {len(QUERIES)}，topK={TOP_K}\n")
    print(f"{'查询':34s} {'命中位置':>6s}  top5 命中文档")
    print("-" * 100)

    for query, relevant in QUERIES:
        hits = search(query)
        titles = [h["documentTitle"] for h in hits]
        rank = next((i + 1 for i, t in enumerate(titles) if t == relevant), None)

        if rank is not None:
            hits_at_5 += 1
            reciprocal_ranks.append(1.0 / rank)
            if rank == 1:
                top1 += 1
        else:
            reciprocal_ranks.append(0.0)
            failures.append((query, relevant, titles))

        # 文档多样性：top5 里不同文档数 / 5，衡量「是否被同一文档的相邻片段占满」
        diversities.append(len(set(titles)) / TOP_K)

        rank_text = str(rank) if rank else "未中"
        top5 = " | ".join(t[:16] for t in titles)
        print(f"{query[:32]:34s} {rank_text:>6s}  {top5}")

    total = len(QUERIES)
    print("-" * 100)
    print(f"Recall@{TOP_K}        = {hits_at_5}/{total} = {hits_at_5 / total:.1%}")
    print(f"MRR              = {sum(reciprocal_ranks) / total:.3f}")
    print(f"Accuracy@1       = {top1}/{total} = {top1 / total:.1%}")
    print(f"文档多样性@{TOP_K}     = {sum(diversities) / total:.1%}")

    if failures:
        print(f"\n未命中 {len(failures)} 条（相关文档不在 top{TOP_K}）：")
        for query, relevant, titles in failures:
            print(f"  · {query}")
            print(f"      期望：{relevant}")
            print(f"      实际：{' | '.join(t[:20] for t in titles)}")


if __name__ == "__main__":
    main()
