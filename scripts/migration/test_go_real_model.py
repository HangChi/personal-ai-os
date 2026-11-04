import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock
import urllib.error
import urllib.request

from go_real_model import ModelConfig, ModelFailure, NoRedirect, RealModel, load_config, serve


class RealModelTest(unittest.TestCase):
    def config(self):
        return ModelConfig("https://models.example.invalid/v1", "test-only-secret", "chat-test", "embed-test", False)

    def response(self, value):
        response = Mock()
        response.status = 200
        response.read.return_value = json.dumps(value).encode()
        context = Mock()
        context.__enter__ = Mock(return_value=response)
        context.__exit__ = Mock(return_value=False)
        return context

    def embeddings(self, index=0, vector=None):
        return {"data": [{"index": index, "embedding": vector if vector is not None else [0.5] * 1024}],
                "usage": {"prompt_tokens": 3, "total_tokens": 3}}

    def test_explicit_configuration_allowlist_precedence_and_redaction(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary).resolve() / "model.env"
            path.write_text("export AI_CHAT_PROVIDER=openai\r\nAI_EMBEDDING_PROVIDER=openai\n"
                            "OPENAI_BASE_URL='https://models.example.invalid/v1'\n"
                            "OPENAI_API_KEY=test-only-secret\nOPENAI_CHAT_MODEL=chat-test\n"
                            "OPENAI_EMBEDDING_MODEL=embed-test\nPOSTGRES_PASSWORD=never-copy\n"
                            "OPENAI_CHAT_MODEL=\"last-chat\"\nOPENAI_ENABLE_THINKING=false\n")
            config = load_config(path, {"OPENAI_CHAT_MODEL": "override-chat", "AUTH_PASSWORD": "never-copy"})
            self.assertEqual(config.chat_model, "override-chat")
            self.assertEqual(config.embedding_model, "embed-test")
            self.assertNotIn(config.api_key, repr(config) + json.dumps(config.metadata()))
            self.assertNotIn("never-copy", repr(config) + json.dumps(config.metadata()))
            with self.assertRaises(ModelFailure):
                load_config(path, {"OPENAI_API_KEY": ""})
            link = path.with_name("link")
            link.symlink_to(path)
            with self.assertRaises(ModelFailure):
                load_config(link, {})

    def test_invalid_configurations_never_fall_back_to_a_default_model(self):
        base = ("AI_CHAT_PROVIDER=openai\nAI_EMBEDDING_PROVIDER=openai\nOPENAI_BASE_URL=https://models.example.invalid\n"
                "OPENAI_API_KEY=test-only-secret\nOPENAI_CHAT_MODEL=chat-test\nOPENAI_EMBEDDING_MODEL=embed-test\n")
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary).resolve() / "model.env"
            for extra in ("OPENAI_BASE_URL=http://models.example.invalid\n",
                          "OPENAI_BASE_URL=https://test-only-secret@models.example.invalid\n",
                          "OPENAI_BASE_URL=https://models.example.invalid?key=test-only-secret\n",
                          "AI_EMBEDDING_PROVIDER=none\n", "OPENAI_CHAT_MODEL=\n",
                          "OPENAI_ENABLE_THINKING=maybe\n"):
                path.write_text(base + extra)
                with self.subTest(extra=extra), self.assertRaises(ModelFailure) as error:
                    load_config(path, {})
                self.assertNotIn("test-only-secret", str(error.exception))

    def test_real_forwarding_receipts_only_after_valid_response_and_no_text_in_metadata(self):
        opener = Mock()
        opener.open.return_value = self.response(self.embeddings())
        model = RealModel(self.config(), opener)
        result = model.forward("/v1/embeddings", {"model": "embed-test", "input": ["actual-public-text"]})
        self.assertEqual(len(result["data"][0]["embedding"]), 1024)
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, "https://models.example.invalid/v1/embeddings")
        self.assertEqual(request.headers["Authorization"], "Bearer test-only-secret")
        self.assertEqual(opener.open.call_args.kwargs["timeout"], 25)
        self.assertEqual(sum(model.embedding_receipts().values()), 1)
        self.assertEqual(model.snapshot()["usage"]["total_tokens"], 3)
        self.assertNotIn("actual-public-text", json.dumps(model.snapshot()))
        self.assertNotIn("test-only-secret", repr(model) + json.dumps(model.snapshot()))

    def test_bad_embedding_dimensions_nonfinite_zero_index_or_count_fail_closed(self):
        for result in (self.embeddings(vector=[0.5]), self.embeddings(vector=[0.0] * 1024),
                       self.embeddings(vector=[float("nan")] * 1024), self.embeddings(index=1), {"data": []}):
            opener = Mock()
            opener.open.return_value = self.response(result)
            model = RealModel(self.config(), opener)
            with self.subTest(result_kind=list(result)), self.assertRaises(ModelFailure):
                model.forward("/v1/embeddings", {"model": "embed-test", "input": ["actual-text"]})
            self.assertEqual(sum(model.embedding_receipts().values()), 0)

    def test_upstream_failure_is_not_retried_and_response_secrets_are_not_reported(self):
        opener = Mock()
        opener.open.side_effect = urllib.error.HTTPError("https://example.invalid", 401, "test-only-secret", {},
                                                        io.BytesIO(b"test-only-secret actual-text"))
        model = RealModel(self.config(), opener)
        with self.assertRaisesRegex(ModelFailure, "^UPSTREAM_HTTP_401$") as error:
            model.forward("/v1/embeddings", {"model": "embed-test", "input": ["actual-text"]})
        self.assertEqual(opener.open.call_count, 1)
        self.assertNotIn("test-only-secret", str(error.exception) + json.dumps(model.snapshot()))

    def test_request_character_and_chat_budgets_reject_before_network(self):
        for field in ("max_calls", "max_characters", "max_chat_calls"):
            opener = Mock()
            model = RealModel(self.config(), opener)
            setattr(model, field, 0)
            path = "/v1/chat/completions" if field == "max_chat_calls" else "/v1/embeddings"
            payload = ({"model": "chat-test", "messages": [{"role": "user", "content": "hello"}]}
                       if field == "max_chat_calls" else {"model": "embed-test", "input": ["hello"]})
            with self.subTest(field=field), self.assertRaisesRegex(ModelFailure, "BUDGET"):
                model.forward(path, payload)
            opener.open.assert_not_called()

    def test_provider_rejection_keeps_only_allowed_codes_counts_and_numeric_limits(self):
        for code in ("InvalidParameter", "test-only-secret"):
            body = json.dumps({"error": {"code": code, "message":
                "batch size must not be larger than 25; test-only-secret actual-text"}}).encode()
            opener = Mock()
            opener.open.side_effect = urllib.error.HTTPError("https://example.invalid", 400, "raw", {},
                                                            io.BytesIO(body))
            model = RealModel(self.config(), opener)
            with self.assertRaises(ModelFailure):
                model.forward("/v1/embeddings", {"model": "embed-test", "input": ["actual-text"] * 31})
            report = model.snapshot()
            self.assertEqual(report["last_upstream_rejection"]["input_count"], 31)
            self.assertEqual(report["last_upstream_rejection"]["batch_limits"], [25])
            self.assertEqual(report["last_upstream_rejection"]["code"],
                             "InvalidParameter" if code == "InvalidParameter" else "UNSPECIFIED")
            self.assertNotIn("test-only-secret", json.dumps(report))
            self.assertNotIn("actual-text", json.dumps(report))

    def test_rejects_routes_wrong_model_tools_streams_and_remote_images(self):
        model = RealModel(self.config(), Mock())
        for path, payload in (("/v1/other", {}), ("/v1/embeddings", {"model": "wrong", "input": ["x"]}),
                              ("/v1/chat/completions", {"model": "chat-test", "messages": [], "stream": True}),
                              ("/v1/chat/completions", {"model": "chat-test", "messages": [], "tools": ["x"]}),
                              ("/v1/chat/completions", {"model": "chat-test", "messages": [{"role": "user", "content": []}]})):
            with self.subTest(path=path), self.assertRaises(ModelFailure):
                model.forward(path, payload)
        model.opener.open.assert_not_called()

    def test_chat_is_real_bounded_and_requires_nonempty_content(self):
        opener = Mock()
        opener.open.return_value = self.response({"choices": [{"message": {"content": "actual reply"}}]})
        model = RealModel(self.config(), opener)
        model.forward("/v1/chat/completions", {"model": "chat-test", "max_tokens": 8000,
                      "messages": [{"role": "user", "content": "hello"}]})
        payload = json.loads(opener.open.call_args.args[0].data)
        self.assertEqual(payload["max_tokens"], 1024)
        self.assertFalse(payload["enable_thinking"])
        opener.open.return_value = self.response({"choices": [{"message": {"content": ""}}]})
        with self.assertRaises(ModelFailure):
            model.forward("/v1/chat/completions", {"model": "chat-test", "messages": [{"role": "user", "content": "x"}]})

    def test_loopback_proxy_requires_its_own_token_and_never_redirects_real_credentials(self):
        opener = Mock()
        opener.open.return_value = self.response(self.embeddings())
        model = RealModel(self.config(), opener)
        local = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with serve(model) as values:
            self.assertNotEqual(values["OPENAI_API_KEY"], model.config.api_key)
            url = values["OPENAI_BASE_URL"] + "/v1/embeddings"
            body = json.dumps({"model": "embed-test", "input": ["actual-text"]}).encode()
            with self.assertRaises(urllib.error.HTTPError) as error:
                local.open(urllib.request.Request(url, body), timeout=2)
            self.assertEqual(error.exception.code, 502)
            error.exception.close()
            opener.open.assert_not_called()
            request = urllib.request.Request(url, body, {"Authorization": "Bearer " + values["OPENAI_API_KEY"]})
            with local.open(request, timeout=2) as response:
                self.assertEqual(len(json.load(response)["data"][0]["embedding"]), 1024)
            self.assertEqual(opener.open.call_count, 1)
        self.assertIsNone(NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.invalid"))


if __name__ == "__main__":
    unittest.main()
