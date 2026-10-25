#!/usr/bin/env python3
"""第二批抓取（增量）：只抓 /tmp 里还没有的文章。

反爬注意：掘金对连续请求会返回空壳，所以每篇之间 sleep，且失败即停
（继续硬闯只会把 IP 越踩越实）。
"""
import re
import subprocess
import sys
import time
from html.parser import HTMLParser

UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")

# (post_id, 干净标题)
ARTICLES = [
    ("7271659132381216787", "Redis 缓存机制"),
    ("7569377216896057395", "HTTP 一篇说清楚"),
    ("7692646947434643502", "MySQL 事务、索引与锁"),
    ("7681202345805119488", "GMP 调度器：Go 并发的心脏"),
    ("7577229187897098275", "Go 并发模式选择指南"),
    ("7687592289394704399", "HTTP 渐进：编码、缓存与断点续传"),
    ("7696709714028281906", "MySQL 学习记：从增删改查到慢查询"),
]

SKIP_TAGS = {"script", "style", "svg", "iframe", "noscript"}
SKIP_CLASS = re.compile(r"article-catalog|article-end|catalog-block")


class Extractor(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.depth = 0
        self.skip = 0
        self.out = []
        self.list_stack = []
        self.in_pre = False
        self.pre_buf = []

    def handle_starttag(self, tag, attrs):
        cls = dict(attrs).get("class", "")
        if self.depth == 0:
            if "article-viewer" in cls:
                self.depth = 1
            return
        if self.skip:
            self.skip += 1
            return
        if tag in SKIP_TAGS or SKIP_CLASS.search(cls):
            self.skip = 1
            return
        self.depth += 1
        if tag in ("h1", "h2", "h3", "h4", "h5", "h6"):
            self.out.append("\n\n" + "#" * int(tag[1]) + " ")
        elif tag == "p":
            self.out.append("\n\n")
        elif tag == "br":
            self.out.append("\n")
        elif tag == "hr":
            self.out.append("\n\n---\n\n")
        elif tag in ("strong", "b"):
            self.out.append("**")
        elif tag in ("em", "i"):
            self.out.append("*")
        elif tag == "code" and not self.in_pre:
            self.out.append("`")
        elif tag == "pre":
            self.in_pre = True
            self.pre_buf = []
        elif tag == "li":
            self.out.append("\n- ")
        elif tag in ("ul", "ol"):
            self.list_stack.append(tag)
            self.out.append("\n")
        elif tag == "img":
            alt = dict(attrs).get("alt", "")
            self.out.append(f" [图片：{alt}] " if alt else " [图片] ")

    def handle_endtag(self, tag):
        if self.depth == 0:
            return
        if self.skip:
            self.skip -= 1
            return
        if tag in ("h1", "h2", "h3", "h4", "h5", "h6", "p"):
            self.out.append("\n")
        elif tag in ("strong", "b"):
            self.out.append("**")
        elif tag in ("em", "i"):
            self.out.append("*")
        elif tag == "code" and not self.in_pre:
            self.out.append("`")
        elif tag == "pre":
            self.in_pre = False
            self.out.append("\n\n```\n" + "".join(self.pre_buf).strip("\n") + "\n```\n\n")
        elif tag in ("ul", "ol") and self.list_stack:
            self.list_stack.pop()
        self.depth -= 1

    def handle_data(self, data):
        if self.depth == 0 or self.skip:
            return
        if self.in_pre:
            self.pre_buf.append(data)
        else:
            self.out.append(data)

    def text(self):
        raw = "".join(self.out)
        raw = re.sub(r"[ \t]+\n", "\n", raw)
        raw = re.sub(r"\n{3,}", "\n\n", raw)
        return raw.strip()


def fetch(post_id):
    url = f"https://juejin.cn/post/{post_id}"
    html = subprocess.run(["curl", "-s", "--noproxy", "*", "-A", UA, url],
                          capture_output=True, text=True, check=True).stdout
    parser = Extractor()
    parser.feed(html)
    return parser.text()


def main():
    for post_id, title in ARTICLES:
        path = f"/tmp/juejin-{post_id}.md"
        import os
        if os.path.exists(path):
            print(f"SKIP {path} 已存在")
            continue
        body = fetch(post_id)
        if len(body) < 400:
            print(f"!! {post_id} 正文仅 {len(body)} 字符 —— 疑似被反爬，停止（避免继续触发）")
            return 1
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(f"# {title}\n\n")
            fh.write(f"> 来源：掘金 https://juejin.cn/post/{post_id}\n")
            fh.write("> 仅用于本地 RAG 评测，不做传播。\n\n")
            fh.write(body + "\n")
        print(f"OK {path}  {len(body)} 字符  {title}")
        time.sleep(3)
    return 0


if __name__ == "__main__":
    sys.exit(main())
