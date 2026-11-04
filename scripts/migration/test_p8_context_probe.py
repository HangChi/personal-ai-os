import copy
import types
import unittest

from p8_context_probe import RequestObservations, digest, require_rejection, require_trimmed


class ContextProbeTest(unittest.TestCase):
    def test_observations_are_bounded_detached_hashes_without_prompt_text(self):
        observer = RequestObservations()
        request = dict(messages=[dict(role="system", content="private system"),
                                 dict(role="user", content="synthetic input")],
                       tools=[dict(function=dict(name="fixture"))], stream=True)
        observer(request)
        result = observer.snapshot()
        self.assertEqual(result[0]["user_sha256"], [digest("synthetic input")])
        self.assertNotIn("private", str(result))
        self.assertNotIn("synthetic input", str(result))
        result[0]["user_sha256"].append("mutation")
        self.assertEqual(len(observer.snapshot()[0]["user_sha256"]), 1)
        for _ in range(31):
            observer(request)
        with self.assertRaises(AssertionError):
            observer(request)

    def test_rejection_requires_local_error_no_output_and_no_model_request(self):
        values = dict(error=dict(code=41301))
        stream = types.SimpleNamespace(first=lambda name: values.get(name))
        self.assertEqual(require_rejection(stream, 0)["upstream_calls"], 0)
        for wrong in (dict(error=dict(code=50000)), dict(values, done={}), dict(values, delta="text")):
            with self.subTest(wrong=wrong), self.assertRaises(AssertionError):
                require_rejection(types.SimpleNamespace(first=lambda name: wrong.get(name)), 0)
        with self.assertRaises(AssertionError):
            require_rejection(stream, 1)

    def test_trimming_requires_exact_current_input_order_and_persisted_history(self):
        history, current = ["oldest", "middle", "recent"], "current"
        persisted = [dict(role="USER", content=value) for value in history + [current]]
        observation = dict(user_sha256=[digest("recent"), digest(current)], stream=True, tools=1)
        result = require_trimmed(observation, history, current, persisted)
        self.assertEqual(result["removed_from_request"], 2)
        for wrong in ([digest(current)], [digest("middle"), digest(current)],
                      [digest(current), digest("recent")], [digest(value) for value in history + [current]],
                      [digest("foreign"), digest(current)]):
            if wrong == [digest(current)]:
                self.assertTrue(require_trimmed(dict(observation, user_sha256=wrong), history, current, persisted)["ordered_suffix"])
                continue
            with self.subTest(wrong=wrong), self.assertRaises(AssertionError):
                require_trimmed(dict(observation, user_sha256=wrong), history, current, persisted)
        altered = copy.deepcopy(persisted)
        altered[0]["content"] = "changed"
        with self.assertRaises(AssertionError):
            require_trimmed(observation, history, current, altered)
        with self.assertRaises(AssertionError):
            require_trimmed(dict(observation, tools=0), history, current, persisted)


if __name__ == "__main__":
    unittest.main()
