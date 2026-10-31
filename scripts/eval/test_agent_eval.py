"""验证评测器不会仅凭模型声称成功就给出通过，并且只清理本轮测试数据。"""
import importlib.util
from pathlib import Path
import re
import unittest
from urllib.parse import urlparse, parse_qs

spec = importlib.util.spec_from_file_location('agent_eval', Path(__file__).with_name('agent-eval.py'))
eval_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(eval_module)


class FakeClient:
    def __init__(self, persist=True, wrong_tool=False):
        self.persist = persist
        self.wrong_tool = wrong_tool
        self.tasks = {'user-task': {'id': 'user-task', 'title': '用户原有任务'}}
        self.sessions = {}
        self.deleted = []

    def api(self, method, path, body=None):
        url = urlparse(path)
        if method == 'POST' and path == '/tasks':
            task = {'id': 'fixture', 'title': body['title']}
            self.tasks[task['id']] = task
            return task
        if method == 'POST' and path == '/chat/sessions':
            sid = str(len(self.sessions))
            self.sessions[sid] = []
            return {'conversationId': sid}
        if method == 'GET' and url.path == '/agent/tool-invocations':
            return {'items': self.sessions[parse_qs(url.query)['conversationId'][0]]}
        if method == 'GET' and url.path == '/tasks':
            keyword = parse_qs(url.query)['keyword'][0]
            return {'items': [t for t in self.tasks.values() if keyword in t['title']]}
        if method == 'GET' and path.startswith('/tasks/'):
            return self.tasks[path.rsplit('/', 1)[1]]
        if method == 'GET' and url.path == '/agent/confirmations':
            return []
        if method == 'DELETE':
            key = path.rsplit('/', 1)[1]
            self.deleted.append(path)
            (self.tasks if path.startswith('/tasks/') else self.sessions).pop(key)
            return None
        raise AssertionError((method, path))

    def chat(self, sid, prompt):
        if '检索个人知识库' in prompt:
            tool = 'searchKnowledge'
        elif '查询今天的日程' in prompt:
            tool = 'findTasks' if self.wrong_tool else 'findCalendarEvents'
        elif '申请删除任务' in prompt:
            tool = 'deleteTask'
        elif '创建一条任务' in prompt:
            tool = 'createTask'
            if self.persist:
                title = re.search(r'精确为 (AgentEval-[a-f0-9]+-created)', prompt).group(1)
                self.tasks['created'] = {'id': 'created', 'title': title}
        elif '查找标题' in prompt:
            tool = 'findTasks'
        else:
            tool = None
        self.sessions[sid] = [] if tool is None else [
            {'operation': tool, 'outcome': 'REQUIRES_CONFIRMATION' if tool == 'deleteTask' else 'SUCCESS', 'turnId': sid}]
        return [('done', {'content': '我已经处理好了', 'promptTokens': 10})]


class AgentEvaluationTest(unittest.TestCase):
    def test_checks_actions_and_cleans_only_own_fixtures(self):
        client = FakeClient()
        rows = eval_module.evaluate(client, False)
        self.assertEqual(6, len(rows))
        self.assertTrue(all(r['passed'] for r in rows))
        self.assertEqual(['user-task'], list(client.tasks))
        self.assertEqual({}, client.sessions)

    def test_claimed_success_without_database_write_fails(self):
        rows = eval_module.evaluate(FakeClient(persist=False), False)
        self.assertFalse(next(r for r in rows if r['name'] == '任务实际落库')['passed'])

    def test_wrong_tool_is_detected(self):
        rows = eval_module.evaluate(FakeClient(wrong_tool=True), False)
        self.assertFalse(next(r for r in rows if r['name'] == '日程查询')['passed'])


if __name__ == '__main__':
    unittest.main()
