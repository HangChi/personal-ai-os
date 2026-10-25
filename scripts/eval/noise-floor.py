#!/usr/bin/env python3
"""测量检索的**噪声底**：同一查询文本重复请求，得分漂移多大。

## 为什么需要这个测量

2026-10-26 发现：同一个查询文本连续请求，返回的**得分数值本身会变**。
说明嵌入向量不是逐位可复现的（模型侧数值非确定性，不是我们的代码问题）。

后果有两个，都很实际：

1. **评测不可完全复现**：两次跑同一份查询集，R@1 可能差一两条。
2. **近并列的排序是噪声**：若两篇文档得分只差 0.001，谁在前取决于这一次的抽样，
   谈不上「排序错误」。

不量化这个噪声，就会把噪声当成改进/退化 —— 例如以为某个改动让 MRR 从 0.905
升到 0.912，而它其实在噪声范围内。

## 用法

    python3 scripts/eval/noise-floor.py             # 默认 10 条查询 × 5 次
    python3 scripts/eval/noise-floor.py 20 8        # 20 条 × 8 次

输出的「建议判平阈值」就是 `retrieval-eval.py` 里 `NOISE_TOLERANCE` 的依据。
"""
import json
import statistics
import sys
import urllib.request
from collections import defaultdict

BASE = "http://127.0.0.1:8081/api/v1"

# 覆盖不同难度与文档长度，避免只测某一篇
QUERIES = [
    "BRIN 索引适合什么场景",
    "索引在什么情况下会失效",
    "ref 和 reactive 该用哪个",
    "虚拟线程有哪些坑",
    "缓存雪崩是什么原因造成的",
    "强缓存和协商缓存应该怎么配合使用",
    "MVCC 是怎么减少读写之间的阻塞的",
    "goroutine 为什么比操作系统线程轻量",
    "断点续传靠协议里的什么机制实现",
    "InnoDB 默认的事务隔离级别是什么",
    "日志表按时间顺序不断写入、想用很小的索引先筛掉一批数据块，该建哪种索引",
    "库存扣减怎么防止超卖",
]


def search(query, top_k=5):
    body = json.dumps({"query": query, "topK": top_k}).encode()
    req = urllib.request.Request(f"{BASE}/knowledge/search", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)["data"]["hits"]


def main():
    query_count = int(sys.argv[1]) if len(sys.argv) > 1 else 10
    runs = int(sys.argv[2]) if len(sys.argv) > 2 else 5
    queries = QUERIES[:query_count]

    stds, spreads = [], []
    for query in queries:
        samples = defaultdict(list)
        for _ in range(runs):
            for hit in search(query):
                samples[hit["documentTitle"]].append(hit["score"])
        for scores in samples.values():
            if len(scores) >= runs:
                stds.append(statistics.stdev(scores))
                spreads.append(max(scores) - min(scores))

    if not stds:
        print("没有采集到可用的重复样本，检查后端是否在运行")
        return 1

    stds.sort()
    spreads.sort()
    p90_std = stds[int(len(stds) * 0.9)]
    p90_spread = spreads[int(len(spreads) * 0.9)]

    print(f"样本：{len(stds)} 个（文档×查询）组合，每组合 {runs} 次")
    print(f"标准差  中位数={statistics.median(stds):.5f}  P90={p90_std:.5f}  max={max(stds):.5f}")
    print(f"极差    中位数={statistics.median(spreads):.5f}  P90={p90_spread:.5f}  max={max(spreads):.5f}")
    print()
    print(f"建议判平阈值：{p90_spread * 2:.4f}（P90 极差的 2 倍，留一倍余量）")
    print(f"含义：两篇文档得分差小于该值时，谁排前面属于噪声，不应判为排序错误。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
