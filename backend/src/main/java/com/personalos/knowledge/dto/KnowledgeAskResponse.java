package com.personalos.knowledge.dto;

import java.util.List;

/**
 * 知识库问答响应。
 *
 * @param question  原始问题
 * @param answer    回答正文；其中用 {@code [n]} 标注引用编号
 * @param answered  是否真的基于知识库作答。{@code false} 表示知识库中没有相关内容，
 *                  此时 {@code citations} 必为空，且**没有调用过模型**（不得编造）
 * @param citations 引用列表，编号与答案中的 {@code [n]} 一一对应
 */
public record KnowledgeAskResponse(String question,
                                   String answer,
                                   boolean answered,
                                   List<CitationDto> citations) {
}
