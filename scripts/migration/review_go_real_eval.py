#!/usr/bin/env python3
"""Derive an explicit strict/tied quality assessment without rerunning models or approving release."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from go_eval_adapter import original_modules
from run_go_eval_tests import query_subset
from run_go_real_eval_tests import SPEC, comparison, metrics


def review_multistage(report, raw, path):
    if (report.get("result") not in ("PASS", "FAIL") or report.get("synthetic_model") is not False
            or report.get("configured_real_model_used") is not True
            or set(report.get("backends", {})) != {"go", "java"}
            or report.get("real_model_requests", {}).get("failures")):
        raise ValueError("Multistage review requires both completed real backends without upstream failures")
    modules, hashes = original_modules()
    annotations = modules["queries.py"].QUERIES
    documents = len(modules["rebuild-corpus.py"].CORPUS)
    summaries, total_calls = {}, 1  # The runner performs one chat connectivity preflight.
    for name, backend in report["backends"].items():
        rows = backend.get("multistage_cases", [])
        if (backend.get("mode") != "multistage-full" or backend.get("stage") != "complete"
                or backend.get("documents") != documents or backend.get("sources_sha256") != hashes
                or backend.get("queries") != len(annotations) or len(rows) != len(annotations)
                or backend.get("executed_original_queries") != len(rows)
                or backend.get("unexecuted_eligible_queries") != 0 or backend.get("excluded_queries") != 0
                or backend.get("all_original_questions_executed") is not True
                or backend.get("processes_stopped") is not True or backend.get("database_connections_after_stop") != 0
                or [(row.get("question"), row.get("acceptable_documents")) for row in rows] !=
                   [(row["q"], row["docs"]) for row in annotations]):
            raise ValueError("Multistage review coverage, original annotations or shutdown evidence differs")
        passed = sum(row.get("result") == "PASS" for row in rows)
        if (any(row.get("result") not in ("PASS", "FAIL") for row in rows)
                or backend.get("multistage_passed") != passed or backend.get("multistage_failed") != len(rows) - passed
                or backend.get("result") != ("PASS" if passed == len(rows) else "FAIL")):
            raise ValueError("Multistage source outcome counters differ")
        for row in rows:
            calls = row.get("actual_chat_calls")
            if type(calls) is not int or calls < 0:
                raise ValueError("Multistage actual call counter is invalid")
            total_calls += calls
            if row.get("result") == "PASS" and (row.get("retrieval_status") != "READY"
                    or row.get("answered") is not True or row.get("warning_count") != 0 or calls != 4
                    or [(stage.get("name"), stage.get("status")) for stage in row.get("stages", [])] !=
                       [(stage, "OK") for stage in ("recall", "rewrite", "rerank", "evidence")]
                    or row.get("answer", {}).get("explicit_marks_match") is not True
                    or row.get("answer", {}).get("citation_chunks_match_database") is not True
                    or row.get("answer", {}).get("acceptable_document_cited") is not True):
                raise ValueError("Multistage PASS lacks original pipeline or persisted citation checks")
        summaries[name] = dict(attempted=len(rows), response_observed=sum("retrieval_status" in row for row in rows),
            strict_passed=passed, strict_failed=len(rows) - passed,
            status_counts=dict(Counter(row.get("retrieval_status", "NO_RESPONSE") for row in rows)),
            cases_with_warnings=sum(row.get("warning_count", 0) > 0 for row in rows),
            fallback_stages=dict(Counter(stage["name"] for row in rows for stage in row.get("stages", [])
                                         if stage["status"] != "OK")),
            failed_cases=[dict(index=index, question=row["question"],
                retrieval_status=row.get("retrieval_status"), warning_count=row.get("warning_count"),
                failure=row.get("failure", row.get("error_type")))
                for index, row in enumerate(rows, 1) if row.get("result") != "PASS"])
    expected_result = "PASS" if all(value["strict_failed"] == 0 for value in summaries.values()) else "FAIL"
    if report.get("result") != expected_result:
        raise ValueError("Multistage overall result differs from original case outcomes")
    if report["real_model_requests"].get("calls", {}).get("chat") != total_calls:
        raise ValueError("Multistage actual model totals differ from original case receipts and preflight")
    paired = Counter((left["result"], right["result"]) for left, right in zip(
        report["backends"]["go"]["multistage_cases"], report["backends"]["java"]["multistage_cases"]))
    return dict(result="FULL_MULTISTAGE_ATTEMPTED_QUALITY_NOT_APPROVED", source_report=path.name,
        source_sha256=hashlib.sha256(raw).hexdigest(), documents=documents, queries_per_backend=len(annotations),
        model=report["model"], backends=summaries, factual_quality_manually_approved=False,
        paired_strict_outcomes=[dict(go=go, java=java, cases=count) for (go, java), count in sorted(paired.items())],
        release_gate_approved=False, semantic_quality_verified=False, historical_corpus_identity_verified=False,
        limits="Every original case retained in its original order, including guarded and degraded responses. "
               "Independent model calls and shared per-backend governance state are not isolated per-question experiments. "
               "Matching failures or complete execution do not approve factual quality or release gates.")


def review(path):
    raw = path.read_bytes()
    report = json.loads(raw)
    if report.get("mode") == "multistage-full":
        return review_multistage(report, raw, path)
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
        print("Real-model report reviewed; strict quality and release approval remain separate: " + str(path))
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
