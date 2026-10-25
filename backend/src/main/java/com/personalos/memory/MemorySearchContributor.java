package com.personalos.memory;

import com.personalos.common.search.SearchContributor;
import com.personalos.common.search.SearchHit;
import com.personalos.common.search.SearchPatterns;
import com.personalos.memory.entity.MemoryEntity;
import com.personalos.memory.repository.MemoryRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 长期记忆搜索（阶段 17）。
 *
 * <p>记忆只有「内容」一个可搜字段。标题取内容前若干字 ——
 * 记忆本来就是一句话，硬造一个标题反而要多一层截断规则。
 */
@Component
public class MemorySearchContributor implements SearchContributor {

    /** 作为标题展示的内容长度 */
    private static final int TITLE_LENGTH = 40;

    private final MemoryRepository memoryRepository;

    public MemorySearchContributor(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Override
    public String moduleName() {
        return "记忆";
    }

    @Override
    public String kind() {
        return "memory";
    }

    @Override
    @Transactional(readOnly = true)
    public List<SearchHit> search(String keyword, int limit) {
        String pattern = SearchPatterns.likePattern(keyword);
        return memoryRepository.findAll(
                (root, query, builder) ->
                        builder.like(builder.lower(root.get("content")), pattern, SearchPatterns.ESCAPE),
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "updatedAt")))
                .getContent().stream().map(memory -> new SearchHit(
                "memory",
                memory.getId().toString(),
                title(memory.getContent()),
                "最近更新 " + memory.getUpdatedAt().toString().substring(0, 10),
                "内容")).toList();
    }

    private static String title(String content) {
        return content.length() <= TITLE_LENGTH ? content : content.substring(0, TITLE_LENGTH) + "…";
    }
}
