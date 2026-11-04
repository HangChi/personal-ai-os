#!/usr/bin/env python3
"""Record deterministic Java/Go contract parity and disposable four-facility regressions.

Controlled scores test contract preservation only. They do not measure embedding/model quality.
The historical 129-query article corpus is not available in this repository.
"""
import json
import hashlib
from pathlib import Path
import re
import subprocess
import time

ROOT=Path(__file__).resolve().parents[2]

def main():
    subprocess.run(['python3','scripts/migration/java_p6_fixture.py','--write'],cwd=ROOT,check=True)
    start=time.monotonic()
    result=subprocess.run(['python3','scripts/migration/run_go_p6_tests.py'],cwd=ROOT,text=True,
                          stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=600)
    if result.returncode:
        print(result.stdout[-16000:]);raise SystemExit(result.returncode)
    reports=[json.loads(line.split('P6_EVAL ',1)[1]) for line in result.stdout.splitlines() if 'P6_EVAL ' in line]
    if {r['suite'] for r in reports}!={'controlled_live_corpus','real_facilities_roles'}:
        raise SystemExit('Missing evaluation evidence')
    record=dict(date='2026-11-04',baseline_commit=json.loads((ROOT/'specs/033-go-rewrite/inventory.json').read_text())['baseline_commit'],
                elapsed_seconds=round(time.monotonic()-start,3),reports=reports,
                passed_tests=[name.strip() for name in re.findall(r'^--- PASS: ([^(]+)',result.stdout,re.M)],
                inputs_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
                               for p in [ROOT/'backend-go/internal/business/knowledge/testdata/java-p6.json',
                                         ROOT/'scripts/eval/rag-live-cases.json', ROOT/'scripts/eval/queries.py']},
                semantic_quality_measured=False,
                limitation='Controlled scores exercise real Go pgvector SQL against Java service fixtures. Neither mock-channel Java latency nor local Go SQL latency predicts production model quality or end-to-end speed.')
    target=ROOT/'specs/033-go-rewrite/p6-eval-results.json';target.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
    for r in reports: print(json.dumps(r,ensure_ascii=False))
    print('P6 evidence saved:',target)

if __name__=='__main__':main()
