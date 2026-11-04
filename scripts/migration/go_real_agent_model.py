"""Opt-in real Agent protocol; no invented model text, tools, or usage."""
import json

from go_real_model import ModelFailure, RealModel


class AgentModel(RealModel):
    def __init__(self, config, allowed_tools, opener=None):
        super().__init__(config, opener)
        self.allowed_tools = frozenset(allowed_tools)
        self.last_rejected_agent_shape = None
        if not self.allowed_tools:
            raise ValueError("Explicit registered Agent tools are required")

    def snapshot(self):
        result = super().snapshot()
        with self.lock:
            result["last_rejected_agent_shape"] = self.last_rejected_agent_shape
        return result

    def prepare_chat(self, payload):
        try:
            return self.prepare_agent_chat(payload)
        except ModelFailure:
            # Retain field names and types only, never values or messages.
            def shape(value):
                return {key if key.isidentifier() and key.isascii() and len(key) <= 48 else "OTHER": type(item).__name__
                        for key, item in value.items()} if isinstance(value, dict) else type(value).__name__
            with self.lock:
                self.last_rejected_agent_shape = dict(fields=shape(payload),
                    stream_options=shape(payload.get("stream_options")))
            raise

    def validate_calls(self, calls):
        if not isinstance(calls, list) or not 1 <= len(calls) <= 16:
            raise ModelFailure("AGENT_TOOL_CALLS_INVALID")
        ids = set()
        for call in calls:
            if not isinstance(call, dict) or not set(call) <= {"id", "type", "function", "index"}:
                raise ModelFailure("AGENT_TOOL_CALL_INVALID")
            identity, function = call.get("id"), call.get("function")
            if (not isinstance(identity, str) or not identity or identity in ids or len(identity) > 256
                    or call.get("type") != "function" or not isinstance(function, dict)
                    or not set(function) <= {"name", "arguments"}
                    or function.get("name") not in self.allowed_tools
                    or not isinstance(function.get("arguments"), str)):
                raise ModelFailure("AGENT_TOOL_CALL_INVALID")
            try:
                arguments = json.loads(function["arguments"])
            except (ValueError, TypeError):
                raise ModelFailure("AGENT_TOOL_ARGUMENTS_INVALID") from None
            if not isinstance(arguments, dict):
                raise ModelFailure("AGENT_TOOL_ARGUMENTS_INVALID")
            ids.add(identity)
        return ids

    def prepare_agent_chat(self, payload):
        allowed = {"model", "messages", "stream", "stream_options", "tools", "tool_choice",
                   "parallel_tool_calls", "temperature", "max_tokens", "enable_thinking",
                   "response_format", "top_p"}
        messages = payload.get("messages")
        stream_options = payload.get("stream_options", {})
        if (not set(payload) <= allowed or type(payload.get("stream", False)) is not bool
                or not isinstance(stream_options, dict)
                or not set(stream_options) <= {"include_usage", "include_obfuscation"}
                or any(type(value) is not bool for value in stream_options.values())
                or payload.get("tool_choice", "auto") not in ("auto", "none")
                or type(payload.get("parallel_tool_calls", False)) is not bool
                or not isinstance(messages, list) or not messages):
            raise ModelFailure("AGENT_CHAT_REQUEST_INVALID")
        tools = payload.get("tools", [])
        if not isinstance(tools, list) or len(tools) > len(self.allowed_tools):
            raise ModelFailure("AGENT_TOOL_DEFINITIONS_INVALID")
        names = set()
        for tool in tools:
            if not isinstance(tool, dict) or set(tool) != {"type", "function"}:
                raise ModelFailure("AGENT_TOOL_DEFINITION_INVALID")
            function = tool.get("function")
            if (tool.get("type") != "function" or not isinstance(function, dict)
                    or not set(function) <= {"name", "description", "parameters", "strict"}
                    or function.get("name") not in self.allowed_tools
                    or function["name"] in names or not isinstance(function.get("parameters"), dict)):
                raise ModelFailure("AGENT_TOOL_DEFINITION_INVALID")
            names.add(function["name"])
        pending = set()
        for row in messages:
            if not isinstance(row, dict) or not set(row) <= {"role", "content", "name", "tool_calls", "tool_call_id"}:
                raise ModelFailure("AGENT_MESSAGE_INVALID")
            role, content = row.get("role"), row.get("content")
            if role not in ("system", "user", "assistant", "tool"):
                raise ModelFailure("AGENT_MESSAGE_INVALID")
            if role == "tool":
                identity = row.get("tool_call_id")
                if identity not in pending or not isinstance(content, str) or row.get("tool_calls"):
                    raise ModelFailure("AGENT_TOOL_RESULT_INVALID")
                pending.remove(identity)
            else:
                if pending or (content is not None and not isinstance(content, str)) or row.get("tool_call_id"):
                    raise ModelFailure("AGENT_MESSAGE_INVALID")
                calls = row.get("tool_calls")
                if calls:
                    if role != "assistant":
                        raise ModelFailure("AGENT_MESSAGE_INVALID")
                    pending = self.validate_calls(calls)
                elif not isinstance(content, str):
                    raise ModelFailure("AGENT_MESSAGE_INVALID")
        if pending:
            raise ModelFailure("AGENT_TOOL_RESULTS_MISSING")
        # Buffered real completions verify behavior, not upstream streaming latency.
        payload["stream"] = False
        payload.pop("stream_options", None)
        return len(json.dumps(messages, ensure_ascii=False)) + len(json.dumps(tools, ensure_ascii=False))

    def validate_chat(self, result):
        choices = result.get("choices")
        if not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict):
            raise ModelFailure("AGENT_RESPONSE_INVALID")
        message = choices[0].get("message")
        if not isinstance(message, dict):
            raise ModelFailure("AGENT_RESPONSE_INVALID")
        if message.get("tool_calls"):
            self.validate_calls(message["tool_calls"])
            if message.get("content") is not None and not isinstance(message["content"], str):
                raise ModelFailure("AGENT_RESPONSE_INVALID")
            if choices[0].get("finish_reason") != "tool_calls":
                raise ModelFailure("AGENT_TOOL_RESPONSE_INCOMPLETE")
        else:
            super().validate_chat(result)

    def stream_response(self, result):
        choice = result["choices"][0]
        message = choice["message"]
        delta = {key: message[key] for key in ("role", "content") if key in message}
        if message.get("tool_calls"):
            delta["tool_calls"] = [dict(call, index=index) for index, call in enumerate(message["tool_calls"])]
        common = {key: result[key] for key in ("id", "created", "model") if key in result}
        common["object"] = "chat.completion.chunk"
        chunks = [dict(common, choices=[dict(index=0, delta=delta, finish_reason=None)]),
                  dict(common, choices=[dict(index=0, delta={}, finish_reason=choice.get("finish_reason"))])]
        if "usage" in result:
            chunks.append(dict(common, choices=[], usage=result["usage"]))
        return ("".join("data: " + json.dumps(chunk) + "\n\n" for chunk in chunks) + "data: [DONE]\n\n").encode()
