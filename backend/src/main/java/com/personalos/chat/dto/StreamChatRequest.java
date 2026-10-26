package com.personalos.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 流式对话请求（阶段 19 扩展）。
 *
 * @param content      本条用户消息内容
 * @param useKnowledge 是否启用知识库增强：调用模型前先检索知识库，把命中片段
 *                     注入系统提示，回答带 [n] 引用。缺省 false —— 省略时行为与
 *                     阶段 1 逐字符一致（回归保护面）
 * @param topK         检索条数，1~20，缺省 4；越界由服务端收敛
 */
public record StreamChatRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 4000, message = "长度不能超过 4000")
        String content,
        Boolean useKnowledge,
        Integer topK) {

    /** 检索条数默认值 */
    public static final int DEFAULT_TOP_K = 4;
    /** 检索条数上限（与知识库检索的 MAX_TOP_K 对齐） */
    public static final int MAX_TOP_K = 20;

    /** useKnowledge 缺省值：关 */
    public boolean knowledgeEnabled() {
        return Boolean.TRUE.equals(useKnowledge);
    }

    /** topK 收敛到 [1, 20]；null 回落默认值 */
    public int resolvedTopK() {
        if (topK == null) {
            return DEFAULT_TOP_K;
        }
        return Math.min(Math.max(topK, 1), MAX_TOP_K);
    }
}
