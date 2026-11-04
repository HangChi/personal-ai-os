#!/usr/bin/env python3
"""Fetch public corpus pages into a new private directory; never touch an existing corpus."""
import argparse
from datetime import datetime, timezone
import hashlib
from html.parser import HTMLParser
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time

from go_eval_adapter import ROOT, original_modules

MAX_BYTES = 8 * 1024 * 1024
EXTRACTOR = "scripts/eval/fetch-corpus-juejin.py"


class FetchFailure(ValueError):
    pass


class Metadata(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.in_title = False
        self.title_parts = []
        self.published_title = ""
        self.canonical = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "title":
            self.in_title = True
        if tag == "meta" and attrs.get("property") == "og:title":
            self.published_title = attrs.get("content", "").strip()
        if tag == "link" and attrs.get("rel") == "canonical":
            self.canonical = attrs.get("href")

    def handle_endtag(self, tag):
        if tag == "title":
            self.in_title = False

    def handle_data(self, text):
        if self.in_title:
            self.title_parts.append(text)


def extractor():
    inventory = json.loads((ROOT / "specs/033-go-rewrite/inventory.json").read_text())
    expected = next(row["sha256"] for row in inventory["files"] if row["path"] == EXTRACTOR)
    if hashlib.sha256((ROOT / EXTRACTOR).read_bytes()).hexdigest() != expected:
        raise ValueError("Original corpus extractor differs from fixed baseline")
    spec = importlib.util.spec_from_file_location("original_p8_article_extractor", ROOT / EXTRACTOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.Extractor, expected


def extract_page(raw, post_id, extract):
    if not 0 < len(raw) <= MAX_BYTES:
        raise FetchFailure("PAGE_SIZE")
    try:
        html = raw.decode("utf-8")
    except UnicodeDecodeError:
        raise FetchFailure("INVALID_UTF8") from None
    metadata = Metadata()
    metadata.feed(html)
    url = "https://juejin.cn/post/" + post_id
    if metadata.canonical is not None and metadata.canonical.rstrip("/") != url:
        raise FetchFailure("CANONICAL_MISMATCH")
    title = metadata.published_title or "".join(metadata.title_parts).strip()
    parser = extract()
    parser.feed(html)
    body = parser.text()
    if not title or len(body.strip()) < 400:
        raise FetchFailure("ARTICLE_BODY_MISSING_OR_BLOCKED")
    return title, body


def fetch_one(post_id, label, directory, extract):
    if not re.fullmatch(r"[0-9]{19}", post_id):
        raise ValueError("Unexpected original article ID")
    url = "https://juejin.cn/post/" + post_id
    response = directory / "response.html"
    try:
        result = subprocess.run([
            "curl", "--silent", "--show-error", "--compressed", "--proto", "=https",
            "--proto-redir", "=https", "--max-redirs", "0", "--connect-timeout", "8",
            "--max-time", "25", "--max-filesize", str(MAX_BYTES), "--output", str(response),
            "--write-out", "%{http_code}\n%{url_effective}\n%{content_type}\n", url,
        ], capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise FetchFailure("NETWORK_OR_TLS_FAILURE")
        metadata = result.stdout.splitlines()
        if len(metadata) != 3 or metadata[0] != "200" or metadata[1] != url:
            raise FetchFailure("HTTP_OR_REDIRECT_REJECTED")
        if not metadata[2].lower().startswith("text/html") or not response.is_file():
            raise FetchFailure("NOT_HTML")
        if response.stat().st_size > MAX_BYTES:
            raise FetchFailure("PAGE_SIZE")
        raw = response.read_bytes()
        title, body = extract_page(raw, post_id, extract)
    except (OSError, subprocess.TimeoutExpired):
        raise FetchFailure("NETWORK_OR_LOCAL_IO_FAILURE") from None
    finally:
        response.unlink(missing_ok=True)
    document = "# " + title + "\n\n> 来源：" + url + "\n> 评估标注名：" + label + "\n> 仅本地测试，不传播全文。\n\n" + body + "\n"
    path = directory / ("juejin-" + post_id + ".md")
    with path.open("x", encoding="utf-8") as output:
        output.write(document)
    path.chmod(0o600)
    return dict(post_id=post_id, label=label, published_title=title, url=url, filename=path.name,
                fetched_at=datetime.now(timezone.utc).isoformat(), body_chars=len(body),
                page_sha256=hashlib.sha256(raw).hexdigest(), body_sha256=hashlib.sha256(body.encode()).hexdigest(),
                bytes=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def collect(directory, corpus, extract, extractor_sha, pause=time.sleep):
    manifest = dict(version=1, result="IN_PROGRESS", requested=len(corpus), articles=[], failure=None,
                    extractor_sha256=extractor_sha, historical_corpus_identity_verified=False,
                    real_model_quality_verified=False, scope="Fresh public pages; local test use only")
    for index, (post_id, label) in enumerate(corpus):
        if index:
            pause(3)
        try:
            article = fetch_one(post_id, label, directory, extract)
        except FetchFailure as error:
            manifest["failure"] = dict(post_id=post_id, reason=str(error))
            break
        manifest["articles"].append(article)
        print("Fetched " + post_id + ": " + str(article["body_chars"]) + " body characters", flush=True)
    manifest["result"] = ("COMPLETE" if len(manifest["articles"]) == len(corpus)
                          else "PARTIAL" if manifest["articles"] else "BLOCKED")
    with (directory / "manifest.json").open("x", encoding="utf-8") as output:
        output.write(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(dict(directory=str(directory), result=manifest["result"],
                          documents=len(manifest["articles"]), failure=manifest["failure"]), ensure_ascii=False), flush=True)
    return manifest


def load_corpus(directory, modules):
    directory = Path(directory)
    if not directory.is_absolute() or directory.is_symlink() or directory.resolve() != directory:
        raise ValueError("Downloaded corpus requires an explicit absolute real directory")
    path = directory / "manifest.json"
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ValueError("Downloaded corpus manifest is missing or unsafe")
    manifest = json.loads(path.read_text())
    _, expected_sha = extractor()
    if (manifest.get("version") != 1 or manifest.get("result") not in ("COMPLETE", "PARTIAL")
            or manifest.get("extractor_sha256") != expected_sha):
        raise ValueError("Downloaded corpus has an invalid result or extractor identity")
    labels = dict(modules["rebuild-corpus.py"].CORPUS)
    documents = {}
    articles = manifest.get("articles")
    if not isinstance(articles, list) or not 1 <= len(articles) <= len(labels):
        raise ValueError("Downloaded corpus has an invalid document count")
    for article in articles:
        post_id = article.get("post_id")
        if (post_id not in labels or post_id in documents or article.get("label") != labels[post_id]
                or article.get("url") != "https://juejin.cn/post/" + post_id
                or article.get("filename") != "juejin-" + post_id + ".md"
                or not isinstance(article.get("published_title"), str) or not article["published_title"].strip()
                or type(article.get("body_chars")) is not int or article["body_chars"] < 400):
            raise ValueError("Downloaded article identity differs from the fixed corpus")
        path = directory / article["filename"]
        if path.is_symlink() or not path.is_file() or not 400 <= path.stat().st_size <= MAX_BYTES:
            raise ValueError("Downloaded article file is missing or unsafe")
        data = path.read_bytes()
        if len(data) != article.get("bytes") or hashlib.sha256(data).hexdigest() != article.get("sha256"):
            raise ValueError("Downloaded article content changed since acquisition")
        documents[post_id] = (labels[post_id], data.decode("utf-8"))
    return documents, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, choices=range(1, 27), default=6)
    parser.add_argument("--output-dir", type=Path, help="New absolute directory only; never overwrite")
    args = parser.parse_args()
    modules, _ = original_modules()
    extract, source_sha = extractor()
    if args.output_dir:
        directory = args.output_dir
        if not directory.is_absolute() or directory.parent.resolve() != directory.parent:
            parser.error("Output must be a new absolute path with a real parent")
        directory.mkdir(mode=0o700)
    else:
        directory = Path(tempfile.mkdtemp(prefix="personalos-p8-juejin-")).resolve()
    manifest = collect(directory, modules["rebuild-corpus.py"].CORPUS[:args.limit], extract, source_sha)
    return 0 if manifest["result"] == "COMPLETE" else 2


if __name__ == "__main__":
    raise SystemExit(main())
