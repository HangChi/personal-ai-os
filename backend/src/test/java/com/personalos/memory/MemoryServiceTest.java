package com.personalos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.memory.entity.MemoryEntity;
import com.personalos.memory.repository.MemoryRepository;
import com.personalos.memory.service.MemoryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/**
 * {@link MemoryService} 单元测试：只 mock 仓储。
 *
 * <p>重点覆盖两件"读代码看不出错"的事：
 * <ol>
 *   <li><b>注入预算</b>——条数上限、字符上限、以及「超预算整条跳过而不是截断」；</li>
 *   <li><b>校验顺序</b>——非法标识与空内容都必须**不查库**。</li>
 * </ol>
 * CRUD 的正常路径交给集成测试（真库更真实，且能验证列映射）。
 */
class MemoryServiceTest {

    private MemoryRepository repository;
    private MemoryService service;

    @BeforeEach
    void setUp() {
        repository = mock(MemoryRepository.class);
        service = new MemoryService(repository);
    }

    private static MemoryEntity memory(String content, Instant updatedAt) {
        return new MemoryEntity(content, updatedAt);
    }

    // ------------------------------------------------------------ 注入渲染

    @Test
    @DisplayName("renderForPrompt(): 无记忆时返回空串（调用方据此完全不追加内容）")
    void renderShouldBeEmptyWhenNoMemory() {
        given(repository.findByOrderByUpdatedAtDesc(any(Pageable.class))).willReturn(List.of());

        assertThat(service.renderForPrompt()).isEmpty();
    }

    @Test
    @DisplayName("renderForPrompt(): 含标题、引导句与编号列表")
    void renderShouldContainHeaderAndItems() {
        given(repository.findByOrderByUpdatedAtDesc(any(Pageable.class)))
                .willReturn(List.of(memory("周报每周五下午交", Instant.parse("2026-10-01T00:00:00Z"))));

        String block = service.renderForPrompt();

        assertThat(block).startsWith("## 长期记忆");
        assertThat(block).contains("用户明确要求你长期记住");
        assertThat(block).contains("1. 周报每周五下午交");
    }

    @Test
    @DisplayName("renderForPrompt(): 只向仓储索取注入上限条数（不在内存里全表加载再截断）")
    void renderShouldAskRepositoryForCappedPage() {
        List<MemoryEntity> memories = new ArrayList<>();
        // 仓库按 updated_at 倒序返回；这里按同样的顺序构造，序号 1 是最新的
        for (int i = 1; i <= MemoryService.INJECT_MAX_COUNT; i++) {
            memories.add(memory("第" + i + "条记忆", Instant.parse("2026-10-01T00:00:00Z").plusSeconds(1000 - i)));
        }
        given(repository.findByOrderByUpdatedAtDesc(any(Pageable.class))).willReturn(memories);

        String block = service.renderForPrompt();

        // 关键断言：查询本身就带上限，而不是查全表再在 Java 里 break
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByOrderByUpdatedAtDesc(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(MemoryService.INJECT_MAX_COUNT);
        assertThat(captor.getValue().getOffset()).isZero();

        assertThat(block).contains("1. 第1条记忆");
        assertThat(block).contains(MemoryService.INJECT_MAX_COUNT + ". 第" + MemoryService.INJECT_MAX_COUNT + "条记忆");
    }

    @Test
    @DisplayName("renderForPrompt(): 字符预算用尽时整条跳过，绝不写半条")
    void renderShouldStopInsteadOfTruncating() {
        // 每条正好 2000 字（单条上限），四条共 8000；预算 6000 → 只能容纳前三条。
        // 注意按「总长度正好 2000」构造：加了前缀之后若写成 repeat(2000)，每条会是 2004，
        // 预算只能容纳两条 —— 那样断言「第三条存在」会失败，而失败原因与被测逻辑无关。
        List<MemoryEntity> memories = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String prefix = "标记" + i + "：";
            memories.add(memory(prefix + "x".repeat(2000 - prefix.length()),
                    Instant.parse("2026-10-01T00:00:00Z")));
        }
        given(repository.findByOrderByUpdatedAtDesc(any(Pageable.class))).willReturn(memories);

        String block = service.renderForPrompt();

        assertThat(block).contains("3. 标记3：");
        // 第四条整条不出现（若被截断，会出现「4. 标记4：」加上半截内容）
        assertThat(block).doesNotContain("标记4");
        // 且没有残缺的编号列表项
        assertThat(block).doesNotContain("4. ");
    }

    // ------------------------------------------------------------ 校验顺序

    @Test
    @DisplayName("create(): 空内容与超长内容被拒绝，且不查重、不写入")
    void createShouldRejectInvalidContent() {
        assertThatThrownBy(() -> service.create("   "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("记忆内容不能为空");
        assertThatThrownBy(() -> service.create(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("记忆内容不能为空");
        assertThatThrownBy(() -> service.create("x".repeat(MemoryService.CONTENT_MAX_LENGTH + 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2000");

        verify(repository, never()).findFirstByContent(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("update(): 非法标识被拒绝，且不查库")
    void updateShouldRejectInvalidIdBeforeQuery() {
        assertThatThrownBy(() -> service.update("bad id!", "内容"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("记忆标识格式非法");
        assertThatThrownBy(() -> service.update("not-a-uuid", "内容"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("记忆标识格式非法");

        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("update(): 内容非法时不查库（顺序：标识 → 内容 → 存在性）")
    void updateShouldValidateContentBeforeQuery() {
        String validId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> service.update(validId, "   "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("记忆内容不能为空");

        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("update(): 记忆不存在 → 资源不存在，且不写入")
    void updateShouldFailWhenMissing() {
        String id = UUID.randomUUID().toString();
        given(repository.findById(UUID.fromString(id))).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, "新内容"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("delete(): 记忆不存在 → 资源不存在")
    void deleteShouldFailWhenMissing() {
        String id = UUID.randomUUID().toString();
        given(repository.existsById(UUID.fromString(id))).willReturn(false);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).deleteById(any());
    }
}
