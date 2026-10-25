#!/usr/bin/env python3
"""RAG 检索评测：Recall@1/3/5 · MRR · 文档多样性。

查询集见 queries.py（57 条，分 term / natural / cross 三档难度）。
语料见 fetch-corpus-juejin.py（10 篇掘金文章）。

## 用法

    python3 scripts/eval/retrieval-eval.py            # 全量
    python3 scripts/eval/retrieval-eval.py -v         # 加上每条的 top5 明细

## 为什么要看随机基线

语料只有 10 篇、topK=5：即便检索**完全随机**，Recall@5 的期望也是 5/10 = 50%。
所以「Recall@5 = 90%」并不等于「很好」，它只是随机基线的 1.8 倍。
脚本会把随机基线一起打出来，避免这个数字被误读。

## 为什么不报 Precision@5

每个查询只标注 1~2 篇相关文档，Precision@5 的上限只有 0.2~0.4，数值本身没有意义。
更糟的是「按片段取 topK」时，同一文档的重复片段会把它刷高（出现 4 次 = 0.8），
等于**奖励重复**。2026-10-25 引入文档级去重后它从 48% 掉到 19%，而实际质量是变好的。
替代指标用「文档多样性」= top5 里不同文档数 / 5。

## 关于多标注

有 2 条查询给了 2 篇可接受文档（同主题重叠），命中任一都算对。
只标一篇会把「答对了但命中的是另一篇」判成失败，那不是检索的问题。
"""
import json
import statistics
import sys
import urllib.request
from collections import defaultdict

from queries import AMBIGUOUS, QUERIES

BASE = "http://127.0.0.1:8081/api/v1"
TOP_K = 5
CORPUS_SIZE = 10  # 语料文档数，用于计算随机基线

# 判平阈值：两篇文档得分差小于它时，排序属于噪声。
# 依据见 noise-floor.py 的实测（两次测量：标准差中位数 0.0005~0.0006，
# 极差 P90 0.0024~0.0029、max 0.0048，对应建议阈值 0.0048~0.0058）。
# 取 0.006 覆盖两次测量，宁严不宽。
NOISE_TOLERANCE = 0.006


def search(query, top_k=TOP_K):
    body = json.dumps({"query": query, "topK": top_k}).encode()
    req = urllib.request.Request(f"{BASE}/knowledge/search", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)["data"]["hits"]


def first_hit_rank(titles, acceptable):
    """严格名次：第一个命中的位置（从 1 开始）；未命中返回 None。"""
    for index, title in enumerate(titles):
        if title in acceptable:
            return index + 1
    return None


def effective_rank(hits, acceptable, tolerance=NOISE_TOLERANCE):
    """判平后的名次：只数「显著高于正确答案」的文档。

    嵌入向量逐位不可复现（见 noise-floor.py），所以两篇文档得分差 0.001 时
    谁在前是抽样决定的。把这种并列算作「并列第一」而不是「排第二」，
    才不会让噪声污染指标。

    未命中仍返回 None —— 判平只影响**名次**，不影响**是否命中**。
    """
    best_rank, best_score = None, None
    for index, hit in enumerate(hits):
        if hit["documentTitle"] in acceptable:
            best_rank, best_score = index + 1, hit["score"]
            break
    if best_rank is None:
        return None
    significantly_higher = sum(1 for hit in hits if hit["score"] > best_score + tolerance)
    return significantly_higher + 1


def evaluate(verbose=False):
    rows = []
    for item in QUERIES:
        hits = search(item["q"])
        titles = [hit["documentTitle"] for hit in hits]
        rows.append({
            "query": item["q"],
            "docs": item["docs"],
            "level": item["level"],
            "rank": first_hit_rank(titles, item["docs"]),
            "tied_rank": effective_rank(hits, item["docs"]),
            "titles": titles,
        })
        if verbose:
            mark = f"@{rows[-1]['rank']}" if rows[-1]["rank"] else "未中"
            tied = rows[-1]["tied_rank"]
            if rows[-1]["rank"] and tied != rows[-1]["rank"]:
                mark += f"(判平@{tied})"
            print(f"[{item['level']:7s}] {mark:>12s}  {item['q'][:40]}")
            for position, title in enumerate(titles, 1):
                flag = "✓" if title in item["docs"] else " "
                print(f"        {position}. {flag} {title[:44]}")
            print()
    return rows


