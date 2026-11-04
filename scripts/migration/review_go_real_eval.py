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
                or backend["sources_sha256"] != hashes or metrics(rows) != backend["metrics"]
                or [(row["query"], row["docs"], row["level"]) for row in rows] !=
                   [(row["q"], row["docs"], row["level"]) for row in annotations]):
            raise ValueError("Review source metrics or original annotations differ")
    assessment = comparison(report["backends"])
    return dict(result="SUBSET_EXECUTED_QUALITY_NOT_APPROVED", source_report=path.name,
                source_sha256=hashlib.sha256(raw).hexdigest(), model=report["model"],
                queries=report["backends"]["go"]["queries"],
                excluded_queries=report["backends"]["go"]["excluded_queries"], assessment=assessment,
                strict_rank_differences=[dict(query=go["query"], go_rank=go["rank"], java_rank=java["rank"],
                                             go_tied_rank=go["tied_rank"], java_tied_rank=java["tied_rank"])
                                         for go, java in zip(report["backends"]["go"]["query_results"],
                                                             report["backends"]["java"]["query_results"])
                                         if go["rank"] != java["rank"]],
                release_gate_approved=False, semantic_quality_verified=False,
                limits="Fresh 16-document subset only. Original 0.007 tie threshold unchanged. "
                       "Strict regression remains visible even when tied ranks match. "
                       "Independent upstream noise, random document identities, full corpus, "
                       "factual review, multistage RAG and production acceptance remain separate.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    result = review(args.report)
    if args.write:
        path = SPEC / "p8-real-model-review.json"
        path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        print("Real-model subset reviewed; strict quality and release approval remain separate: " + str(path))
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
