"""Load fixed evaluation algorithms; restrict their IO to an owned empty fixture."""
import contextlib
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import sys
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ("queries.py", "rebuild-corpus.py", "retrieval-eval.py", "noise-floor.py")


def original_modules():
    inventory = json.loads((ROOT / "specs/033-go-rewrite/inventory.json").read_text())
    expected = {row["path"]: row["sha256"] for row in inventory["files"]}
    hashes = {}
    # Check every dependency before executing any original module.
    for name in SOURCES:
        path = "scripts/eval/" + name
        digest = hashlib.sha256((ROOT / path).read_bytes()).hexdigest()
        if digest != expected[path]:
            raise ValueError("Evaluation source differs from fixed baseline: " + name)
        hashes[path] = digest
    modules = {}
    previous = sys.modules.get("queries")
    try:
        for name in SOURCES:
            spec = importlib.util.spec_from_file_location("original_p8_" + name.replace("-", "_"), ROOT / "scripts/eval" / name)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            modules[name] = module
            if name == "queries.py":
                sys.modules["queries"] = module
    finally:
        if previous is None:
            sys.modules.pop("queries", None)
        else:
            sys.modules["queries"] = previous
    return modules, hashes


def corpus_preflight(modules, directory=None):
    corpus = modules["rebuild-corpus.py"].CORPUS
    queries = modules["queries.py"].QUERIES
    titles = {title for _, title in corpus}
    if len(titles) != len(corpus) or any(not set(row["docs"]) <= titles for row in queries):
        raise ValueError("Original corpus/query annotations disagree")
    report = dict(required_documents=len(corpus), queries=len(queries), source_provenance_verified=False,
                  real_model_quality_verified=False, available=[], missing=[], invalid=[])
    if directory is None:
        report.update(result="BLOCKED", reason="Original corpus directory was not provided; no implicit /tmp scan")
        report["missing"] = [post for post, _ in corpus]
        return report
    directory = Path(directory)
    if not directory.is_absolute() or not directory.is_dir() or directory.is_symlink():
        raise ValueError("Corpus preflight requires an explicit absolute real directory")
    for post, _ in corpus:
        path = directory / ("juejin-" + post + ".md")
        if path.is_symlink() or (path.exists() and not path.is_file()):
            report["invalid"].append(post)
        elif not path.exists():
            report["missing"].append(post)
        elif not 350 <= path.stat().st_size <= 8 * 1024 * 1024:
            report["invalid"].append(post)
        else:
            data = path.read_bytes()
            try:
                text = data.decode("utf-8")
            except UnicodeDecodeError:
                report["invalid"].append(post)
                continue
            if len(text.strip()) < 350:
                report["invalid"].append(post)
                continue
            report["available"].append(dict(post_id=post, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
    report["result"] = "BLOCKED" if report["missing"] or report["invalid"] else "FILES_PRESENT_NOT_AUTHENTICATED"
    return report


class FixtureAdapter:
    def __init__(self, client, database, documents):
        database.require_empty()
        self.client = client
        self.documents = dict(documents)
        self.uploaded = {}
        self.search_calls = 0

    @staticmethod
    def refuse(*args, **kwargs):
        raise ValueError("Evaluation adapter never calls original rebuild main/delete_all or fixed-target IO")

    def upload(self, path, title):
        # Original upload accepts a local path; here it is an in-memory fixture key.
        # No original curl, arbitrary file open, URL or deletion is permitted.
        if path not in self.documents or self.documents[path][0] != title or title in self.uploaded.values():
            raise ValueError("Evaluation upload is outside the owned fixture manifest")
        content = self.documents[path][1].encode()
        document = self.client.upload("/knowledge/documents?title=" + quote(title, safe=""),
                                      "p8-eval.md", content, status=201)
        if document.get("title") != title or not document.get("id") or document.get("chunkCount", 0) <= 0:
            raise AssertionError("Evaluation ingestion did not persist the expected document")
        self.uploaded[document["id"]] = title
        return document

    def search(self, query, top_k=5):
        if not isinstance(query, str) or not query.strip() or type(top_k) is not int or not 1 <= top_k <= 20:
            raise ValueError("Invalid original evaluation search parameters")
        result = self.client.request("/knowledge/search", "POST", {"query": query, "topK": top_k})
        hits = result["hits"]
        if not isinstance(hits, list) or len(hits) > top_k:
            raise AssertionError("Evaluation search response has an invalid result count")
        seen = set()
        for hit in hits:
            identity = hit.get("documentId")
            score = hit.get("score")
            if identity not in self.uploaded or self.uploaded[identity] != hit.get("documentTitle") or identity in seen:
                raise AssertionError("Evaluation search escaped its manifest or repeated a document")
            if score is not None and (type(score) not in (int, float) or not math.isfinite(score)):
                raise AssertionError("Evaluation search returned an invalid score")
            seen.add(identity)
        self.search_calls += 1
        return hits

    def bind(self, modules):
        rebuild = modules["rebuild-corpus.py"]
        rebuild.upload = self.upload
        rebuild.main = rebuild.delete_all = rebuild.request = self.refuse
        for name in ("retrieval-eval.py", "noise-floor.py"):
            modules[name].search = self.search


@contextlib.contextmanager
def arguments(values):
    previous = sys.argv
    try:
        sys.argv = list(values)
        yield
    finally:
        sys.argv = previous
