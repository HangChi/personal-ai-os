package com.personalos.support;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * 脚本化的桩 ChatModel，可以按顺序返回「工具调用」与「文本回复」。
 *
 * <p>这是本阶段能写「Agent 调用后数据库真的变了」这类断言的前提：真实模型无法保证
 * 一定会调用某个工具、也无法保证参数一致，而桩模型可以精确编排。
 *
 * <p>工具调用被一次性返回为一个完整的 {@link ChatResponse}（而非流式分片），
 * 由框架的 ToolCallingAdvisor 负责识别并执行 —— 这正好也验证了流式路径下的工具调用链路。
 */
public class ScriptedToolCallingChatModel implements ChatModel {

    private final Queue<ChatResponse> script = new ConcurrentLinkedQueue<>();
    private final List<List<Message>> receivedInstructions = new CopyOnWriteArrayList<>();
    private final AtomicInteger callCounter = new AtomicInteger();

    /** 入队一次「调用某个工具」的回复 */
    public void enqueueToolCall(String toolName, String argumentsJson) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-" + callCounter.incrementAndGet(), "function", toolName, argumentsJson)))
                .build();
        // finishReason 必须显式给出：真实模型的工具调用回复会带 tool_calls，
        // 框架据此判断「本轮响应是否可用于工具执行」
        ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                .finishReason("tool_calls")
                .build();
        script.add(new ChatResponse(List.of(new Generation(message, metadata))));
    }

    /** 入队一次普通文本回复 */
    public void enqueueText(String text) {
        ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                .finishReason("stop")
                .build();
        script.add(new ChatResponse(List.of(
                new Generation(new AssistantMessage(text), metadata))));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        receivedInstructions.add(List.copyOf(prompt.getInstructions()));
        ChatResponse next = script.poll();
        return next == null ? textResponse("（脚本已空）") : next;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        receivedInstructions.add(List.copyOf(prompt.getInstructions()));
        ChatResponse next = script.poll();
        return Flux.just(next == null ? textResponse("（脚本已空）") : next);
    }

    /**
     * 必须返回 {@link ToolCallingChatOptions}。
     *
     * <p>这不是可选项：ChatClient 在组装请求时以 {@code chatModel.getOptions().mutate()} 为基准，
     * 只有该 builder 是 {@code ToolCallingChatOptions.Builder} 时才会把工具回调写进 options。
     * 否则 options 会退化成普通 {@code DefaultChatOptions}，
     * 而 {@code ToolCallingAdvisor} 会因 {@code !(options instanceof ToolCallingChatOptions)}
     * 直接放行 —— 症状是「模型返回了工具调用、对话正常结束、零报错，但工具从不执行」。
     */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    public List<List<Message>> receivedInstructions() {
        return receivedInstructions;
    }

    public void reset() {
        script.clear();
        receivedInstructions.clear();
    }

    private ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason("stop").build())));
    }
}
