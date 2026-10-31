package com.personalos.chat.service;

import com.personalos.chat.entity.ChatMessageEntity;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.common.ai.AiProperties;
import com.personalos.common.ai.ContextBudgetService;
import com.personalos.common.ai.PromptContributor;
import com.personalos.common.ai.ToolSchemaOverhead;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 拼装送给模型的系统提示，并估算它的体量。
 *
 * <h2>顺序即优先级</h2>
 * <b>基础人设 → 早前对话摘要 → 各模块上下文（长期记忆等）→ 知识库片段</b>。
 * 摘要紧跟人设，是因为它回答「这段对话之前发生了什么」，属于对话本身的一部分；
 * 记忆与检索则是外部知识，排在后面。
 *
 * <h2>为什么不用 {@code defaultSystem}</h2>
 * 它只是**默认值**，请求级 {@code system()} 会替换它。若只传贡献内容（如记忆块），
 * 阶段 1 定下的人设与行为约束会全部丢失 —— 这类错误不报错，只会让助手"变了个样"，
 * 极难从日志发现。
 *
 * <p>各段为空时**不追加任何内容**（连空行都不加），保证「没有任何上下文」时的系统提示
 * 与阶段 1 逐字符相同。
 *
 * <p>从 {@code ChatCompletionService} 抽出（P2 重构）：提示词拼装是一套自成体系的规则
 * （顺序、空值处理、引用编号），和「怎么推流」是两件事。
 */
@Service
public class ChatPromptAssembler {

    private final AiProperties aiProperties;
    private final ObjectProvider<PromptContributor> promptContributors;
    private final ChatSummaryService summaryService;
    private final ChatMessageRepository messageRepository;
    private final ToolSchemaOverhead toolSchemaOverhead;
    private final ContextBudgetService contextBudget;

    public ChatPromptAssembler(AiProperties aiProperties,
                               ObjectProvider<PromptContributor> promptContributors,
                               ChatSummaryService summaryService,
                               ChatMessageRepository messageRepository,
                               ToolSchemaOverhead toolSchemaOverhead,
                               ContextBudgetService contextBudget) {
        this.aiProperties = aiProperties;
        this.promptContributors = promptContributors;
        this.summaryService = summaryService;
        this.messageRepository = messageRepository;
        this.toolSchemaOverhead = toolSchemaOverhead;
        this.contextBudget = contextBudget;
        // 把自己算的固定开销注册给预算服务：预算要扣掉它才能真正约束「整轮请求」。
        // 反向注册（预算服务注入本类）会形成构造器环 —— 本类依赖工具集合，
        // 工具集合经 ChatMemory 又依赖预算服务。
        contextBudget.registerFixedOverhead(this::fixedOverheadTokens);
    }

    /**
     * 拼装本轮请求的系统提示。
     *
     * @param hits           本轮知识库命中；空列表表示不注入知识库区块
     * @param userInput      本轮用户问题（贡献者据此做相关性筛选）
     * @param conversationId 会话标识（摘要按会话取）
     */
    public String assemble(List<KnowledgeHitDto> hits, String userInput, String conversationId) {
        return assemble(hits, userInput, conversationId, "");
    }

    /**
     * 拼装本轮请求的系统提示（含用户上传的附件内容）。
     *
     * <p>附件区块排在**知识库之前**：用户本轮显式上传的材料，相关性高于系统自动检索的结果。
     * 这与「越靠后越具体」的通例相反，是刻意为之 —— 优先级不该只靠位置表达，
     * 但位置至少要保证「更该被看到的先被看到」。
     *
     * @param attachmentBlock 已格式化的附件文本（见 {@code ChatAttachmentContextBuilder}）；
     *                        空串表示本轮无文本附件
     */
    public String assemble(List<KnowledgeHitDto> hits, String userInput, String conversationId,
                           String attachmentBlock) {
        StringBuilder prompt = new StringBuilder(aiProperties.systemPrompt());

        String summary = summaryService.renderForConversation(conversationId);
        if (!summary.isBlank()) {
            prompt.append("\n\n").append(summary);
        }

        promptContributors.orderedStream().forEach(contributor -> {
            // 传本轮问题 + 会话标识：前者用于相关性筛选（如长期记忆按相关度排序），
            // 后者用于会话级上下文（如执行计划 —— 换个会话就不该出现）
            String contribution = contributor.contribute(userInput, conversationId);
            if (contribution != null && !contribution.isBlank()) {
                prompt.append("\n\n").append(contribution);
            }
        });
        if (attachmentBlock != null && !attachmentBlock.isBlank()) {
            prompt.append("\n\n").append(attachmentBlock);
        }
        if (hits != null && !hits.isEmpty()) {
            prompt.append("\n\n").append(buildKnowledgeSection(hits));
        }
        return prompt.toString();
    }

