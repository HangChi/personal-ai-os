package com.personalos.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 检索请求。
 *
 * @param query 检索词
 * @param topK  返回条数；省略则用配置默认值，超过上限会被收敛
 */
public record KnowledgeSearchRequest(
        @NotBlank(message = "检索词不能为空")
        @Size(max = 500, message = "检索词长度不能超过 500")
        String query,

        Integer topK) {
}
