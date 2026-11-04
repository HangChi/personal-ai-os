#!/usr/bin/env python3
"""按已经验收的职责组补充 P6 台账，保留其他阶段和发布门禁状态。"""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / 'specs/033-go-rewrite'
GROUPS = {
    'readers': {
        'sources': ['DocumentFilenameSupport', 'KnowledgeDocumentReader', 'ReaderSupport',
                    'DocumentReaderFactory', 'PdfDocumentReader', 'DocxDocumentReader',
                    'ImageDocumentReader', 'VisionTextExtractor', 'MarkdownStructureTextSplitter'],
        'evidence': ['reader.go', 'reader_test.go', 'splitter.go', 'splitter_test.go'],
        'note': 'P6 首批：文件名/UTF-8/大小/短文拒收、PDF 500页和超时/空文本/损坏、DOCX段落表格/ZIP/XML边界、图片MIME/空白/无文本与上游失败、Markdown围栏/标题栈/重叠/短块无丢失均经race测试；真实模型装配、向量与完整Java差分仍待后续批次。',
    },
}

def update(groups):
    path = SPEC / 'progress.json'
    progress = json.loads(path.read_text())
    inventory = json.loads((SPEC / 'inventory.json').read_text())
    keys = {entry['id'] for entries in inventory.values() if isinstance(entries, list)
            for entry in entries if isinstance(entry, dict) and 'id' in entry}
    changed = 0
    for name in groups:
        group = GROUPS[name]
        evidence = ['backend-go/internal/business/knowledge/' + f for f in group['evidence']]
        evidence.append('specs/033-go-rewrite/validation.md')
        for entry in evidence:
            if not (ROOT / entry).is_file():
                raise ValueError('证据不存在: ' + entry)
        for key in sorted(keys):
            if '/knowledge/' not in key:
                continue
            if not any('/' + source + '.java' in key or '/' + source + 'Test.java' in key
                       for source in group['sources']):
                continue
            item = progress['items'].setdefault(key, {'status': 'not_started', 'evidence': []})
            if item['status'] != 'verified':
                item['status'] = 'implemented'
            item['evidence'] = list(dict.fromkeys(item.get('evidence', []) + evidence))
            item['note'] = group['note']
            changed += 1
    path.write_text(json.dumps(progress, ensure_ascii=False, indent=2) + '\n')
    print('更新 P6 条目:', changed)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('groups', nargs='+', choices=GROUPS)
    update(parser.parse_args().groups)
