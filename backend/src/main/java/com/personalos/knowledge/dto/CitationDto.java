package com.personalos.knowledge.dto;

import com.personalos.knowledge.governance.SourceInfo;
import java.util.UUID;

/**
 * 引用。
 *
 * <p>引用由检索命中直接构造。问答中的 {@code [n]} 对应第 n 条上下文片段。
 *
 * @param index         引用编号，与答案中的 {@code [n]} 对应
 * @param documentId    来源文档标识
 * @param documentTitle 来源文档标题
 * @param filename      来源文件名
 * @param chunkIndex    片段序号
 * @param content       片段原文
 * @param score         相似度
 * @param matchedBy     命中的检索通道
 * @param source        来源的治理元数据
 */
public record CitationDto(int index,
                          UUID documentId,
                          String documentTitle,
                          String filename,
                          int chunkIndex,
                          String content,
                          Double score,
                          KnowledgeHitDto.MatchedBy matchedBy,
                          SourceInfo source) {
    public static CitationDto fromHit(int index, KnowledgeHitDto hit) {
        return new CitationDto(index, hit.documentId(), hit.documentTitle(), hit.filename(),
                hit.chunkIndex(), hit.content(), hit.score(), hit.matchedBy(), hit.source());
    }
}
