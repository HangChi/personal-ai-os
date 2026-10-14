package com.personalos.knowledge.repository;

import com.personalos.knowledge.entity.KnowledgeDocumentEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 知识库文档仓储。
 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, UUID> {

    /**
     * @return 全部文档，按上传时间倒序
     */
    List<KnowledgeDocumentEntity> findAllByOrderByCreatedAtDesc();
}
