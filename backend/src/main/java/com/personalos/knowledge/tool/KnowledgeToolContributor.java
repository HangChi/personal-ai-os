package com.personalos.knowledge.tool;

import com.personalos.common.ai.ToolContributor;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 把知识库工具贡献给对话。
 *
 * <p>复用阶段 2 建立的 {@link ToolContributor} SPI，因此 {@code chat} 模块
 * **不需要任何改动**就获得了知识库检索能力 —— 这正是当初把 SPI 放在 {@code common} 的目的。
 */
@Component
public class KnowledgeToolContributor implements ToolContributor {

    private final KnowledgeTools knowledgeTools;

    public KnowledgeToolContributor(KnowledgeTools knowledgeTools) {
        this.knowledgeTools = knowledgeTools;
    }

    @Override
    public List<Object> toolObjects() {
        return List.of(knowledgeTools);
    }
}
