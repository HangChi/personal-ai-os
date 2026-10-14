package com.personalos.knowledge.tool;

import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.ai.tool.ToolExecutor;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.service.KnowledgeRetrievalService;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 供 Agent 调用的知识库工具。
 *
 * <p><b>只读</b>：检索不产生任何副作用，因此不进入人工确认范围，
 * 也不需要幂等（由 {@link ToolExecutor#read} 走「只审计、不去重」的路径）。
 */
@Component
public class KnowledgeTools {

    private final KnowledgeRetrievalService retrievalService;
    private final ToolExecutor toolExecutor;

    public KnowledgeTools(KnowledgeRetrievalService retrievalService, ToolExecutor toolExecutor) {
        this.retrievalService = retrievalService;
        this.toolExecutor = toolExecutor;
    }

    @Tool(name = "searchKnowledge",
            description = "检索用户的个人知识库（用户上传的笔记与文档）。"
                    + "当用户询问自己的资料、笔记、上传过的文档内容时调用。"
                    + "这是只读操作，不会修改任何数据。返回的片段带有来源标题与序号，"
                    + "回答时请据此说明依据。")
    public String searchKnowledge(
            @ToolParam(description = "检索词或问题的关键词，1~500 字") String query,
            @ToolParam(required = false, description = "返回条数，默认 4，上限 20") Integer topK,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
            args.put("query", query);
            args.put("topK", topK);
        });

        return toolExecutor.read("searchKnowledge", arguments, toolContext, () -> {
            if (query == null || query.isBlank()) {
                return ToolExecutionResult.rejected("检索词不能为空");
            }
            List<KnowledgeHitDto> hits = retrievalService.search(query, topK);
            if (hits.isEmpty()) {
                // 空结果**不是错误**：模型应据此如实回答「资料里没有」。
                // 若返回 REJECTED，模型会倾向于重试或换个说法再试，反而更糟。
                return ToolExecutionResult.ok("知识库中没有找到相关内容", List.of());
            }
            return ToolExecutionResult.ok("找到 " + hits.size() + " 条相关内容", hits);
        });
    }
}
