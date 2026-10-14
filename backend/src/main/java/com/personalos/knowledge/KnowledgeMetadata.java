package com.personalos.knowledge;

/**
 * 片段元数据的键名。
 *
 * <p>这些键会随向量一起写入 {@code vector_store.metadata}，并在检索时原样读回。
 * 集中定义的理由：**拼错的字符串不会编译报错**，只会导致「删除时清不掉向量」
 * 或「引用里标题为空」这类静默故障。
 */
public final class KnowledgeMetadata {

    /** 来源文档标识 —— 删除文档时按它过滤清理向量 */
    public static final String DOCUMENT_ID = "document_id";

    /** 来源文档标题 —— 引用展示用，避免回表 */
    public static final String DOCUMENT_TITLE = "document_title";

    /** 原始文件名 */
    public static final String FILENAME = "filename";

    /** 片段在文档内的序号（从 0 开始） */
    public static final String CHUNK_INDEX = "chunk_index";

    private KnowledgeMetadata() {
    }
}
