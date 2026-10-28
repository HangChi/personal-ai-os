-- 向量表按 document_id 检索的表达式索引。
--
-- 为什么需要：删除/替换文档、统计分块数、按文档取原文，全部走
-- `WHERE metadata->>'document_id' = ?`。metadata 列是 json（Spring AI 建表时
-- 用的是 json 而非 jsonb），没有索引时这些查询是**全表扫描**：
--   - 删文档：先删向量再删记录，扫描一次
--   - 替换文档：删旧向量 + 写新向量，扫描两次
--   - VectorStoreInspector.countByDocument：每次混合检索都可能调用
-- 个人库目前只有 234 行，扫描是毫秒级；但知识库增长到几万块后，每次检索都
-- 扫一遍全表会变成「聊一句话先等两秒」。这是唯一一处「随文档量线性恶化」的路径，
-- 现在加索引成本几乎为零，等真的变慢再回头排查要贵得多。
--
-- 用表达式索引而不是加列：加列要改写入路径（Spring AI 的 PgVectorStore 由框架
-- 生成 INSERT，我们无法插入额外的列），而表达式索引对写入方完全透明。
--
-- json 而非 jsonb 的注意点：json 列上没有默认的 jsonb 运算符类，
-- 但 `->>` 取出的是 text，因此对 `(metadata->>'document_id')` 建 btree 索引是合法的，
-- 且查询里的 `metadata->>'document_id' = ?` 能命中它（已在真实库用 EXPLAIN 验证：
-- Seq Scan → Bitmap Index Scan）。
--
-- 注意：Spring AI 自己会建 `spring_ai_vector_index`（HNSW，给向量相似度搜索用），
-- 与本索引互不相干 —— 那是「找相似向量」，这是「按文档取子集」，别把两者搞混。
CREATE INDEX IF NOT EXISTS idx_vector_store_document_id
    ON vector_store ((metadata->>'document_id'));

COMMENT ON INDEX idx_vector_store_document_id IS
    '按文档检索向量片段；metadata 是 json，故用表达式索引，对框架写入路径透明';
