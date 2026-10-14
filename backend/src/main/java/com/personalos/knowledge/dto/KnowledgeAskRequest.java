package com.personalos.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 知识库问答请求。
 *
 * @param question 问题
 * @param topK     参与回答的片段条数；省略则用配置默认值
 */
public record KnowledgeAskRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 500, message = "问题长度不能超过 500")
        String question,

        Integer topK) {
}
