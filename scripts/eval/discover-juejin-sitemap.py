#!/usr/bin/env python3
'''Sample post URLs from Juejin public sitemap; do not use private search APIs.'''
import argparse
import hashlib
import json
from pathlib import Path
import re
from urllib.robotparser import RobotFileParser
import xml.etree.ElementTree as ET

def main():
    import httpx
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--input',type=Path,help='Use an already fetched sitemap without another request')
    p.add_argument('--page',type=int,default=1)
    p.add_argument('--sample',type=int,default=4000)
    args=p.parse_args()
    if args.page<1 or args.sample<1:raise ValueError('Positive page/sample required')
    url='https://juejin.cn/sitemap/posts/index'+str(args.page)+'.xml'
    args.output.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    if args.input:
        raw=args.input.read_bytes()
    else:
        with httpx.Client(timeout=60,follow_redirects=False)as client:
            robots=client.get('https://juejin.cn/robots.txt');robots.raise_for_status()
            rules=RobotFileParser();rules.parse(robots.text.splitlines())
            if not rules.can_fetch('*',url):raise ValueError('Sitemap disallowed by robots')
            result=client.get(url);result.raise_for_status();raw=result.content
        cached=args.output.parent/('sitemap-index'+str(args.page)+'.xml')
        cached.write_bytes(raw);cached.chmod(0o600)
    root=ET.fromstring(raw);ns={'s':'http://www.sitemaps.org/schemas/sitemap/0.9'}
    ids=[]
    for node in root:
        loc=node.findtext('s:loc',namespaces=ns)or ''
        match=re.fullmatch(r'https://juejin.cn/post/([0-9]{19})',loc)
        if match:ids.append(match.group(1))
    ids=sorted(set(ids),key=lambda x:hashlib.sha256(('20261108|'+x).encode()).hexdigest())[:args.sample]
    seeds=json.loads(args.output.read_text())if args.output.exists()else {'documents':[]}
    existing={d['slug']for d in seeds['documents']}
    seeds['documents'] += [dict(slug='juejin-'+x)for x in ids if 'juejin-'+x not in existing]
    seeds['sitemap']=dict(url=url,sha256=hashlib.sha256(raw).hexdigest(),sample=args.sample,
        method='Deterministic SHA256 URL sample; article bodies fetched and deduplicated separately')
    temp=args.output.with_suffix('.tmp');temp.write_text(json.dumps(seeds,ensure_ascii=False,indent=2));temp.chmod(0o600);temp.replace(args.output)
    print('Public post seeds:',len(seeds['documents']))
if __name__=='__main__':main()
