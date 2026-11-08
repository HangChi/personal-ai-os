#!/usr/bin/env python3
"""Collect publicly readable Juejin article bodies, stopping on access challenges.
No login, browser challenge, proxy rotation, or private API is used.
All full text and raw responses stay in a private ignored directory.
"""
import argparse
from collections import deque
import importlib.util
import json
from pathlib import Path
import re
import time
from urllib.parse import urlparse

SPEC = importlib.util.spec_from_file_location('knowledge_scale', Path(__file__).with_name('knowledge-scale.py'))
scale = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(scale)

def article_ids(html):
    return list(dict.fromkeys(re.findall(r'/post/([0-9]{19})', html)))

def main():
    import httpx
    from bs4 import BeautifulSoup
    from markdownify import markdownify
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seeds', type=Path, required=True)
    parser.add_argument('--limit', type=int, default=600)
    parser.add_argument('--pause', type=float, default=2)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True, mode=0o700)
    (out / 'corpus').mkdir(exist_ok=True)
    (out / 'raw').mkdir(exist_ok=True)
    state_path = out / 'manifest.json'
    state = scale.load(state_path) if state_path.exists() else dict(started_at=scale.now(), documents=[], observations=[], requested=args.limit)
    seeds = article_ids(args.seeds.read_text())
    seeds += [d['slug'].split('-')[1] for d in scale.load(args.seeds)['documents'] if d.get('slug', '').startswith('juejin-')]
    queue = deque(dict.fromkeys(seeds))
    visited = {o['post_id'] for o in state['observations']}
    seen = {d['body_sha256'] for d in state['documents']}
    client = httpx.Client(timeout=35, follow_redirects=False)
    robots = client.get('https://juejin.cn/robots.txt')
    robots.raise_for_status()
    (out / 'robots.txt').write_text(robots.text)
    from urllib.robotparser import RobotFileParser
    rules = RobotFileParser()
    rules.parse(robots.text.splitlines())
    try:
        while queue and len(state['documents']) < args.limit:
            post_id = queue.popleft()
            if post_id in visited:
                continue
            visited.add(post_id)
            url = 'https://juejin.cn/post/' + post_id
            if not rules.can_fetch('*', url):
                state['observations'].append(dict(post_id=post_id, status='ROBOTS_DISALLOWED'))
                continue
            time.sleep(max(1.5, args.pause))
            try:
                response = client.get(url)
            except httpx.TransportError as error:
                state['observations'].append(dict(post_id=post_id, status='NETWORK_ERROR', error_type=type(error).__name__))
                state['stopped_reason'] = 'network_failure'
                break
            if response.status_code == 404:
                state['observations'].append(dict(post_id=post_id, status='NOT_FOUND'))
                continue
            soup = BeautifulSoup(response.text, 'html.parser')
            body = soup.select_one('.article-viewer')
            if response.status_code != 200 or body is None or len(body.get_text(strip=True)) < 400:
                state['observations'].append(dict(post_id=post_id, status='BODY_MISSING_OR_ACCESS_RESTRICTED', http_status=response.status_code))
                state['stopped_reason'] = 'access_restriction_or_missing_article_body; no bypass attempted'
                break
            canonical = soup.select_one('link[rel=canonical]')
            if canonical and canonical.get('href', '').rstrip('/') != url:
                state['stopped_reason'] = 'canonical_mismatch'
                break
            for candidate in article_ids(response.text):
                if candidate not in visited:
                    queue.append(candidate)
            title_tag = soup.select_one('h1.article-title') or soup.select_one('meta[property=og:title]') or soup.title
            title = ' '.join((title_tag.get('content') or title_tag.get_text(' ', strip=True)).split())
            author_tag = soup.select_one('.author-info-box .username') or soup.select_one('.author-info-box .name') or soup.select_one('meta[name=author]')
            author = (author_tag.get('content') or author_tag.get_text(' ', strip=True)) if author_tag else '以原文作者栏为准'
            for item in body.select('script,style,.article-catalog,.article-end,.catalog-block'):
                item.decompose()
            for link in body.select('a[href]'):
                from urllib.parse import urljoin
                link['href'] = urljoin(url, link['href'])
            text = markdownify(str(body), heading_style='ATX', strip=['img']).strip()
            digest = scale.sha(' '.join(text.split()))
            if digest in seen:
                state['observations'].append(dict(post_id=post_id, status='DUPLICATE_BODY'))
                continue
            if len(text) > 120000:
                state['observations'].append(dict(post_id=post_id, status='OVERSIZE_NOT_TRUNCATED'))
                continue
            seen.add(digest)
            content = scale.NL.join(['# ' + title, '', '来源：' + url, '作者：' + author, '采集时间：' + scale.now(),
                '收录范围：公开博客正文；仅用于用户本地知识检索和测评，版权归原作者。', '', text, ''])
            filename = 'juejin-full-' + post_id + '.md'
            path = out / 'corpus' / filename
            path.write_text(content)
            path.chmod(0o600)
            raw = out / 'raw' / (post_id + '.html')
            raw.write_bytes(response.content)
            raw.chmod(0o600)
            state['documents'].append(dict(slug=filename[:-3], filename=filename, title=('【掘金正文】' + title)[:300],
                group='juejin', version='公开页快照 ' + scale.now()[:10], relative=post_id, source_url=url, author=author,
                raw_sha256=scale.sha(response.content), body_sha256=digest, sha256=scale.sha(content), chars=len(content)))
            state['observations'].append(dict(post_id=post_id, status='FULL_BODY_COLLECTED', chars=len(text)))
            scale.save(state_path, state)
            print(scale.now(), 'Juejin full texts', len(state['documents']), '/', args.limit, 'queue', len(queue), title[:60], flush=True)
    finally:
        state['finished_at'] = scale.now()
        scale.save(state_path, state)
        client.close()
    print(json.dumps(dict(documents=len(state['documents']), stopped_reason=state.get('stopped_reason', 'limit_or_queue_exhausted')), ensure_ascii=False), flush=True)

if __name__ == '__main__':
    main()
