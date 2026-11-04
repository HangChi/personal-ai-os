import base64
import copy
import json
import struct
import unittest
from unittest.mock import Mock

from go_real_model import ModelConfig, ModelFailure
from run_go_paired_eval_tests import QueryReplay, paired_comparison


class PairedEvaluationTest(unittest.TestCase):
    def model(self):
        model = Mock()
        model.config = ModelConfig("https://models.example.invalid", "test-only-secret", "chat", "embed", False)
        model.forward.return_value = dict(data=[dict(index=0, embedding=base64.b64encode(
            struct.pack("<1024f", *([0.5] * 1024))).decode())])
        return model

    def payload(self, text="original query", encoding="base64"):
        return dict(model="embed", input=[text], encoding_format=encoding)

    def test_replays_the_recorded_real_float32_values_without_another_upstream_call(self):
        model = self.model()
        replay = QueryReplay(model, ["original query"])
        replay.begin_recording()
        replay.forward("/embeddings", self.payload())
        replay.begin_replay()
        answer = replay.forward("/v1/embeddings", self.payload(encoding="float"))
        self.assertEqual(answer["data"][0]["embedding"], [0.5] * 1024)
        self.assertEqual(model.forward.call_count, 1)
        self.assertEqual(replay.finish()["replayed_query_vectors"], 1)
        public = json.dumps(replay.finish())
        self.assertNotIn("original query", public)
        self.assertNotIn("test-only-secret", public)

    def test_rejects_different_input_order_models_dimensions_tools_and_routes(self):
        for field in ("input", "model", "dimensions", "tools", "route"):
            model = self.model()
            replay = QueryReplay(model, ["original query"])
            replay.begin_recording()
            payload, path = self.payload(), "/embeddings"
            if field == "input": payload["input"] = ["changed query"]
            elif field == "model": payload["model"] = "another-model"
            elif field == "dimensions": payload["dimensions"] = 512
            elif field == "tools": payload["tools"] = []
            else: path = "/v1/chat/completions"
            with self.subTest(field=field), self.assertRaises(ModelFailure):
                replay.forward(path, payload)
            model.forward.assert_not_called()

    def test_incomplete_recording_and_replay_cannot_finish_or_fall_back_to_upstream(self):
        replay = QueryReplay(self.model(), ["original query", "second query"])
        replay.begin_recording()
        replay.forward("/embeddings", self.payload())
        with self.assertRaises(ModelFailure): replay.begin_replay()
        with self.assertRaises(ModelFailure): replay.finish()
        replay.forward("/embeddings", self.payload("second query"))
        replay.begin_replay()
        replay.forward("/v1/embeddings", self.payload(encoding="float"))
        with self.assertRaises(ModelFailure): replay.finish()
        with self.assertRaises(ModelFailure):
            replay.forward("/v1/embeddings", self.payload("unexpected", "float"))
        self.assertEqual(replay.upstream.forward.call_count, 2)

    def test_invalid_vector_or_upstream_failure_is_never_recorded_or_retried(self):
        for value in ([], [0.0] * 1024, [float("inf")] * 1024, "invalid-base64"):
            model = self.model()
            model.forward.return_value = dict(data=[dict(index=0, embedding=value)])
            replay = QueryReplay(model, ["original query"])
            replay.begin_recording()
            with self.subTest(value_type=type(value).__name__), self.assertRaises(ModelFailure):
                replay.forward("/embeddings", self.payload())
            self.assertEqual(model.forward.call_count, 1)
            with self.assertRaises(ModelFailure): replay.begin_replay()
        model = self.model()
        model.forward.side_effect = ModelFailure("UPSTREAM_HTTP_429")
        replay = QueryReplay(model, ["original query"])
        replay.begin_recording()
        with self.assertRaisesRegex(ModelFailure, "UPSTREAM_HTTP_429"):
            replay.forward("/embeddings", self.payload())
        self.assertEqual(model.forward.call_count, 1)

    def test_comparison_does_not_claim_independent_live_calls_or_approve_quality(self):
        rows = [dict(query="q", docs=["expected"], titles=["expected"], rank=1, tied_rank=1)]
        backends = {backend: dict(query_results=copy.deepcopy(rows), metrics=dict(
            recall_at_1=1, recall_at_3=1, recall_at_5=1, mrr=1)) for backend in ("java", "go")}
        result = paired_comparison(backends)
        self.assertTrue(result["all_original_rankings_identical"])
        self.assertFalse(result["live_upstream_calls_independent"])
        self.assertFalse(result["release_gate_approved"])
        self.assertFalse(result["semantic_quality_verified"])
        backends["go"]["query_results"][0]["titles"] = ["different"]
        self.assertFalse(paired_comparison(backends)["all_original_rankings_identical"])


if __name__ == "__main__":
    unittest.main()
