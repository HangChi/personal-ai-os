package com.personalos.chat.service;

import org.springframework.ai.content.Media;

import java.util.List;

/**
 * 本轮附件送给模型时的形态。
 *
 * <h2>为什么图片和文本分开</h2>
 * 两者进入模型的通道完全不同：
 * <ul>
 *   <li>图片必须作为多模态 {@link Media} 挂在**用户消息**上 —— 这是协议要求；</li>
 *   <li>文本则拼进**系统提示**的附件区块（见 {@link ChatAttachmentContextBuilder} 的说明）。</li>
 * </ul>
 * 硬塞进同一个通道会有一边失效：文本变成 Base64 图片模型读不懂，图片塞进 system 又不合法。
 *
 * @param images    多模态图片（可能为空；数量与大小在构建时已收敛）
 * @param textBlock 已格式化的附件文本区块（可能为空串），可直接追加到系统提示
 */
public record ChatAttachmentContext(List<Media> images, String textBlock) {

    public static ChatAttachmentContext empty() {
        return new ChatAttachmentContext(List.of(), "");
    }

    public boolean isEmpty() {
        return images.isEmpty() && (textBlock == null || textBlock.isBlank());
    }

    /**
     * 图片数组形式。
     *
     * <p>{@code ChatClient} 的 {@code media(...)} 是 varargs，直接传 {@code List}
     * 编译不过（Spring AI 2.0 的 {@code PromptUserSpec#media} 只接受 {@code Media[]}）。
     * 在这里收口，调用方不必关心这个签名细节。
     */
    public Media[] imageArray() {
        return images.toArray(Media[]::new);
    }
}
