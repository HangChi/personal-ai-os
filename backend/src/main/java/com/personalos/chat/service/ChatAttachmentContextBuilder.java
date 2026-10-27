package com.personalos.chat.service;

import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.common.document.PlainTextExtractor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * 把本轮附件转成「模型能读懂的内容」。
 *
 * <h2>为什么文本进系统提示而不是用户消息</h2>
 * 这是本项目里最容易做错的一处：如果附件全文拼进用户消息，{@code MessageChatMemoryAdvisor}
 * 会把这条膨胀后的消息**存进历史**，于是下一轮、再下一轮都会重新带上它 ——
 * 一份 8000 字的附件会在几轮里吃光上下文预算，而且用户完全看不出为什么。
 *
 * <p>放进系统提示只影响本轮（请求级 {@code system()} 不落库），与知识库片段同一个套路。
 *
 * <h2>图片为什么必须留在用户消息上</h2>
 * 多模态协议要求图片随 user message 发送。好消息是 ChatMemory 只保存文本内容，
 * 图片天然不会进历史 —— 两边的约束正好各自成立。
 *
 * <h2>读不到就跳过，不失败</h2>
 * 用户传了 zip、加密 PDF、损坏文件都是常态。此时正确行为是「告诉模型这份读不到」，
 * 而不是让整轮对话报错 —— 界面上附件依然可见，模型也知道自己缺了哪份材料，
 * 从而能回答「这份文件我读不了」而不是编造内容。
 */
@Service
public class ChatAttachmentContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentContextBuilder.class);

    /** 单轮最多送几张图：多模态按图计费，且每张都占大量 token */
    private static final int MAX_IMAGES = 4;

    /** 单张图片上限：20MB 的原图经 Base64 编码会膨胀约 1/3，多数模型也会拒绝 */
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    /** 文本附件合计上限（单份已由 PlainTextExtractor 截到 8000） */
    private static final int MAX_TEXT_TOTAL = 16_000;

    private final ChatAttachmentService attachmentService;
    private final PlainTextExtractor textExtractor;

    public ChatAttachmentContextBuilder(ChatAttachmentService attachmentService,
                                        PlainTextExtractor textExtractor) {
        this.attachmentService = attachmentService;
        this.textExtractor = textExtractor;
    }

    /**
     * @param attachments 客户端声明的附件名片；内部会先 {@link ChatAttachmentService#verify}
     *                    逐个确认文件真实存在（不信任客户端传来的 size 与标识）
     */
    public ChatAttachmentContext build(List<ChatAttachmentDto> attachments) {
        List<ChatAttachmentDto> verified = attachmentService.verify(attachments);
        if (verified.isEmpty()) {
            return ChatAttachmentContext.empty();
        }

        List<Media> images = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        StringBuilder textBlock = new StringBuilder();

        for (ChatAttachmentDto attachment : verified) {
            try {
                if (attachment.image()) {
                    collectImage(attachment, images, notes);
                } else {
                    collectText(attachment, textBlock, notes);
                }
            } catch (RuntimeException ex) {
                log.warn("附件内容读取失败，本轮跳过 id={} filename={}",
                        attachment.id(), attachment.filename(), ex);
                notes.add(attachment.filename() + "：读取失败，本轮未使用");
            }
        }

        if (images.isEmpty() && textBlock.isEmpty()) {
            // 全是读不到的附件：仍要告诉模型「有附件但读不了」，否则它会把用户的问法
            // 当成在问别的东西（实测：会去猜知识库里有没有相关内容）
            String onlyNotes = notes.isEmpty() ? "" : "【用户上传的附件】\n" + String.join("\n", notes);
            return new ChatAttachmentContext(List.of(), onlyNotes);
        }

        StringBuilder section = new StringBuilder("【用户上传的附件】");
        if (!textBlock.isEmpty()) {
            section.append('\n').append(textBlock);
        }
        for (String note : notes) {
            section.append("\n- ").append(note);
        }
        return new ChatAttachmentContext(List.copyOf(images), section.toString());
    }

    private void collectImage(ChatAttachmentDto attachment, List<Media> images, List<String> notes) {
        if (images.size() >= MAX_IMAGES) {
            notes.add(attachment.filename() + "：本轮图片数量已达上限（" + MAX_IMAGES + " 张），未发送");
            return;
        }
        byte[] bytes = attachmentService.readBytes(attachment.id());
        if (bytes.length > MAX_IMAGE_BYTES) {
            notes.add(attachment.filename() + "：图片超过 " + (MAX_IMAGE_BYTES / 1024 / 1024)
                    + "MB，未发送（可压缩后重试）");
            return;
        }
        MimeType mimeType;
        try {
            mimeType = MimeTypeUtils.parseMimeType(attachment.contentType());
        } catch (RuntimeException ex) {
            // contentType 可能缺失或写成 octet-stream —— 此时按 PNG 处理没有依据，
            // 干脆跳过并说明，不做猜测
            notes.add(attachment.filename() + "：无法识别图片类型，未发送");
            return;
        }
        images.add(new Media(mimeType, new ByteArrayResource(bytes)));
    }

    private void collectText(ChatAttachmentDto attachment, StringBuilder textBlock, List<String> notes) {
        if (textBlock.length() >= MAX_TEXT_TOTAL) {
            notes.add(attachment.filename() + "：本轮文本附件已达上限，未发送");
            return;
        }
        byte[] bytes = attachmentService.readBytes(attachment.id());
        Optional<PlainTextExtractor.Extracted> extracted =
                textExtractor.extract(bytes, attachment.filename(), attachment.contentType());
        if (extracted.isEmpty()) {
            notes.add(attachment.filename() + "：格式不支持直接读取（当前支持 txt/md/json/csv/xml/yaml/pdf/docx）");
            return;
        }
        PlainTextExtractor.Extracted content = extracted.get();
        textBlock.append("\n### ").append(attachment.filename()).append('\n')
                .append(content.text());
        if (content.truncated()) {
            textBlock.append("\n（以上内容已截断，原文更长）");
        }
        textBlock.append('\n');
    }
}