    /** Assemble complete dynamic context, then summarize the history that will not fit this turn. */
    public String prepare(List<KnowledgeHitDto> hits, String userInput, String conversationId,
                          String attachmentBlock, int imageCount, String modelName, int thinkingBudget) {
        String model = contextBudget.effectiveModelName(modelName);
        String system = assemble(hits, userInput, conversationId, attachmentBlock);
        int fixed = com.personalos.common.ai.PromptTokenCounter.text(system, model)
                + com.personalos.common.ai.PromptTokenCounter.text(userInput, model)
                + ContextBudgetService.toTokens(toolSchemaOverhead.chars())
                + imageCount * contextBudget.protection().imageReserveTokens() + 128;
        int window = contextBudget.contextWindowTokens(model);
        int inputLimit = window - contextBudget.outputReserveForWindow(window, thinkingBudget) - contextBudget.safetyMarginForWindow(window);
        int historyBudget = Math.max(0, (int) (Math.min(contextBudget.budgetTokensFor(model), inputLimit) * 0.85) - fixed);
        List<ChatMessageEntity> recent = messageRepository.findRecentByConversationId(
                conversationId, PageRequest.of(0, aiProperties.memory().maxMessages()));
        int boundary = recent.isEmpty() ? 0 : recent.get(recent.size() - 1).getSequenceNo() - 1;
        int used = 0;
        for (ChatMessageEntity message : recent) {
            int cost = com.personalos.common.ai.PromptTokenCounter.text(message.getContent(), model) + 12;
            if (used + cost > historyBudget) {
                boundary = message.getSequenceNo();
                break;
            }
            used += cost;
        }
        if (boundary <= 0) return system;
        summaryService.refreshBeforeRequest(conversationId, boundary);
        return assemble(hits, userInput, conversationId, attachmentBlock);
    }

    /**
     * 估算某个会话下一次请求的上下文字符数（仪表盘用）。
     *
     * <p>与流内计算同源，但独立可调：刷新页面后前端拿不到 done 事件，
     * 需要单独查一次才能显示仪表盘。
     */
    public int estimatePromptChars(String conversationId) {
        // 只含系统提示 + 窗口内历史：窗口里已经包含最新的那些消息，
        // 再单独加一条「最新消息长度」就是重复计算（2026-10-26 修）。
        // 下一轮用户要发什么这里不可能知道，所以不计入。
        //
        // 2026-10-29 加上工具定义：它每轮都随请求发送，却不在系统提示与历史里。
        // 漏掉它的后果是仪表盘显示「103 / 32K」，而实际请求已是 4K —— 用户
        // 据此判断「还有余量」，做出的决定全是错的。
        String systemPrompt = assemble(List.of(), "", conversationId);
        return systemPrompt.length() + estimateHistoryChars(conversationId) + toolSchemaOverhead.chars();
    }

    /**
     * 一份完整请求里**与对话内容无关**的固定开销（token）：基础人设 + 工具定义。
     *
     * <p>它的唯一用途是给预算兜底：预算比这份开销还小的时候，无论怎么裁历史，
     * 请求都必然超出预算 —— 那不是「预算生效了」，而是「这个预算不可能被满足」。
     * 详见 {@code ChatSessionController#setContextBudget}。
     *
     * <p>刻意**不含**摘要、记忆、知识库：它们随会话与问题变化，属于「内容」，
     * 该由预算去约束，而不是被当成不可压缩的底盘。
     */
    public int fixedOverheadTokens() {
        int base = aiProperties.systemPrompt() == null ? 0 : aiProperties.systemPrompt().length();
        return ContextBudgetService.toTokens(base + toolSchemaOverhead.chars());
    }

    /**
     * 工具定义的字符数（与系统提示、历史分开给）。
     *
     * <p>流式路径要把「系统提示 + 用户消息 + 历史 + 工具」加在一起做用量估算，
     * 那一处需要的是字符而不是 token，因此单独暴露一次，避免调用方自己再折算一遍。
     */
    public int toolOverheadChars() {
        return toolSchemaOverhead.chars();
    }

    /**
     * 估算窗口内历史的字符数。
     *
     * <p>为什么是估算：历史由 {@code MessageChatMemoryAdvisor} 在请求链路里装载，
     * 这个方法拿不到最终窗口的原文。用「窗口条数上限内最新若干条」的字符和逼近，
     * 误差来自「只有字符预算把窗口裁得更短时才偏大」。
     *
     * <p>偏差方向是「偏大」，对仪表盘是安全的：宁可让用户以为快满了，
     * 也不要显示假的宽裕。
     *
     * <p>对外可见是因为流内也要算同一笔账：本轮的实际系统提示（含知识库区块）由调用方
     * 拿在手里，只需再补上历史这一段。用 {@link #estimatePromptChars} 反推会漏掉
     * 知识库区块的长度 —— 那正是唯一会让两处结果不一致的地方。
     */
    public int estimateHistoryChars(String conversationId) {
        int limit = aiProperties.memory().maxMessages();
        List<ChatMessageEntity> recent = messageRepository
                .findRecentByConversationId(conversationId, PageRequest.of(0, limit));
        return (int) recent.stream()
                .mapToLong(message -> message.getContent() == null ? 0 : message.getContent().length())
                .sum();
    }

    /** 知识库检索片段的系统提示区块（格式即契约，见 contracts C-01）。 */
    private String buildKnowledgeSection(List<KnowledgeHitDto> hits) {
        StringBuilder section = new StringBuilder();
        section.append("以下内容来自用户的个人知识库检索结果（本轮实时检索）：");
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeHitDto hit = hits.get(i);
            section.append("\n\n[").append(i + 1).append("] 文档《")
                    .append(nullToDash(hit.documentTitle())).append("》（")
                    .append(nullToDash(hit.filename())).append("，第 ")
                    .append(hit.chunkIndex() + 1).append(" 段）\n")
                    .append(hit.content());
        }
        section.append("\n\n使用约束：\n")
                .append("- 回答必须基于上述内容，引用处标注对应编号，如 [1]\n")
                .append("- 上述内容没有答案时，明确说明「知识库中没有找到相关内容」，不得编造\n")
                .append("- 不要复述整段原文，提炼回答");
        return section.toString();
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "未命名" : value;
    }
}
