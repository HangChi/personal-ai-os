#!/usr/bin/env python3
"""把浏览器抓下来的 .article-viewer 内部 HTML 转成 Markdown。

为什么单独一步：curl 版抓的是整页 HTML，靠 `class="article-viewer"` 定位正文；
浏览器版抓的已经是那个容器的 innerHTML（纯片段），需要包一层再喂给同一个解析器，
保证两条抓取路径产出的 Markdown 结构完全一致。
"""
import glob
import os
import re
import sys
from html.parser import HTMLParser

SKIP_TAGS = {"script", "style", "svg", "iframe", "noscript"}
SKIP_CLASS = re.compile(r"article-catalog|article-end|catalog-block|comment|recommend")


class Extractor(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.depth = 0
        self.skip = 0
        self.out = []
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
            self.out.append("\n")
        elif tag == "img":
            alt = dict(attrs).get("alt", "")
            self.out.append(f" [图片：{alt}] " if alt else " [图片] ")
        elif tag == "table":
            self.out.append("\n\n")

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


TITLES = {
    "7681235828687175730": "TypeScript 泛型与条件类型",
    "7650146543883239487": "TypeScript 类型体操技巧",
    "7694101953675460648": "分布式一致性：CAP、Raft 与脑裂",
    "7607112994062630939": "系统性地理解分布式系统",
    "7591207451778875418": "动态规划五部曲实战",
    "7472359618326052901": "动态规划基础与核心思想",
    "7672711130193936424": "Nginx 完整配置参考手册",
    "7677521340606300170": "Nginx 生产级配置优化",
}


def main():
    converted = 0
    for raw_path in sorted(glob.glob("/tmp/juejin-raw-*.html")):
        post_id = re.search(r"juejin-raw-(\w+)\.html", raw_path).group(1)
        title = TITLES.get(post_id)
        if not title:
            print(f"!! 未知 id {post_id}，跳过")
            continue

        out_path = f"/tmp/juejin-{post_id}.md"
        if os.path.exists(out_path):
            print(f"SKIP {out_path} 已存在")
            continue

        fragment = open(raw_path, encoding="utf-8").read()
        parser = Extractor()
        # 包一层带目标 class 的容器，让解析器能定位正文
        parser.feed(f'<div class="article-viewer">{fragment}</div>')
        body = parser.text()

        if len(body) < 400:
            print(f"!! {post_id} 转换后仅 {len(body)} 字符，跳过")
            continue

        with open(out_path, "w", encoding="utf-8") as fh:
            fh.write(f"# {title}\n\n")
            fh.write(f"> 来源：掘金 https://juejin.cn/post/{post_id}\n")
            fh.write("> 仅用于本地 RAG 评测，不做传播。\n\n")
            fh.write(body + "\n")
        print(f"OK {len(body):6d} 字符  {title}")
        converted += 1

    print(f"\n转换 {converted} 篇")
    return 0


if __name__ == "__main__":
    sys.exit(main())
