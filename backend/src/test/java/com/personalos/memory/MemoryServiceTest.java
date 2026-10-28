package com.personalos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.json.JsonCodec;
import com.personalos.memory.dto.MemoryDto;
import com.personalos.memory.entity.MemoryEntity;
import com.personalos.memory.entity.MemorySource;
import com.personalos.memory.repository.MemoryRepository;
import com.personalos.memory.service.MemoryEmbeddingService;
import com.personalos.memory.service.MemoryEmbeddingStore;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * {@link MemoryService} 单元测试：mock 仓储、向量存储与事务管理器。
 *
 * <p>重点覆盖三件"读代码看不出错"的事：
 * <ol>
 *   <li><b>注入预算</b>——条数上限、字符上限、以及「超预算整条跳过而不是截断」；</li>
 *   <li><b>常驻优先</b>（V26）——pinned 条目必须先占预算，哪怕它很旧；</li>
 *   <li><b>校验顺序</b>——非法标识与空内容都必须**不查库**。</li>
 * </ol>
 *
 * <p>向量相关默认关闭（{@code ranking.enabled=false}）：这些用例关心的是定序与预算，
 * 若开着向量，必须为每条记忆准备相似度桩数据，测的就不再是被测逻辑。
 * 向量路径由 {@code MemoryEmbeddingTest} 单独覆盖。
 *
 * <p>CRUD 的正常路径交给集成测试（真库更真实，且能验证列映射）。
 */
class MemoryServiceTest {

    private MemoryRepository repository;
    private MemoryEmbeddingService embeddingService;
    private MemoryEmbeddingStore embeddingStore;
    private MemoryService service;

