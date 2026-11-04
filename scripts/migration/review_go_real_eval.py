#!/usr/bin/env python3
"""Derive an explicit strict/tied quality assessment without rerunning models or approving release."""
import argparse
import hashlib
import json
from pathlib import Path

from go_eval_adapter import original_modules
from run_go_eval_tests import query_subset
from run_go_real_eval_tests import SPEC, comparison, metrics


def review(path):
    raw = path.read_bytes()
    report = json.loads(raw)
    if (report.get("result") != "PASS" or report.get("synthetic_model") is not False
            or report.get("mode", "retrieval") != "retrieval"
            or report.get("configured_real_model_used") is not True
            or set(report.get("backends", {})) != {"go", "java"}
            or report.get("real_model_requests", {}).get("failures")):
        raise ValueError("Review requires both completed real backends without hidden upstream failures")
    modules, hashes = original_modules()
    for backend in report["backends"].values():
        rows = backend["query_results"]
        titles = {row["title"] for row in backend["ingestion"]["per_document"]}
        annotations = query_subset(modules["queries.py"].QUERIES, {title: (title, "") for title in titles})
        if (backend["result"] != "PASS" or backend["stage"] != "complete"
                or backend["documents"] != len(titles) or backend["queries"] != len(rows)
                or backend["excluded_queries"] != len(modules["queries.py"].QUERIES) - len(rows)
                or backend["sources_sha256"] != hashes or metrics(rows) != backend["metrics"]
                or [(row["query"], row["docs"], row["level"]) for row in rows] !=
                   [(row["q"], row["docs"], row["level"]) for row in annotations]):
            raise ValueError("Review source metrics or original annotations differ")
    go, java = report["backends"]["go"], report["backends"]["java"]
    if go["documents"] != java["documents"]:
        raise ValueError("Review corpus sizes differ between backends")
    full = (go["documents"] == len(modules["rebuild-corpus.py"].CORPUS)
            and go["queries"] == len(modules["queries.py"].QUERIES) and go["excluded_queries"] == 0)
    assessment = comparison(report["backends"])
    return dict(result="FULL_CURRENT_CORPUS_EXECUTED_QUALITY_NOT_APPROVED" if full else "SUBSET_EXECUTED_QUALITY_NOT_APPROVED",
                documents=go["documents"], full_current_corpus_executed=full,
                historical_corpus_identity_verified=False, source_report=path.name,
                source_sha256=hashlib.sha256(raw).hexdigest(), model=report["model"],
                queries=report["backends"]["go"]["queries"],
                excluded_queries=report["backends"]["go"]["excluded_queries"], assessment=assessment,
                strict_rank_differences=[dict(query=go["query"], go_rank=go["rank"], java_rank=java["rank"],
                                             go_tied_rank=go["tied_rank"], java_tied_rank=java["tied_rank"])
                                         for go, java in zip(report["backends"]["go"]["query_results"],
                                                             report["backends"]["java"]["query_results"])
                                         if go["rank"] != java["rank"]],
                release_gate_approved=False, semantic_quality_verified=False,
                limits="Fresh " + str(go["documents"]) + "-document current-page corpus. Original 0.007 tie threshold unchanged. "
                       "Strict regression remains visible even when tied ranks match. "
                       "Independent upstream noise, random document identities, historical corpus identity, "
                       "factual review, multistage RAG and production acceptance remain separate.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--output-file", type=Path, help="New report in the migration spec directory; no overwrites")
    args = parser.parse_args()
    result = review(args.report)
    if args.write:
        path = args.output_file or SPEC / (args.report.stem.replace("p8-eval-real-", "p8-real-model-review-") + ".json")
        if path.parent.resolve() != SPEC.resolve() or path.resolve() == args.report.resolve():
            parser.error("Review output must be a new file in the migration spec directory")
        with path.open("x", encoding="utf-8") as output:
            output.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        print("Real-model subset reviewed; strict quality and release approval remain separate: " + str(path))
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
