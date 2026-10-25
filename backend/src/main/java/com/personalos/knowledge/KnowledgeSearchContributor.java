package com.personalos.knowledge;

import com.personalos.common.search.SearchContributor;
import com.personalos.common.search.SearchHit;
import com.personalos.common.search.SearchPatterns;
import com.personalos.knowledge.entity.KnowledgeDocumentEntity;
import com.personalos.knowledge.repository.KnowledgeDocumentRepository;
import com.personalos.knowledge.service.VectorStoreInspector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库搜索（阶段 17）。
 *
 * <p>搜标题与文件名；元数据没填满时，用**分块内容**补齐 —— 命中分块时返回的是
 * **文档**而不是单个分块，否则一次搜索会返回几十条同源片段，分组就失去意义。
 */
@Component
public class KnowledgeSearchContributor implements SearchContributor {

    private final KnowledgeDocumentRepository repository;
    private final VectorStoreInspector inspector;

    public KnowledgeSearchContributor(KnowledgeDocumentRepository repository,
                                      VectorStoreInspector inspector) {
        this.repository = repository;
        this.inspector = inspector;
    }

    @Override
    public String moduleName() {
        return "知识库";
    }

    @Override
    public String kind() {
        return "document";
    }

    @Override
    @Transactional(readOnly = true)
    public List<SearchHit> search(String keyword, int limit) {
        String pattern = SearchPatterns.likePattern(keyword);
        String needle = keyword.toLowerCase(Locale.ROOT);
        List<SearchHit> hits = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        repository.findAll(
                (root, query, builder) -> builder.or(
                        builder.like(builder.lower(root.get("title")), pattern, SearchPatterns.ESCAPE),
                        builder.like(builder.lower(root.get("filename")), pattern, SearchPatterns.ESCAPE)),
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent()
                .forEach(document -> {
                    seen.add(document.getId());
                    hits.add(toHit(document,
                            document.getTitle().toLowerCase(Locale.ROOT).contains(needle) ? "标题" : "文件名"));
                });

        if (hits.size() < limit) {
            for (UUID documentId : inspector.findDocumentIdsByContent(keyword)) {
                if (hits.size() >= limit || seen.contains(documentId)) {
                    continue;
                }
                repository.findById(documentId).ifPresent(document -> {
                    seen.add(document.getId());
                    hits.add(toHit(document, "正文"));
                });
            }
        }
        return hits;
    }

    private SearchHit toHit(KnowledgeDocumentEntity document, String matchedField) {
        return new SearchHit("document",
                document.getId().toString(),
                document.getTitle(),
                document.getFilename() + " · " + document.getChunkCount() + " 个分块",
                matchedField);
    }
}
