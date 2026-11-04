import copy
import io
import json
import unittest
from unittest.mock import Mock
import urllib.request

from go_real_agent_model import AgentModel
from go_real_model import ModelConfig, ModelFailure, RealModel, serve
from run_go_original_eval_tests import AgentClient, frozen_module


class OriginalEvalTest(unittest.TestCase):
    def setUp(self):
        self.config = ModelConfig("https://model.example.invalid/v1", "test-only-secret", "chat-test", "embed-test", False)
        self.opener = Mock()
        self.model = AgentModel(self.config, {"findTasks"}, self.opener)
        self.call = dict(id="call-test", type="function", function=dict(name="findTasks", arguments='{}'))
        self.payload = dict(model="chat-test", messages=[dict(role="user", content="read tasks")],
                            tools=[dict(type="function", function=dict(name="findTasks", parameters={"type": "object"}))])
        self.result = dict(id="response-test", model="chat-test", choices=[dict(index=0,
            message=dict(role="assistant", content=None, tool_calls=[self.call]), finish_reason="tool_calls")],
            usage=dict(prompt_tokens=99, completion_tokens=10, total_tokens=109))
        self.opener.open.side_effect = lambda *args, **kwargs: self.response()

    def response(self):
        value = io.BytesIO(json.dumps(self.result).encode())
        value.status = 200
        return value

    def test_full_tool_protocol_reaches_real_transport_and_keeps_caller_unchanged(self):
        payload = copy.deepcopy(self.payload)
        payload.update(stream=True, stream_options={"include_usage": True})
        result = self.model.forward("/v1/chat/completions", payload)
        sent = json.loads(self.opener.open.call_args.args[0].data)
        self.assertFalse(sent["stream"])
        self.assertNotIn("stream_options", sent)
        self.assertTrue(payload["stream"])
        self.assertEqual(result, self.result)
        self.assertEqual(sent["tools"], payload["tools"])
        self.assertEqual(self.model.snapshot()["usage"]["total_tokens"], 109)
        self.assertNotIn("read tasks", json.dumps(self.model.snapshot()))

    def test_linked_assistant_and_tool_result_history_is_forwarded(self):
        payload = copy.deepcopy(self.payload)
        payload["messages"] += [dict(role="assistant", content=None, tool_calls=[self.call]),
                                dict(role="tool", tool_call_id="call-test", content="owned result")]
        self.model.forward("/chat/completions", payload)
        self.assertEqual(json.loads(self.opener.open.call_args.args[0].data)["messages"], payload["messages"])

    def test_java_sdk_boolean_stream_options_are_supported_without_forwarding(self):
        for flag in (True, False):
            payload = dict(self.payload, stream=True,
                           stream_options={"include_usage": True, "include_obfuscation": flag})
            self.model.forward("/chat/completions", payload)
            sent = json.loads(self.opener.open.call_args.args[0].data)
            self.assertNotIn("stream_options", sent)
            self.assertFalse(sent["stream"])
            self.assertEqual(payload["stream_options"]["include_obfuscation"], flag)

    def test_invalid_stream_options_are_rejected_with_value_free_diagnostics(self):
        for options in ({"include_usage": "secret-request-value"}, {"unknown_option": True}, None):
            with self.assertRaisesRegex(ModelFailure, "AGENT_CHAT_REQUEST_INVALID"):
                self.model.forward("/chat/completions", dict(self.payload, stream_options=options))
            encoded = json.dumps(self.model.snapshot())
            self.assertNotIn("read tasks", encoded)
            self.assertNotIn("secret-request-value", encoded)
        self.opener.open.assert_not_called()

    def test_unknown_tools_or_unlinked_results_never_reach_network(self):
        variants = []
        payload = copy.deepcopy(self.payload)
        payload["tools"][0]["function"]["name"] = "unregisteredDangerousTool"
        variants.append(payload)
        payload = copy.deepcopy(self.payload)
        payload["messages"].append(dict(role="tool", tool_call_id="missing", content="result"))
        variants.append(payload)
        payload = copy.deepcopy(self.payload)
        payload["messages"].append(dict(role="assistant", content=None, tool_calls=[self.call]))
        variants.append(payload)
        for payload in variants:
            with self.assertRaises(ModelFailure):
                self.model.forward("/v1/chat/completions", payload)
        self.opener.open.assert_not_called()

    def test_default_gateway_still_rejects_agent_tools_and_streaming(self):
        for extra in ({}, {"stream": True}):
            with self.assertRaisesRegex(ModelFailure, "CHAT_REQUEST_INVALID"):
                RealModel(self.config, self.opener).forward("/v1/chat/completions", dict(self.payload, **extra))
        self.opener.open.assert_not_called()

    def test_sse_contains_exact_upstream_tool_calls_finish_reason_and_usage(self):
        self.payload["stream"] = True
        with serve(self.model) as values:
            request = urllib.request.Request(values["OPENAI_BASE_URL"] + "/v1/chat/completions",
                json.dumps(self.payload).encode(), {"Authorization": "Bearer " + values["OPENAI_API_KEY"]})
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(request, timeout=3) as response:
                self.assertIn("text/event-stream", response.headers["Content-Type"])
                parts = response.read().decode().split("\n\n")
        chunks = [json.loads(part[6:]) for part in parts if part.startswith("data: {")]
        tool = chunks[0]["choices"][0]["delta"]["tool_calls"][0]
        self.assertEqual(tool, dict(self.call, index=0))
        self.assertEqual(chunks[1]["choices"][0]["finish_reason"], "tool_calls")
        self.assertEqual(chunks[2]["usage"], self.result["usage"])
        self.assertIn("data: [DONE]", parts)

    def test_agent_api_adapter_uses_original_creation_statuses(self):
        client = Mock()
        adapter = AgentClient(client, self.model, None)
        adapter.api("POST", "/tasks", {"title": "fixture"})
        self.assertEqual(client.request.call_args.kwargs["status"], 201)
        adapter.api("POST", "/chat/sessions", {})
        self.assertEqual(client.request.call_args.kwargs["status"], 201)
        adapter.api("DELETE", "/tasks/owned")
        self.assertEqual(client.request.call_args.kwargs["status"], 200)

    def test_only_frozen_original_evaluation_modules_can_be_loaded(self):
        for name in ("agent-eval.py", "rag-live-eval.py"):
            module, hashes = frozen_module(name)
            self.assertTrue(callable(module.evaluate))
            self.assertEqual(len(hashes), 2 if name.startswith("rag") else 1)
        with self.assertRaises(ValueError):
            frozen_module("arbitrary.py")


if __name__ == "__main__":
    unittest.main()
