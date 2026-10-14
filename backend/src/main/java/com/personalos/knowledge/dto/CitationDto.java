package com.personalos.knowledge.dto;

/**
 * 引用。
 *
 * <p>由**检索结果的元数据**直接构造，不由模型生成 —— 模型只负责在答案里写 {@code [n]} 编号。
 * 这样引用与「实际进入上下文的片段」严格一一对应，可被测试逐条断言（SC-005）。
 *
 * @param index         引用编号，与答案中的 {@code [n]} 对应
 * @param documentId    来源文档标识
 * @param documentTitle 来源文档标题
 * @param filename      来源文件名
 * @param chunkIndex    片段序号
 * @param content       片段原文
 * @param score         相似度
 */
public record CitationDto(int index,
                          java.util.UUID documentId,
                          String documentTitle,
                          String filename,
                          int chunkIndex,
                          String content,
                          Double score) {
}
