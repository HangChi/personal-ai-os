#!/usr/bin/env python3
"""第三批抓取：把语料从 10 篇扩到 26 篇。

目的：解决 R@5 的天花板效应。10 篇 + topK=5 时随机基线就有 50%，
R@5 恒为 100% 毫无区分力；26 篇时随机基线降到 5/26 = 19.2%。

选题原则：**主题互不重叠**（Docker/浏览器/Kafka/Python/TS/分布式/DP/Nginx），
每主题取 2 篇以保留「同主题干扰」。主题越分散，检索的区分度才看得出来。
"""
import os
import re
import subprocess
import sys
import time
from html.parser import HTMLParser

UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")

ARTICLES = [
    # ---- Docker ----
    ("7590681158717472808", "Docker 与虚拟机的区别：Namespace 和 Cgroup"),
    ("7693201755920465939", "Docker 深度解析：UnionFS 与核心组件"),
    # ---- 浏览器渲染 ----
    ("7694832291801677878", "浏览器渲染管道：重排、重绘与合成层"),
    ("7631430251667243023", "浏览器渲染原理：从输入到像素"),
    # ---- Kafka ----
    ("7628488008481751083", "Kafka 实战总结：架构与吞吐优化"),
    ("7623271328049676330", "Kafka 为什么快：PageCache、零拷贝与 Segment"),
    # ---- Python 并发 ----
    ("7651932218912522280", "Python 并发选型：GIL、多线程、多进程与协程"),
    ("7664457295027814451", "Python 异步陷阱：谁在帮你并发"),
    # ---- TypeScript ----
    ("7681235828687175730", "TypeScript 泛型与条件类型"),
    ("7650146543883239487", "TypeScript 类型体操技巧"),
    # ---- 分布式 ----
    ("7694101953675460648", "分布式一致性：CAP、Raft 与脑裂"),
    ("7607112994062630939", "系统性地理解分布式系统"),
    # ---- 动态规划 ----
    ("7591207451778875418", "动态规划五部曲实战"),
    ("7472359618326052901", "动态规划基础与核心思想"),
    # ---- Nginx ----
    ("7672711130193936424", "Nginx 完整配置参考手册"),
    ("7677521340606300170", "Nginx 生产级配置优化"),
]

SKIP_TAGS = {"script", "style", "svg", "iframe", "noscript"}
SKIP_CLASS = re.compile(r"article-catalog|article-end|catalog-block")


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


def fetch(post_id):
    url = f"https://juejin.cn/post/{post_id}"
    html = subprocess.run(["curl", "-s", "--noproxy", "*", "-A", UA, url],
                          capture_output=True, text=True, check=True).stdout
    parser = Extractor()
    parser.feed(html)
    return parser.text()


def main():
    failed = []
    for post_id, title in ARTICLES:
        path = f"/tmp/juejin-{post_id}.md"
        if os.path.exists(path):
            print(f"SKIP {path} 已存在")
            continue
        body = fetch(post_id)
        if len(body) < 400:
            print(f"!! {post_id} 正文仅 {len(body)} 字符 —— 疑似反爬，暂停 20 秒后继续")
            failed.append((post_id, title))
            time.sleep(20)
            continue
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(f"# {title}\n\n")
            fh.write(f"> 来源：掘金 https://juejin.cn/post/{post_id}\n")
            fh.write("> 仅用于本地 RAG 评测，不做传播。\n\n")
            fh.write(body + "\n")
        print(f"OK {len(body):6d} 字符  {title}")
        time.sleep(3)

    if failed:
        print(f"\n抓到失败 {len(failed)} 篇：")
        for post_id, title in failed:
            print(f"  {post_id}  {title}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
