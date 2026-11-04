#!/usr/bin/env python3
"""Validate explicit rendered-page exports and merge private acquisitions without network IO."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import tempfile

from fetch_go_juejin_corpus import MAX_BYTES, extract_page, extractor, load_corpus
from go_eval_adapter import original_modules


def read_private(path, maximum):
    if (not path.is_absolute() or path.resolve() != path or path.is_symlink()
            or not path.is_file() or not 0 < path.stat().st_size <= maximum):
        raise ValueError("Input requires an explicit bounded regular file")
    return path.read_bytes()


def write_private(path, data):
    with path.open("xb") as output:
        output.write(data)
    path.chmod(0o600)


def save_manifest(directory, articles, requested, source_sha, **metadata):
    manifest = dict(version=1, result="COMPLETE" if len(articles) == requested else "PARTIAL",
                    requested=requested, articles=articles, failure=None, extractor_sha256=source_sha,
                    historical_corpus_identity_verified=False, real_model_quality_verified=False,
                    scope="Fresh public pages; local test use only", **metadata)
    write_private(directory / "manifest.json", (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode())
    return manifest


def import_rendered(source, posts, destination, modules):
    labels = dict(modules["rebuild-corpus.py"].CORPUS)
    if not posts or len(set(posts)) != len(posts) or any(post not in labels for post in posts):
        raise ValueError("Rendered imports require unique original article IDs")
    extract, source_sha = extractor()
    prepared = []
    for post in posts:
        receipt_path = source / ("receipt-" + post + ".json")
        receipt_raw = read_private(receipt_path, 16384)
        receipt = json.loads(receipt_raw)
        path = source / ("rendered-" + post + ".html")
        raw = read_private(path, MAX_BYTES)
        url = "https://juejin.cn/post/" + post
        if (receipt.get("post_id") != post or receipt.get("url") != url or receipt.get("canonical") != url
                or receipt.get("acquisition_method") != "normal-browser-rendered-article-viewer"
                or receipt.get("path") != str(path)
                or receipt.get("html_sha256") != hashlib.sha256(raw).hexdigest()):
            raise ValueError("Rendered page receipt differs from the explicit source")
        captured = datetime.fromisoformat(receipt["captured_at"].replace("Z", "+00:00"))
        if captured.tzinfo is None:
            raise ValueError("Rendered page capture needs a timezone")
        title, body = extract_page(raw, post, extract)
        if title != receipt.get("title"):
            raise ValueError("Rendered page title differs from its receipt")
        document = ("# " + title + "\n\n> 来源：" + url + "\n> 评估标注名：" + labels[post]
                    + "\n> 仅本地测试，不传播全文。\n\n" + body + "\n").encode()
        article = dict(post_id=post, label=labels[post], published_title=title, url=url,
                       filename="juejin-" + post + ".md", fetched_at=captured.astimezone(timezone.utc).isoformat(),
                       body_chars=len(body), page_sha256=hashlib.sha256(raw).hexdigest(),
                       body_sha256=hashlib.sha256(body.encode()).hexdigest(), bytes=len(document),
                       sha256=hashlib.sha256(document).hexdigest(),
                       acquisition_method=receipt["acquisition_method"],
                       page_hash_scope="rendered article-viewer HTML plus observed title and canonical; not raw HTTP",
                       source_receipt_sha256=hashlib.sha256(receipt_raw).hexdigest())
        prepared.append((article, document))
    for article, data in prepared:
        write_private(destination / article["filename"], data)
    return save_manifest(destination, [row for row, _ in prepared], len(posts), source_sha)


def merge(sources, destination, modules):
    documents, articles, receipts = {}, {}, []
    for source in sources:
        incoming, manifest = load_corpus(source, modules)
        if documents.keys() & incoming.keys():
            raise ValueError("Merging overlapping article identities is forbidden")
        documents.update(incoming)
        articles.update({row["post_id"]: row for row in manifest["articles"]})
        receipts.append(dict(directory=str(source), documents=len(incoming),
                             manifest_sha256=hashlib.sha256((source / "manifest.json").read_bytes()).hexdigest()))
    if not documents:
        raise ValueError("Merge requires at least one nonempty acquisition")
    corpus = modules["rebuild-corpus.py"].CORPUS
    ordered = [articles[post] for post, _ in corpus if post in articles]
    for article in ordered:
        data = documents[article["post_id"]][1].encode()
        if hashlib.sha256(data).hexdigest() != article["sha256"]:
            raise ValueError("Original acquired bytes changed during merge")
        write_private(destination / article["filename"], data)
    _, source_sha = extractor()
    return save_manifest(destination, ordered, len(corpus), source_sha, merged_from=receipts,
                         missing_articles=[dict(post_id=post, label=label) for post, label in corpus if post not in articles])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    rendered = commands.add_parser("import-rendered")
    rendered.add_argument("--input-dir", required=True, type=Path)
    rendered.add_argument("--post-id", required=True, nargs="+")
    merged = commands.add_parser("merge")
    merged.add_argument("--corpus-dir", action="append", required=True, type=Path)
    for command in (rendered, merged):
        command.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    modules, _ = original_modules()
    destination = args.output_dir
    if destination:
        if not destination.is_absolute() or destination.parent.resolve() != destination.parent:
            parser.error("Output must be a new absolute directory with a real parent")
        destination.mkdir(mode=0o700)
    else:
        destination = Path(tempfile.mkdtemp(prefix="personalos-p8-prepared-")).resolve()
    manifest = (import_rendered(args.input_dir, args.post_id, destination, modules)
                if args.command == "import-rendered" else merge(args.corpus_dir, destination, modules))
    documents, _ = load_corpus(destination, modules)
    print(json.dumps(dict(directory=str(destination), result=manifest["result"], documents=len(documents),
                          historical_corpus_identity_verified=False, real_model_quality_verified=False)))
    return 0 if manifest["result"] == "COMPLETE" else 2


if __name__ == "__main__":
    raise SystemExit(main())
