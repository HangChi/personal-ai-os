package com.personalos.chat.dto;

/**
 * 消息引用快照（阶段 19）。
 *
 * <p>与知识库检索的 {@code KnowledgeHitDto} 不同：这里**不**带 score /
 * matchedBy——那是检索过程量；存档只需要「回答当时引用了哪段」。
 *
 * @param index         引用编号 [n]，从 1 开始
 * @param documentId    来源文档标识（快照）
 * @param documentTitle 文档标题（快照）
 * @param filename      文件名（快照）
 * @param chunkIndex    片段序号（0 起）
 * @param content       片段内容（快照）
 */
public record ChatCitationDto(Integer index,
                              String documentId,
                              String documentTitle,
                              String filename,
                              Integer chunkIndex,
                              String content) {
}
