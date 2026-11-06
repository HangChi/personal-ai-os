package com.personalos.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.personalos.knowledge.entity.KnowledgeDocumentEntity;
import com.personalos.knowledge.repository.KnowledgeDocumentRepository;
import com.personalos.knowledge.service.VectorStoreInspector;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

class KnowledgeSearchContributorTest {
    private final KnowledgeDocumentRepository repository = mock(KnowledgeDocumentRepository.class);
    private final VectorStoreInspector inspector = mock(VectorStoreInspector.class);
    private final KnowledgeSearchContributor contributor = new KnowledgeSearchContributor(repository, inspector);

    private KnowledgeDocumentEntity document(String title) {
        var document = mock(KnowledgeDocumentEntity.class);
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(document.getTitle()).thenReturn(title);
        when(document.getFilename()).thenReturn("fixture.md");
        when(document.getChunkCount()).thenReturn(2);
        return document;
    }

    @Test
    void shouldBoundBodyCandidatesAndLoadThemInOneBatch() {
        var metadata = document("needle");
        var first = document("first");
        var second = document("second");
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(metadata)));
        var ids = List.of(first.getId(), second.getId());
        when(inspector.findDocumentIdsByContent("needle", 2, Set.of(metadata.getId()))).thenReturn(ids);
        // 仓储批量返回顺序未定义，输出仍遵循正文候选排序。
        when(repository.findAllById(ids)).thenReturn(List.of(second, first));

        var hits = contributor.search("needle", 3);

        assertThat(hits).extracting(hit -> hit.title()).containsExactly("needle", "first", "second");
        assertThat(hits).extracting(hit -> hit.matchedField()).containsExactly("标题", "正文", "正文");
        verify(inspector).findDocumentIdsByContent("needle", 2, Set.of(metadata.getId()));
        verify(repository).findAllById(ids);
        verify(repository, never()).findById(any());
    }

    @Test
    void shouldSkipBodyQueryWhenMetadataFillsLimitOrNoCandidatesExist() {
        var metadata = document("needle");
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(metadata)));
        assertThat(contributor.search("needle", 1)).hasSize(1);
        verifyNoInteractions(inspector);
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        when(inspector.findDocumentIdsByContent(eq("needle"), eq(3), any())).thenReturn(List.of());
        assertThat(contributor.search("needle", 3)).isEmpty();
        verify(repository, never()).findAllById(any());
    }

    @Test
    void shouldReturnEmptyForNonPositiveLimit() {
        assertThat(contributor.search("needle", 0)).isEmpty();
        assertThat(contributor.search("needle", -1)).isEmpty();
        verifyNoInteractions(repository, inspector);
    }
}
