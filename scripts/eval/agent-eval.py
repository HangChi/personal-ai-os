#!/usr/bin/env python3
"""真实模型 Agent 场景评测；只创建/清理带唯一前缀的测试任务和会话，不清库。"""
import argparse
import json
import math
import os
import time
import uuid
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen


class Client:
    def __init__(self, base):
        self.base = base.rstrip('/')
        self.headers = {'Content-Type': 'application/json'}
        if os.environ.get('AGENT_EVAL_COOKIE'):
            self.headers['Cookie'] = os.environ['AGENT_EVAL_COOKIE']

    def api(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        with urlopen(Request(self.base + path, data=data, headers=self.headers, method=method), timeout=30) as r:
            result = json.load(r)
        if result['code'] != 0:
            raise RuntimeError(result.get('message', 'API 失败'))
        return result.get('data')

    def chat(self, session, prompt):
        events, event, data = [], '', []
        body = json.dumps({'content': prompt}).encode()
        headers = dict(self.headers, Accept='text/event-stream')
        with urlopen(Request(self.base + f'/chat/sessions/{session}/stream', data=body, headers=headers), timeout=260) as r:
            for raw in r:
                line = raw.decode().rstrip('\r\n')
                if not line:
                    if data:
                        events.append((event, json.loads('\n'.join(data))))
                        if event in ('done', 'error'):
                            return events
                    event, data = '', []
                elif line.startswith('event:'):
                    event = line[6:].strip()
                elif line.startswith('data:'):
                    data.append(line[5:].lstrip())
        raise RuntimeError('SSE 提前结束，未收到终帧')


def evaluate(client, include_long_history):
    prefix = 'AgentEval-' + uuid.uuid4().hex[:10]
    fixture = client.api('POST', '/tasks', {'title': prefix + '-fixture'})
    sessions, report = [], []
    cases = [
        ('任务查询', f'查找标题包含 {prefix}-fixture 的任务，必须查询真实数据。', {'findTasks', 'getTask'}, 'findTasks'),
        ('日程查询', '查询今天的日程安排，必须使用日程查询工具。', {'findCalendarEvents'}, 'findCalendarEvents'),
        ('知识检索', f'检索个人知识库中 {prefix} 相关资料。', {'searchKnowledge'}, 'searchKnowledge'),
        ('闲聊不调用工具', '你好，请只回答你好，不需要操作或查询任何数据。', set(), None),
        ('危险操作先确认', f'申请删除任务 {fixture["id"]}，名称是 {prefix}-fixture。', {'deleteTask', 'getTask', 'findTasks'}, 'deleteTask'),
        ('任务实际落库', f'创建一条任务，标题必须精确为 {prefix}-created，不设置截止时间。', {'createTask'}, 'createTask'),
    ]
    try:
        for name, prompt, allowed, required in cases:
            session = client.api('POST', '/chat/sessions', {'title': prefix + '-' + name})['conversationId']
            sessions.append(session)
            start = time.monotonic()
            row = {'name': name, 'passed': False}
            try:
                events = client.chat(session, prompt)
                records = client.api('GET', '/agent/tool-invocations?' + urlencode({'conversationId': session, 'size': 100}))['items']
                calls = [r for r in records if r['operation'] not in {'createPlan', 'getPlan', 'startPlanStep', 'completePlanStep', 'failPlanStep', 'skipPlanStep', 'revisePlan'}]
                selected = {r['operation'] for r in calls}
                passed = events[-1][0] == 'done' and selected <= allowed
                passed &= required is None or required in selected
                if required is None:
                    passed &= len(records) == 0
                passed &= not any(r['outcome'] in ('FAILED', 'REJECTED') for r in records)
                if name == '危险操作先确认':
                    client.api('GET', '/tasks/' + fixture['id'])
                    passed &= any(r['operation'] == 'deleteTask' and r['outcome'] == 'REQUIRES_CONFIRMATION' for r in records)
                if name == '任务实际落库':
                    tasks = client.api('GET', '/tasks?' + urlencode({'keyword': prefix + '-created', 'size': 100}))['items']
                    passed &= sum(t['title'] == prefix + '-created' for t in tasks) == 1
                row.update(passed=bool(passed), selectedTools=sorted(selected), toolCalls=len(records),
                           failedCalls=sum(r['outcome'] in ('FAILED', 'REJECTED') for r in records),
                           promptTokens=events[-1][1].get('promptTokens'), terminalEvent=events[-1][0],
                           turnIds=sorted({r['turnId'] for r in records if r.get('turnId')}))
            except Exception as error:
                row['errorType'] = type(error).__name__  # 不把密钥或上游原始报文写入报告。
            row['seconds'] = round(time.monotonic() - start, 3)
            report.append(row)
        if include_long_history:
            session = client.api('POST', '/chat/sessions', {'title': prefix + '-长历史'})['conversationId']
            sessions.append(session)
            code = uuid.uuid4().hex[:12]
            start = time.monotonic()
            try:
                for prompt in [f'本对话测试代号是 {code}。只回答收到，不要调用工具。'] + [
                    f'第{i}轮背景内容：' + '这是与代号无关的临时测试材料。' * 30 + '只回答收到，不要调用工具。' for i in range(12)
                ]:
                    events = client.chat(session, prompt)
                    if events[-1][0] != 'done':
                        raise RuntimeError('长历史预热失败')
                events = client.chat(session, '最开始告诉你的测试代号是什么？只回答代号。')
                report.append({'name': '长历史事实保留', 'passed': events[-1][0] == 'done' and code in events[-1][1].get('content', ''),
                               'seconds': round(time.monotonic() - start, 3)})
            except Exception as error:
                report.append({'name': '长历史事实保留', 'passed': False, 'errorType': type(error).__name__,
                               'seconds': round(time.monotonic() - start, 3)})
    finally:
        # 各清理动作独立执行；后端失败也要留下报告，不能掩盖评测结果。
        cleanup_errors = []
        for session in sessions:
            try:
                confirmations = client.api('GET', '/agent/confirmations?' + urlencode({'conversationId': session, 'status': 'PENDING'}))
                for c in confirmations:
                    try:
                        client.api('POST', f'/agent/confirmations/{c["id"]}/cancel', {'token': c['token']})
                    except Exception as error:
                        cleanup_errors.append(type(error).__name__)
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
            try:
                client.api('DELETE', '/chat/sessions/' + session)
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
        try:
            tasks = client.api('GET', '/tasks?' + urlencode({'keyword': prefix, 'size': 100}))['items']
            for task in tasks:
                if task['title'] in {prefix + '-fixture', prefix + '-created'}:
                    try:
                        client.api('DELETE', '/tasks/' + task['id'])
                    except Exception as error:
                        cleanup_errors.append(type(error).__name__)
        except Exception as error:
            cleanup_errors.append(type(error).__name__)
        if cleanup_errors:
            report.append({'name': '清理测试数据', 'passed': False, 'errorTypes': cleanup_errors, 'seconds': 0})
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080/api/v1')
    parser.add_argument('--output', default='/tmp/personal-ai-os-agent-eval.json')
    parser.add_argument('--long-history', action='store_true', help='额外调用模型评估跨窗口事实保留')
    args = parser.parse_args()
    rows = evaluate(Client(args.base_url), args.long_history)
    latencies = sorted(r['seconds'] for r in rows if r['name'] != '长历史事实保留')
    result = {'cases': rows, 'passed': sum(r['passed'] for r in rows), 'total': len(rows),
              'p50Seconds': latencies[math.ceil(len(latencies) * .5) - 1],
              'p95Seconds': latencies[math.ceil(len(latencies) * .95) - 1],
              'promptTokens': sum(r.get('promptTokens') or 0 for r in rows),
              'notes': 'promptTokens 以服务端返回为准，可能是估算；未计摘要与回复 token。单轮延迟仅作基线，不等于压测。'}
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(f'{result["passed"]}/{len(rows)} 通过；报告：{args.output}')
    return 0 if result['passed'] == len(rows) else 1


if __name__ == '__main__':
    raise SystemExit(main())
