package com.personalos.chat.dto;

/**
 * 附件元数据（阶段 22）。
 *
 * <p>文件本体在 {@code backend/data/uploads/<id>}，本记录只是它的「名片」：
 * 前端凭 {@link #url()} 取内容，凭文件名与类型决定怎么展示。
 *
 * @param id          附件标识（同时是磁盘文件名，不含扩展名）
 * @param filename    原始文件名（展示用，可能含中文与空格）
 * @param contentType MIME 类型；识别不出时为 {@code application/octet-stream}
 * @param size        字节数
 */
public record ChatAttachmentDto(String id, String filename, String contentType, long size) {

    /** 取内容的相对地址（前端拼 baseURL） */
    public String url() {
        return "/api/v1/chat/uploads/" + id + "/content";
    }

    /** 是否图片：决定前端渲染缩略图还是文件卡片 */
    public boolean image() {
        return contentType != null && contentType.startsWith("image/");
    }
}
