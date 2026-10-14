package com.personalos.knowledge.service;

import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.knowledge.dto.CitationDto;
import com.personalos.knowledge.dto.KnowledgeAskResponse;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 知识库问答：检索 → 编号注入 → 生成 → 组装引用。
 *
 * <h2>引用为什么由服务端产出</h2>
 * 引用不是让模型「说出处」，而是把**实际进入上下文的片段**原样返回。
 * 让模型自己写来源等于把溯源交给一个会编造的东西 —— 引用一旦不可信，整个 RAG 就不可信。
 * 模型只负责在正文里写 {@code [n]} 编号，编号与引用列表一一对应。
 */
@Service
public class KnowledgeQaService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQaService.class);

    /** 知识库无相关内容时的固定答复，保证「不编造」是可断言的 */
    public static final String NO_CONTENT_ANSWER = "知识库中没有找到相关内容。";

    private static final String SYSTEM_PROMPT = """
            你是个人知识库问答助手。请严格依据用户提供的资料回答问题。
            规则：
            1. 只使用资料中出现的facts，不要引入资料之外的知识，也不要推测。
            2. 引用资料时在句末标注编号，例如 [1]；引用多条则写 [1][2]。
            3. 如果资料不足以回答问题，明确说明「资料中没有相关内容」，不要编造。
            4. 回答使用中文，简洁准确。
            """;

    private final KnowledgeRetrievalService retrievalService;
    private final AiChatClientProvider aiProvider;

    public KnowledgeQaService(KnowledgeRetrievalService retrievalService, AiChatClientProvider aiProvider) {
        this.retrievalService = retrievalService;
        this.aiProvider = aiProvider;
    }

    /**
     * 基于知识库回答问题。
     *
     * <p>**先检索、后取模型**：检索为空时不需要模型也能给出「没有找到相关内容」这个结论，
     * 因此未配置模型时仍能回答「知识库为空 / 无相关内容」这类问题。
     *
     * @param question 问题
     * @param topK     参与回答的片段条数
     * @return 回答与引用；无命中时 {@code answered=false} 且**不调用模型**
     */
    public KnowledgeAskResponse ask(String question, Integer topK) {
        if (question == null || question.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "问题不能为空");
        }
        String trimmedQuestion = question.strip();

        List<KnowledgeHitDto> hits = retrievalService.search(trimmedQuestion, topK);
        if (hits.isEmpty()) {
            log.debug("知识库无命中，直接返回固定答复而不调用模型 question={}", trimmedQuestion);
            return new KnowledgeAskResponse(trimmedQuestion, NO_CONTENT_ANSWER, false, List.of());
        }

        ChatClient chatClient = aiProvider.requirePlainChatClient();
        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(buildUserMessage(trimmedQuestion, hits))
                .call()
                .content();

        List<CitationDto> citations = new ArrayList<>(hits.size());
        for (int index = 0; index < hits.size(); index++) {
            KnowledgeHitDto hit = hits.get(index);
            citations.add(new CitationDto(index + 1, hit.documentId(), hit.documentTitle(),
                    hit.filename(), hit.chunkIndex(), hit.content(), hit.score()));
        }

        log.debug("知识库问答完成 question={} hits={}", trimmedQuestion, hits.size());
        return new KnowledgeAskResponse(trimmedQuestion, answer == null ? "" : answer, true, citations);
    }

    /**
     * 构造用户消息：把片段编号并附上来源标题。
     *
     * <p>不使用模板引擎 —— 拼接逻辑越直白，越容易在测试里断言「上下文里确实带了这些片段」。
     */
    private String buildUserMessage(String question, List<KnowledgeHitDto> hits) {
        StringBuilder context = new StringBuilder();
        for (int index = 0; index < hits.size(); index++) {
            KnowledgeHitDto hit = hits.get(index);
            context.append('[').append(index + 1).append("] (来源：")
                    .append(hit.documentTitle())
                    .append(hit.filename().isBlank() ? "" : "／" + hit.filename())
                    .append(")\n")
                    .append(hit.content())
                    .append("\n\n");
        }
        return """
                请仅依据下面的资料回答问题。

                【资料】
                %s
                【问题】
                %s
                """.formatted(context, question);
    }
}
