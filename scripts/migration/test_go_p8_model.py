import unittest
from p8_model_fixture import answer, choose


class P8ModelFixtureTest(unittest.TestCase):
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
