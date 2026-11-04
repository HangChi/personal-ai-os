import json
import unittest
from p8_model_fixture import answer, choose


class P8ModelFixtureTest(unittest.TestCase):
    def test_plan_advances_only_successful_receipts_and_counts_actual_tasks(self):
        user = {"role": "user", "content": "计划：1) 查看任务；2) 统计优先级；3) 给出建议。请先 createPlan。"}
        steps = [{"seq": seq, "title": title, "status": "PENDING"} for seq, title in enumerate(
            ["查看任务", "统计优先级", "给出建议"], 1)]
        plan = {"status": "ACTIVE", "steps": steps}

        def receipt(name, data, status="OK"):
            return {"role": "tool", "name": name, "content": json.dumps({"status": status, "data": data})}

        history = [user, receipt("createPlan", plan)]
        self.assertEqual(choose(history), ("startPlanStep", {"seq": 1}))
        steps[0]["status"] = "RUNNING"
        history.append(receipt("startPlanStep", plan))
        self.assertEqual(choose(history), ("findTasks", {"limit": 100}))
        history.append(receipt("findTasks", [{"priority": "HIGH"}, {"priority": "HIGH"}, {"priority": "LOW"}]))
        self.assertEqual(choose(history)[0], "completePlanStep")
        steps[0]["status"] = "DONE"
        steps[1]["status"] = "RUNNING"
        history.append(receipt("startPlanStep", plan))
        self.assertIn('"HIGH": 2', choose(history)[1]["summary"])
        self.assertIn('"MEDIUM": 0', choose(history)[1]["summary"])
        self.assertIsNone(choose(history + [receipt("findTasks", [], "ERROR")]))
        steps[1]["status"] = "DONE"
        steps[2]["status"] = "RUNNING"
        history.append(receipt("startPlanStep", plan))
        self.assertIn("2 个HIGH", choose(history)[1]["summary"])
        steps[2]["status"] = "DONE"
        plan["status"] = "COMPLETED"
        history.append(receipt("completePlanStep", plan))
        self.assertIsNone(choose(history))

    def test_plan_uses_request_steps_once_and_requires_explicit_tool_request(self):
        user = {"role": "user", "content": "请制定计划：1) 获取清单；2) 核对截止日期。请先 createPlan。"}
        self.assertEqual(choose([user]), ("createPlan", {
            "goal": "请制定计划", "steps": ["获取清单", "核对截止日期"]}))
        self.assertIsNone(choose([user, {"role": "tool", "content": "actual plan receipt"}]))
        self.assertIsNone(choose([{"role": "user", "content": "1) 获取清单；2) 核对日期"}]))
        self.assertIsNone(choose([{"role": "user", "content": "请先 createPlan"}]))

    def test_answer_depends_on_actual_history_and_retrieved_content(self):
        user = {"role": "user", "content": "我刚才让你记住的数字是多少？"}
        self.assertNotIn("7741", answer([user]))
        self.assertEqual(answer([{"role": "user", "content": "请记住这个数字：8142"}, user]), "8142")
        self.assertIn("知识库没有", answer([{"role": "user", "content": "内部代号？"}]))
        self.assertIn("Fixture-123", answer([{"role": "system", "content": "内部代号是 Fixture-123"}, {"role": "user", "content": "内部代号？"}]))

    def test_wrapped_question_and_dynamic_context_do_not_supply_missing_answers(self):
        self.assertIn("没有", answer([{"role": "user", "content": "【资料】内部代号是 Example-456【问题】量子引力的温度？"}]))
        for question, fact, expected in (
            ("我常用的编程语言是什么？", "主用编程语言是 Kotlin", "Kotlin"),
            ("这个附件的结论是什么？", "本次评审的结论是推迟三天。", "推迟三天"),
        ):
            user = {"role": "user", "content": question}
            self.assertNotIn(expected, answer([user]))
            self.assertIn(expected, answer([{"role": "system", "content": fact}, user]))

    def test_destructive_tool_uses_only_ids_returned_by_actual_find(self):
        user = {"role": "user", "content": "删除标题为「synthetic target」的任务"}
        self.assertEqual(choose([user]), ("findTasks", {"keyword": "synthetic target"}))
        self.assertIsNone(choose([user, {"role": "tool", "content": "[]"}]))
        result = {"role": "tool", "content": '[{"id":"actual-id","title":"synthetic target"}]'}
        self.assertEqual(choose([user, result]), ("deleteTask", {"taskIds": ["actual-id"]}))


if __name__ == "__main__":
    unittest.main()
