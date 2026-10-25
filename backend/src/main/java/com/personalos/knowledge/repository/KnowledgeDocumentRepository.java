package com.personalos.knowledge.repository;

import com.personalos.knowledge.entity.KnowledgeDocumentEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 知识库文档仓储。
 *
 * <p>阶段 16 起追加 {@link JpaSpecificationExecutor}：文档列表要按标题/文件名筛选 + 分页。
 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, UUID>,
        JpaSpecificationExecutor<KnowledgeDocumentEntity> {

    /**
     * @return 全部文档，按上传时间倒序
     */
    List<KnowledgeDocumentEntity> findAllByOrderByCreatedAtDesc();
}
