package com.personalos.agent.interaction.service;

import com.personalos.agent.interaction.dto.AgentQuestion;
import com.personalos.agent.interaction.dto.AgentFollowup;
import com.personalos.agent.interaction.repository.AgentInteractionRepository;
import com.personalos.chat.repository.ChatRunRepository;
import com.personalos.common.ai.AgentControlSignal;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** 交互持久化与边界信号；不持有模型线程，不通过模型代替人作出决定。 */
@Service
public class AgentInteractionService {
    private final AgentInteractionRepository repository;
    private final ChatRunRepository runs;
    private final JsonCodec json;
    private final Set<String> signals = ConcurrentHashMap.newKeySet();
    public AgentInteractionService(AgentInteractionRepository repository, ChatRunRepository runs, JsonCodec json) {
        this.repository = repository; this.runs = runs; this.json = json;
    }
    public synchronized AgentQuestion ask(String conversationId, String runId, String question, List<AgentQuestion.Option> options) {
        if (question == null || question.isBlank() || question.length() > 600) throw conflict("问题须为 1～600 字");
        List<AgentQuestion.Option> choices = options == null ? List.of() : options;
        if (choices.size() > 4 || choices.stream().anyMatch(option -> option == null || option.label() == null
                || option.label().isBlank() || option.label().length() > 100
                || (option.description() != null && option.description().length() > 300))) throw conflict("最多提供 4 个简短选项");
        if (choices.stream().map(AgentQuestion.Option::label).distinct().count() != choices.size()) throw conflict("选项名称不能重复");
        var run = runs.find(conversationId, runId).orElseThrow(() -> conflict("结构化提问需要后台执行任务"));
        if (!"RUNNING".equals(run.status())) throw conflict("任务当前未在执行");
        String digest = json.digestOf(List.of(question.strip(), choices));
        var existing = repository.byDigest(runId, digest);
        if (existing.isPresent()) return existing.get();
        if (repository.pendingQuestion(runId).isPresent()) throw conflict("请先等待上一个问题的回答");
        var result = new AgentQuestion(UUID.randomUUID().toString(), runId, question.strip(), choices, "PENDING", null);
        repository.createQuestion(result, digest);
        signals.add(runId);
        return result;
    }
    public void checkpoint(String runId) { if (signals.contains(runId)) throw new AgentControlSignal(); }
    public synchronized void restore(String runId) {
        if (repository.pendingQuestion(runId).isPresent() || !repository.pendingSteering(runId).isEmpty()) signals.add(runId);
        else signals.remove(runId);
    }
    public AgentQuestion pendingQuestion(String runId) { return repository.pendingQuestion(runId).orElse(null); }
    public synchronized void answer(String runId, String id, String answer) {
        if (answer == null || answer.isBlank() || answer.length() > 4000) throw conflict("回答须为 1～4000 字");
        var question = repository.question(runId, id).orElseThrow(() -> conflict("问题不属于当前任务"));
        if ("ANSWERED".equals(question.status())) {
            if (!answer.strip().equals(question.answer())) throw conflict("该问题已经回答，不能覆盖原决定");
            return;
        }
        repository.answer(runId, id, answer.strip());
        restore(runId);
    }
    public synchronized AgentFollowup submit(AgentFollowup input) {
        if (!List.of("QUEUE", "STEER").contains(input.mode())) throw conflict("请选择排队或引导当前任务");
        var existing = repository.followup(input.conversationId(), input.id());
        if (existing.isPresent()) {
            if (!json.toJson(existing.get().request()).equals(json.toJson(input.request()))
                    || !existing.get().mode().equals(input.mode()) || !existing.get().runId().equals(input.runId())) throw conflict("跟进标识已用于其他内容");
            return existing.get();
        }
        if (repository.pendingInputs(input.conversationId()).size() >= 20) throw conflict("待处理消息已达 20 条，请稍后再发送");
        repository.createFollowup(input);
        if ("STEER".equals(input.mode())) signals.add(input.runId());
        return input;
    }
    public synchronized List<AgentFollowup> applySteering(String runId) {
        var inputs = repository.pendingSteering(runId);
        inputs.forEach(input -> repository.status(input.id(), "APPLIED"));
        restore(runId);
        return inputs;
    }
    public List<AgentFollowup> pendingInputs(String conversationId) { return repository.pendingInputs(conversationId); }
    public String transcript(String runId) { return repository.transcript(runId); }
    public java.util.Optional<AgentFollowup> nextQueued(String conversationId) {
        return repository.pendingInputs(conversationId).stream().filter(input -> "QUEUE".equals(input.mode())).findFirst();
    }
    public void applied(String id) { repository.status(id, "APPLIED"); }
    public synchronized void cancel(String conversationId, String id) {
        var input = repository.followup(conversationId, id).orElseThrow(() -> conflict("跟进消息不存在"));
        if (!"PENDING".equals(input.status())) throw conflict("消息已经开始处理，无法移除");
        repository.status(id, "CANCELLED"); restore(input.runId());
    }
    public synchronized void edit(String conversationId, String id, String content) {
        if (content == null || content.isBlank() || content.length() > 4000) throw conflict("消息须为 1～4000 字");
        var input = repository.followup(conversationId, id).orElseThrow(() -> conflict("排队消息不存在"));
        var request = input.request();
        var edited = new com.personalos.chat.dto.StreamChatRequest(content.strip(), request.useKnowledge(), request.topK(),
                request.thinkingLevel(), request.attachments());
        if (!repository.edit(conversationId, id, edited)) throw conflict("消息已经开始处理，无法编辑");
    }
    public synchronized boolean steer(String conversationId, String id, String runId) {
        var input = repository.followup(conversationId, id).orElseThrow(() -> conflict("排队消息不存在"));
        if ("STEER".equals(input.mode())) return false; // 网络重试不能再次提交相同引导。
        if (!repository.steer(conversationId, id, runId)) throw conflict("消息已经开始处理，无法改为引导");
        restore(input.runId());
        signals.add(runId);
        return true;
    }
    public List<String> queuedConversations() { return repository.queuedConversations(); }
    private BusinessException conflict(String message) { return new BusinessException(ErrorCode.BUSINESS_CONFLICT, message); }
}