def summarize(rows, label="全部"):
    total = len(rows)
    if total == 0:
        print(f"{label:10s} n=  0")
        return
    stats = {}
    for key in ("rank", "tied_rank"):
        stats[key] = {
            "r1": sum(1 for r in rows if r[key] is not None and r[key] <= 1) / total,
            "r3": sum(1 for r in rows if r[key] is not None and r[key] <= 3) / total,
            "r5": sum(1 for r in rows if r[key] is not None and r[key] <= 5) / total,
            "mrr": sum(1.0 / r[key] if r[key] else 0.0 for r in rows) / total,
        }
    diversity = statistics.mean(len(set(r["titles"])) / TOP_K for r in rows)
    misses = sum(1 for r in rows if r["rank"] is None)

    print(f"{label:10s} n={total:3d}  "
          f"R@1={stats['rank']['r1']:6.1%}  R@3={stats['rank']['r3']:6.1%}  "
          f"R@5={stats['rank']['r5']:6.1%}  MRR={stats['rank']['mrr']:.3f}  "
          f"多样性={diversity:6.1%}  未中={misses}")
    print(f"{'':10s}        判平后 R@1={stats['tied_rank']['r1']:6.1%}  "
          f"MRR={stats['tied_rank']['mrr']:.3f}")


def main():
    verbose = "-v" in sys.argv or "--verbose" in sys.argv

    print(f"查询数 {len(QUERIES)}（多标注 {AMBIGUOUS} 条），topK={TOP_K}，语料 {CORPUS_SIZE} 篇")
    print(f"判平阈值 {NOISE_TOLERANCE}（得分差小于它的排序不计为错误，依据 noise-floor.py）\n")
    rows = evaluate(verbose)

    line = "=" * 96
    print(line)
    print("总体")
    print(line)
    summarize(rows)

    baseline = {k: min(k, CORPUS_SIZE) / CORPUS_SIZE for k in (1, 3, 5)}
    print(f"{'随机基线':10s}            "
          f"R@1={baseline[1]:6.1%}  R@3={baseline[3]:6.1%}  R@5={baseline[5]:6.1%}"
          f"   ← 语料仅 {CORPUS_SIZE} 篇、topK={TOP_K}，随机抽也有这个水平")

    print()
    print(line)
    print("按难度（cross 档刻意不提术语，是真正测语义的一档）")
    print(line)
    by_level = defaultdict(list)
    for row in rows:
        by_level[row["level"]].append(row)
    for level in ("term", "natural", "cross"):
        summarize(by_level.get(level, []), level)

    print()
    print(line)
    print("按文档（多标注查询在两篇里各计一次）")
    print(line)
    by_doc = defaultdict(list)
    for row in rows:
        for doc in row["docs"]:
            by_doc[doc].append(row)
    for doc in sorted(by_doc, key=lambda d: -len(by_doc[d])):
        hits = sum(1 for r in by_doc[doc] if r["rank"] is not None)
        top1 = sum(1 for r in by_doc[doc] if r["tied_rank"] == 1)
        bar = "█" * top1 + "▒" * (hits - top1) + "·" * (len(by_doc[doc]) - hits)
        print(f"{hits}/{len(by_doc[doc])} 首{top1} {bar:9s} {doc}")

    print()
    print(line)
    print("未命中明细")
    print(line)
    misses = [r for r in rows if r["rank"] is None]
    if not misses:
        print("(无)")
    for row in misses:
        print(f"[{row['level']}] {row['query']}")
        print(f"      期望：{' / '.join(row['docs'])}")
        print(f"      实际：{' | '.join(t[:26] for t in row['titles'])}")
        print()

    print(line)
    print("非首选明细（严格名次 > 1，含判平后降到 1 的「统计并列」）")
    print(line)
    others = [r for r in rows if r["rank"] is not None and r["rank"] > 1]
    if not others:
        print("(无)")
    for row in others:
        tag = "统计并列" if row["tied_rank"] == 1 else f"仍为第 {row['tied_rank']} 名"
        print(f"[{row['level']}] @{row['rank']} → {tag}：{row['query']}")
        print(f"      期望：{row['docs'][0]}")
        print()


if __name__ == "__main__":
    main()