    @BeforeEach
    void setUp() {
        repository = mock(MemoryRepository.class);
        embeddingService = mock(MemoryEmbeddingService.class);
        embeddingStore = mock(MemoryEmbeddingStore.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        given(transactionManager.getTransaction(any())).willReturn(mock(TransactionStatus.class));

        // 关闭向量排序：这些用例测定序与预算，不测相似度
        MemoryProperties properties = new MemoryProperties(10, 0.92,
                new MemoryProperties.Ranking(false, null, 30),
                new MemoryProperties.Backfill(false, 20));
        service = new MemoryService(repository, embeddingService, embeddingStore, properties, transactionManager);
    }

    private static MemoryEntity memory(String content, Instant updatedAt) {
        return new MemoryEntity(content, updatedAt);
    }

    private static MemoryEntity memory(String content, Instant updatedAt, boolean pinned) {
        MemoryEntity entity = new MemoryEntity(content, updatedAt);
        entity.setPinned(pinned);
        return entity;
    }

    // ------------------------------------------------------------ 注入渲染

    @Test
    @DisplayName("renderForPrompt(): 无记忆时返回空串（调用方据此完全不追加内容）")
    void renderShouldBeEmptyWhenNoMemory() {
        given(repository.findBySupersededByIsNullOrderByUpdatedAtDesc(any(Pageable.class))).willReturn(List.of());

        assertThat(service.renderForPrompt()).isEmpty();
    }

    @Test
    @DisplayName("renderForPrompt(): 含标题、引导句与编号列表")
    void renderShouldContainHeaderAndItems() {
        given(repository.findBySupersededByIsNullOrderByUpdatedAtDesc(any(Pageable.class)))
                .willReturn(List.of(memory("周报每周五下午交", Instant.parse("2026-10-01T00:00:00Z"))));

        String block = service.renderForPrompt();

        assertThat(block).startsWith("## 长期记忆");
        assertThat(block).contains("用户明确要求你长期记住");
        assertThat(block).contains("1. 周报每周五下午交");
    }

    @Test
    @DisplayName("renderForPrompt(): 只向仓储索取候选窗口大小（不在内存里全表加载再截断）")
    void renderShouldAskRepositoryForCappedPage() {
        List<MemoryEntity> memories = new ArrayList<>();
        for (int i = 1; i <= MemoryService.INJECT_MAX_COUNT; i++) {
            memories.add(memory("第" + i + "条记忆", Instant.parse("2026-10-01T00:00:00Z").plusSeconds(1000 - i)));
        }
        given(repository.findBySupersededByIsNullOrderByUpdatedAtDesc(any(Pageable.class))).willReturn(memories);

        String block = service.renderForPrompt();

        // 关键断言：查询本身就带上限，而不是查全表再在 Java 里 break；
        // 且这个上限必须是**候选窗口**（大于注入上限），否则排序无从谈起
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findBySupersededByIsNullOrderByUpdatedAtDesc(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(MemoryService.INJECT_CANDIDATE_LIMIT);
        assertThat(captor.getValue().getOffset()).isZero();

        assertThat(block).contains("1. 第1条记忆");
        assertThat(block).contains(MemoryService.INJECT_MAX_COUNT + ". 第" + MemoryService.INJECT_MAX_COUNT + "条记忆");
    }

    @Test
    @DisplayName("renderForPrompt(query): 与本轮问题相关的记忆排到最前，哪怕它修改得更早")
    void renderShouldRankByRelevance() {
        Instant older = Instant.parse("2026-09-01T00:00:00Z");
        Instant newer = Instant.parse("2026-10-01T00:00:00Z");
        given(repository.findBySupersededByIsNullOrderByUpdatedAtDesc(any(Pageable.class))).willReturn(List.of(
                memory("代码风格用两空格缩进", newer),
                memory("每周五下午交周报", newer),
                memory("我下午三点以后不安排会议", older)));

        String block = service.renderForPrompt("帮我看看今天下午的会议安排");

        assertThat(block)
                .as("相关度优先于时间：否则问日程时会被无关约束挤出预算")
                .contains("1. 我下午三点以后不安排会议");
    }

    @Test
    @DisplayName("renderForPrompt(query): 常驻条目排在相关度最高之前 —— 否则「常驻」只是文案")
    void renderShouldPutPinnedFirstEvenIfLessRelevant() {
        Instant older = Instant.parse("2026-06-01T00:00:00Z");
        Instant newer = Instant.parse("2026-10-01T00:00:00Z");
        given(repository.findBySupersededByIsNullOrderByUpdatedAtDesc(any(Pageable.class))).willReturn(List.of(
                memory("我下午三点以后不安排会议", newer),
                memory("回复一律用中文", older, true)));

        String block = service.renderForPrompt("帮我看看今天下午的会议安排");

        assertThat(block)
                .as("常驻优先：预算紧张时必须先保住用户明确置顶的那条")
                .contains("1. 回复一律用中文");
    }

    @Test
    @DisplayName("renderForPrompt(query): 查询为空时退化为「最近修改优先」，不做任何重排")
    void renderShouldFallBackToRecencyWhenQueryBlank() {
        given(repository.findBySupersededByIsNullOrderByUpdatedAtDesc(any(Pageable.class))).willReturn(List.of(
                memory("最新的记忆", Instant.parse("2026-10-02T00:00:00Z")),
                memory("较早的记忆", Instant.parse("2026-10-01T00:00:00Z"))));

        String block = service.renderForPrompt("   ");

        assertThat(block).contains("1. 最新的记忆");
        assertThat(block).contains("2. 较早的记忆");
    }

    @Test
    @DisplayName("renderForPrompt(): 字符预算用尽时整条跳过，绝不写半条")
    void renderShouldStopInsteadOfTruncating() {
        // 每条正好 2000 字（单条上限），四条共 8000；预算 6000 → 只能容纳前三条。
        List<MemoryEntity> memories = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String prefix = "标记" + i + "：";
            memories.add(memory(prefix + "x".repeat(2000 - prefix.length()),
                    Instant.parse("2026-10-01T00:00:00Z")));
        }
        given(repository.findBySupersededByIsNullOrderByUpdatedAtDesc(any(Pageable.class))).willReturn(memories);

        String block = service.renderForPrompt();

        assertThat(block).contains("3. 标记3：");
        // 第四条整条不出现（若被截断，会出现「4. 标记4：」加上半截内容）
        assertThat(block).doesNotContain("标记4");
        assertThat(block).doesNotContain("4. ");
    }

    @Test
    @DisplayName("renderForPrompt(): 被注入的条目累计使用次数（用于回答「这条到底有没有用」）")
    void renderShouldRecordUsage() {
        given(repository.findBySupersededByIsNullOrderByUpdatedAtDesc(any(Pageable.class)))
                .willReturn(List.of(memory("周报周五交", Instant.parse("2026-10-01T00:00:00Z"))));

        service.renderForPrompt();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MemoryEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getHitCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------ 常驻

    @Test
    @DisplayName("setPinned(): 非法标识被拒绝且不查库")
    void setPinnedShouldRejectInvalidId() {
        assertThatThrownBy(() -> service.setPinned("bad id!", true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("记忆标识格式非法");

        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("setPinned(): 记忆不存在 → 资源不存在")
    void setPinnedShouldFailWhenMissing() {
        String id = UUID.randomUUID().toString();
        given(repository.findById(UUID.fromString(id))).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.setPinned(id, true))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("setPinned(): 置顶不修改 updated_at —— 它不是内容变更")
    void setPinnedShouldKeepUpdatedAt() {
        String id = UUID.randomUUID().toString();
        Instant updatedAt = Instant.parse("2026-10-01T00:00:00Z");
        MemoryEntity entity = memory("回复一律用中文", updatedAt);
        ReflectionTestUtils.setField(entity, "id", UUID.fromString(id));
        given(repository.findById(UUID.fromString(id))).willReturn(Optional.of(entity));
        given(repository.save(any(MemoryEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

        MemoryDto dto = service.setPinned(id, true);

        assertThat(dto.pinned()).isTrue();
        assertThat(dto.updatedAt()).isEqualTo(updatedAt);
    }

    // ------------------------------------------------------------ 写入与去重

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

        verify(repository, never()).findByContentHash(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("create(): 默认来源是 MANUAL，且 DTO 带上该字段")
    void createMarksManualSource() {
        given(repository.findByContentHash(JsonCodec.sha256Hex("周报周五交"))).willReturn(Optional.empty());
        stubSaveAssigningId();

        assertThat(service.create("周报周五交").source()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("createAuto(): 来源标记为 AUTO —— 页面据此区分模型自行写入的条目")
    void createAutoMarksAutoSource() {
        given(repository.findByContentHash(JsonCodec.sha256Hex("用户在做个人 AI OS 项目"))).willReturn(Optional.empty());
        stubSaveAssigningId();

        assertThat(service.createAuto("用户在做个人 AI OS 项目").source()).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("createAuto(): 内容已存在时不改动其来源（来源以最初写入为准）")
    void createAutoKeepsExistingSource() {
        MemoryEntity existing = new MemoryEntity("周报周五交",
                Instant.parse("2026-10-01T00:00:00Z"), MemorySource.MANUAL);
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        given(repository.findByContentHash(JsonCodec.sha256Hex("周报周五交"))).willReturn(Optional.of(existing));

        assertThat(service.createAuto("周报周五交").source()).isEqualTo("MANUAL");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("create(): 同义改写（近重复）把旧条目标记为已取代，且常驻意图转移到新条目")
    void createShouldSupersedeNearDuplicateAndCarryPin() {
        given(repository.findByContentHash(JsonCodec.sha256Hex("周报改成每周五下班前交"))).willReturn(Optional.empty());
        given(embeddingService.modelTag()).willReturn("test-embedding");
        given(embeddingService.embed(anyString())).willReturn(Optional.of(new float[] {0.1f, 0.2f}));
        given(embeddingStore.save(any(), any(), anyString())).willReturn(true);

        MemoryEntity older = memory("周报每周五下午交", Instant.parse("2026-09-01T00:00:00Z"), true);
        UUID olderId = UUID.randomUUID();
        ReflectionTestUtils.setField(older, "id", olderId);
        given(embeddingStore.findSimilar(any(), eq("test-embedding"), anyDouble(), any()))
                .willReturn(Optional.of(new MemoryEmbeddingStore.SimilarMatch(olderId, 0.97)));
        given(repository.findById(olderId)).willReturn(Optional.of(older));
        stubSaveAssigningId();

        MemoryDto created = service.create("周报改成每周五下班前交");

        assertThat(older.getSupersededBy()).isNotNull()
                .as("旧条目不物理删除，但要指向新条目")
                .isEqualTo(UUID.fromString(created.id()));
        assertThat(older.isPinned()).as("常驻意图随内容转移，否则改一次措辞就悄悄失去常驻资格").isFalse();
        assertThat(created.pinned()).isTrue();
    }

    /** 模拟 JPA 保存：补上生成的主键（否则 Service 内部映射 DTO 时 getId() 为 null） */
    private void stubSaveAssigningId() {
        given(repository.saveAndFlush(any(MemoryEntity.class))).willAnswer(invocation -> {
            MemoryEntity saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            }
            return saved;
        });
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

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("update(): 内容变了就作废旧向量 —— 否则会拿旧文本的向量去比新文本")
    void updateShouldInvalidateStaleVector() {
        String id = UUID.randomUUID().toString();
        MemoryEntity entity = memory("旧内容", Instant.parse("2026-10-01T00:00:00Z"));
        ReflectionTestUtils.setField(entity, "id", UUID.fromString(id));
        given(repository.findById(UUID.fromString(id))).willReturn(Optional.of(entity));
        given(repository.saveAndFlush(any(MemoryEntity.class))).willAnswer(invocation -> invocation.getArgument(0));
        // 嵌入不可用：新向量算不出来
        given(embeddingService.embed(anyString())).willReturn(Optional.empty());

        service.update(id, "新内容");

        verify(embeddingStore).clear(UUID.fromString(id));
        assertThat(entity.getEmbeddingModel()).isNull();
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
