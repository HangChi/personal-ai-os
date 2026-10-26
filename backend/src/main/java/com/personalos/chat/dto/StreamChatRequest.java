package com.personalos.chat.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import jakarta.validation.constraints.Size;

/**
 * 流式对话请求（阶段 19 扩展；阶段 22 增加思考程度与附件）。
 *
 * @param content      本条用户消息内容
 * @param useKnowledge 是否启用知识库增强：调用模型前先检索知识库，把命中片段
 *                     注入系统提示，回答带 [n] 引用。缺省 false —— 省略时行为与
 *                     阶段 1 逐字符一致（回归保护面）
 * @param topK         检索条数，1~20，缺省 4；越界由服务端收敛
 * @param thinkingLevel 模型思考程度：{@code off}（默认，不思考）/ {@code standard} /
 *                     {@code deep}。未知值回落 off
 * @param attachments   附件名片列表（阶段 22）。文件已在发送前经上传接口落盘，
 *                       这里只带标识与展示元数据；服务端会用磁盘上的真实大小覆盖 size
 */
public record StreamChatRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 4000, message = "长度不能超过 4000")
        String content,
        Boolean useKnowledge,
        Integer topK,
        String thinkingLevel,
        List<ChatAttachmentDto> attachments) {

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

    /** 思考程度收敛；null 与未知值都回落 OFF */
    public ThinkingLevel resolvedThinkingLevel() {
        return ThinkingLevel.fromCode(thinkingLevel);
    }

    /** 附件收敛：null → 空列表 */
    public List<ChatAttachmentDto> resolvedAttachments() {
        return attachments == null ? List.of() : attachments;
    }
}
