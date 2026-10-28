package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.ai.AiProperties;
import com.personalos.common.ai.config.ModelProfileService.SaveProfileCommand;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.support.StaticObjectProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 多套配置服务的纯单元测试（无容器、无数据库）。
 *
 * <h2>为什么这些用例必须存在</h2>
 * 本类里有三处「写错了不报错、只表现为行为莫名其妙」的逻辑：
 * <ul>
 *   <li><b>启用顺序</b>：先置 TRUE 再置 FALSE 会撞部分唯一索引。
 *       只有断言「先取消、后启用」这个调用顺序才能钉住它；</li>
 *   <li><b>删除保护</b>：删掉生效行或最后一行会让聊天永久不可用而没有恢复入口；</li>
 *   <li><b>密钥三态</b>：update 时 {@code null} 不改动、空串清空 ——
 *       写错会让「改个模型名就丢了密钥」。</li>
 * </ul>
 */
class ModelProfileServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private AiModelProfileRepository repository;
    private AiModelProfileModelRepository modelRepository;
    private AiModelConfigRepository legacyRepository;
    private ModelConfigService configService;
    private ModelProfileService service;

    /** 内存里的「表」：id → 实体 */
    private final Map<UUID, AiModelProfileEntity> table = new HashMap<>();

    /** 内存里的模型清单：id → 实体（V23） */
    private final Map<Long, AiModelProfileModelEntity> modelTable = new HashMap<>();

    /** 记录 saveAndFlush 的调用顺序，用于断言「先取消、后启用」 */
    private final List<String> flushOrder = new ArrayList<>();

    @BeforeEach
    void setUp() {
        table.clear();
        modelTable.clear();
        flushOrder.clear();

        repository = mock(AiModelProfileRepository.class);
        modelRepository = mock(AiModelProfileModelRepository.class);
        legacyRepository = mock(AiModelConfigRepository.class);
        when(legacyRepository.findSingleton()).thenReturn(Optional.empty());

        // 模型清单的内存替身（V23）。整组替换是唯一的写路径：
        // deleteByProfileId 必须真的把行删掉，否则「删掉的模型还在」这类
        // bug 会被测试放过 —— 与配置表的 delete 替身同一个理由。
        doAnswer(invocation -> {
            UUID profileId = invocation.getArgument(0);
            modelTable.entrySet().removeIf(entry -> profileId.equals(entry.getValue().getProfileId()));
            return null;
        }).when(modelRepository).deleteByProfileId(any(UUID.class));
        when(modelRepository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<AiModelProfileModelEntity> all = invocation.getArgument(0);
            List<AiModelProfileModelEntity> saved = new ArrayList<>();
            for (AiModelProfileModelEntity entity : all) {
                assignModelIdIfMissing(entity);
                modelTable.put(entity.getId(), entity);
                saved.add(entity);
            }
            return saved;
        });
        when(modelRepository.findByProfileIdOrderByKindAscSortOrderAscIdAsc(any(UUID.class)))
                .thenAnswer(invocation -> modelsOf((UUID) invocation.getArgument(0)));
        when(modelRepository.findByProfileIdInOrderByKindAscSortOrderAscIdAsc(any()))
                .thenAnswer(invocation -> {
                    java.util.Collection<UUID> ids = invocation.getArgument(0);
                    List<AiModelProfileModelEntity> result = new ArrayList<>();
                    for (UUID id : ids) {
                        result.addAll(modelsOf(id));
                    }
                    return result;
                });
        when(modelRepository.countByProfileId(any(UUID.class)))
                .thenAnswer(invocation -> (long) modelsOf(invocation.getArgument(0)).size());

        when(repository.save(any(AiModelProfileEntity.class))).thenAnswer(invocation -> {
            AiModelProfileEntity entity = invocation.getArgument(0);
            assignIdIfMissing(entity);
            table.put(entity.getId(), entity);
            return entity;
        });
        when(repository.saveAndFlush(any(AiModelProfileEntity.class))).thenAnswer(invocation -> {
            AiModelProfileEntity entity = invocation.getArgument(0);
            assignIdIfMissing(entity);
            table.put(entity.getId(), entity);
            flushOrder.add(entity.isActive() ? "TRUE:" + entity.getName() : "FALSE:" + entity.getName());
            return entity;
        });
        when(repository.findAllByOrderBySortOrderAscCreatedAtAsc())
                .thenAnswer(invocation -> table.values().stream()
                        .sorted((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()))
                        .toList());
        when(repository.findFirstByActiveTrue()).thenAnswer(invocation -> table.values().stream()
                .filter(AiModelProfileEntity::isActive)
                .findFirst());
        when(repository.findById(any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(table.get(invocation.getArgument(0))));
        when(repository.count()).thenAnswer(invocation -> (long) table.size());
        when(repository.maxSortOrder()).thenAnswer(invocation -> table.values().stream()
                .map(AiModelProfileEntity::getSortOrder)
                .max(Integer::compareTo)
                .orElse(null));
        when(repository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<AiModelProfileEntity> all = invocation.getArgument(0);
            all.forEach(entity -> table.put(entity.getId(), entity));
            return all;
        });
        // 内存替身必须自己把行删掉：mock 的 delete 什么都不做，
        // 会让「删除成功」的断言看起来成立而实际没删（或反过来）。
        doAnswer(invocation -> {
            table.remove(((AiModelProfileEntity) invocation.getArgument(0)).getId());
            return null;
        }).when(repository).delete(any(AiModelProfileEntity.class));

        // 不需要真实模型：本类测试只关心数据库状态与调用顺序
        AiChatClientProvider provider = new AiChatClientProvider(
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(),
                mock(ChatMemory.class), new AiProperties(null, null));
        configService = mock(ModelConfigService.class);
        when(configService.resolveFrom(any())).thenReturn(
                new ModelConfigService.ResolvedConfig(
                        "https://env.example.com", "sk-env", "env-model", null, "env-embed",
                        null, null, Map.of()));

        service = new ModelProfileService(repository, modelRepository, legacyRepository,
                configService, provider, CLOCK);
    }

    // ------------------------------------------------------------------ 新建

    @Test
    @DisplayName("新建配置默认不生效，避免加一套备份配置就把正在用的换掉")
    void createDoesNotActivate() {
        AiModelProfileEntity created = service.create(command("DashScope 主力", "dashscope"));

        assertThat(created.isActive()).isFalse();
        assertThat(created.getRevision()).isEqualTo(1);
        assertThat(created.getPresetId()).isEqualTo("dashscope");
        assertThat(created.getSortOrder()).isZero();
    }

    @Test
    @DisplayName("新建按 maxSortOrder + 1 追加到末尾")
    void createAppendsToEnd() {
        service.create(command("第一套", null));
        AiModelProfileEntity second = service.create(command("第二套", null));

        assertThat(second.getSortOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("名称为空或超长时拒绝，提示语点明是名称问题")
    void createRejectsBadName() {
        assertThatThrownBy(() -> service.create(command("   ", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("名称不能为空");

        String tooLong = "名".repeat(ModelProfileService.NAME_MAX + 1);
        assertThatThrownBy(() -> service.create(command(tooLong, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过");
    }

    @Test
    @DisplayName("名称两端空白被去掉")
    void createTrimsName() {
        assertThat(service.create(command("  带空格的名字  ", null)).getName())
                .isEqualTo("带空格的名字");
    }

    // ------------------------------------------------------------------ 启用

    /**
     * 核心用例：启用的调用顺序必须是「先全部取消并 flush、再置 TRUE」。
     *
     * <p>反过来（先 TRUE 后 FALSE）在真实数据库上会撞部分唯一索引
     * {@code uq_ai_model_profile_single_active}，而症状是一条与业务无关的
     * SQL 唯一约束错误 —— 只有这条断言能防住它。
     */
    @Test
    @DisplayName("启用时先取消旧行并 flush，再激活目标行")
    void activateDeactivatesBeforeActivating() {
        AiModelProfileEntity first = service.create(command("旧配置", null));
        AiModelProfileEntity second = service.create(command("新配置", null));
        table.get(first.getId()).activate(NOW);
        flushOrder.clear();

        service.activate(second.getId());

        assertThat(flushOrder)
                .as("必须先落 FALSE 再落 TRUE，否则中间态有两行 active 会撞唯一索引")
                .containsExactly("FALSE:旧配置", "TRUE:新配置");
        assertThat(table.get(first.getId()).isActive()).isFalse();
        assertThat(table.get(second.getId()).isActive()).isTrue();
    }

    @Test
    @DisplayName("重复启用同一套配置不报错，也不刷 updatedAt")
    void activateSameTwiceIsNoop() {
        AiModelProfileEntity entity = service.create(command("唯一配置", null));
        service.activate(entity.getId());
        Instant afterFirst = table.get(entity.getId()).getUpdatedAt();
        flushOrder.clear();

        service.activate(entity.getId());

        assertThat(flushOrder).as("已是生效状态时不该再写数据库").isEmpty();
        assertThat(table.get(entity.getId()).getUpdatedAt()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("启用不存在的配置抛 404")
    void activateMissingThrowsNotFound() {
        assertThatThrownBy(() -> service.activate(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ 更新

    @Test
    @DisplayName("更新时 apiKey 传 null 保留原密钥")
    void updateNullApiKeyKeepsExisting() {
        AiModelProfileEntity created = service.create(
                new SaveProfileCommand("配置", null, "https://a.example.com", null, "sk-original",
                        "model-a", null, null));
        service.update(created.getId(), new SaveProfileCommand(
                "配置", null, "https://a.example.com", null, null, "model-b", null, null));

        AiModelProfileEntity reloaded = service.require(created.getId());
        assertThat(reloaded.getApiKey()).isEqualTo("sk-original");
        assertThat(reloaded.getChatModel()).isEqualTo("model-b");
    }

    @Test
    @DisplayName("更新时 apiKey 传空串清空，回落到环境变量")
    void updateBlankApiKeyClears() {
        AiModelProfileEntity created = service.create(
                new SaveProfileCommand("配置", null, null, null, "sk-original", "m", null, null));
        service.update(created.getId(), new SaveProfileCommand(
                "配置", null, null, null, "   ", "m", null, null));

        assertThat(service.require(created.getId()).getApiKey()).isNull();
    }

    @Test
    @DisplayName("每次更新推进 revision")
    void updateAdvancesRevision() {
        AiModelProfileEntity created = service.create(command("配置", null));
        assertThat(created.getRevision()).isEqualTo(1);

        service.update(created.getId(), command("配置", null));
        assertThat(service.require(created.getId()).getRevision()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 删除

    /**
     * 删除生效行必须被拒绝。
     *
     * <p>允许的后果是：库里有配置但没一套生效，运行期模型却还在跑它 ——
     * 界面显示「没有生效配置」而聊天还能用，用户无从判断哪个是真的。
     */
    @Test
    @DisplayName("不能删除当前生效的配置")
    void deleteActiveIsRejected() {
        AiModelProfileEntity keep = service.create(command("另一套", null));
        AiModelProfileEntity active = service.create(command("生效中", null));
        service.activate(active.getId());
        assertThat(keep.getId()).isNotNull();

        assertThatThrownBy(() -> service.delete(active.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先切换");
    }

    @Test
    @DisplayName("不能删除最后一套配置")
    void deleteLastOneIsRejected() {
        AiModelProfileEntity only = service.create(command("唯一一套", null));

        assertThatThrownBy(() -> service.delete(only.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少需要保留");
    }

    @Test
    @DisplayName("删除非生效且非最后一套时成功")
    void deleteInactiveSucceeds() {
        AiModelProfileEntity active = service.create(command("生效中", null));
        AiModelProfileEntity spare = service.create(command("备用", null));
        service.activate(active.getId());

        service.delete(spare.getId());

        assertThat(table).doesNotContainKey(spare.getId());
        assertThat(table).containsKey(active.getId());
    }

    // ------------------------------------------------------------------ 排序

    @Test
    @DisplayName("排序按入参顺序重排，未列出的排到末尾")
    void reorderAppliesGivenOrder() {
        AiModelProfileEntity a = service.create(command("A", null));
        AiModelProfileEntity b = service.create(command("B", null));
        AiModelProfileEntity c = service.create(command("C", null));

        service.reorder(List.of(c.getId(), a.getId()));

        assertThat(service.require(c.getId()).getSortOrder()).isZero();
        assertThat(service.require(a.getId()).getSortOrder()).isEqualTo(1);
        assertThat(service.require(b.getId()).getSortOrder()).as("未列出者补到末尾").isEqualTo(2);
    }

    @Test
    @DisplayName("空 id 列表的排序请求是 no-op")
    void reorderEmptyIsNoop() {
        service.create(command("A", null));

        service.reorder(List.of());
        service.reorder(null);

        verify(repository, never()).saveAll(any());
    }

    // ------------------------------------------------------------------ 解析生效配置

    @Test
    @DisplayName("有生效配置时按它解析；没有时回落到旧表")
    void resolveCurrentFallsBackToLegacy() {
        AiModelConfigEntity legacy = mock(AiModelConfigEntity.class);
        when(legacyRepository.findSingleton()).thenReturn(Optional.of(legacy));

        service.resolveCurrent();

        verify(configService).resolveFromLegacy(legacy);
        verify(configService, never()).resolveFrom(any());
    }

    @Test
    @DisplayName("有生效配置时用它解析，不再读旧表")
    void resolveCurrentUsesActiveProfile() {
        AiModelProfileEntity active = service.create(command("生效中", null));
        service.activate(active.getId());

        service.resolveCurrent();

        verify(configService).resolveFrom(active);
        verify(configService, never()).resolveFromLegacy(any());
    }

    @Test
    @DisplayName("rename 只改名字，不推进 revision")
    void renameDoesNotTouchRevision() {
        AiModelProfileEntity created = service.create(command("旧名", null));
        int before = created.getRevision();

        service.rename(created.getId(), "新名");

        assertThat(service.require(created.getId()).getName()).isEqualTo("新名");
        assertThat(service.require(created.getId()).getRevision())
                .as("改名不是配置内容变更，不该推进 revision")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("applyCurrent 把当前生效配置交给 ModelConfigService 应用")
    void applyCurrentDelegatesToConfigService() {
        service.applyCurrent();

        verify(configService).applyResolved(any());
    }

    // ------------------------------------------------------------------ 模型清单（V23）

    @Test
    @DisplayName("新建时清单第一项成为单值列的默认模型")
    void firstChatModelBecomesDefault() {
        AiModelProfileEntity created = service.create(
                new SaveProfileCommand("DashScope", "dashscope", "https://api.example.com", "OPENAI",
                        "sk-test", null, null, null,
                        List.of("qwen-max", "qwen-plus"), List.of("text-embedding-v3")));

        assertThat(created.getChatModel())
                .as("单值列是「默认用哪个」，必须与清单第一项一致，否则界面显示与实际请求会对不上")
                .isEqualTo("qwen-max");
        assertThat(created.getEmbeddingModel()).isEqualTo("text-embedding-v3");
    }

    @Test
    @DisplayName("清单里的空串与重复项被丢弃，顺序保持用户提交的顺序")
    void listIsSanitizedButKeepsOrder() {
        AiModelProfileEntity created = service.create(
                new SaveProfileCommand("c", null, null, "OPENAI", "sk", null, null, null,
                        List.of("  ", "qwen-max", "qwen-plus", "qwen-max"), null));

        assertThat(service.modelsOf(created.getId()))
                .extracting(AiModelProfileModelEntity::getModelName)
                .containsExactly("qwen-max", "qwen-plus");
    }

    @Test
    @DisplayName("清单为空时回落到命令里的单个模型名（兼容 V22 客户端）")
    void emptyListFallsBackToSingleModel() {
        AiModelProfileEntity created = service.create(command("旧客户端", null));

        assertThat(created.getChatModel()).isEqualTo("test-model");
        assertThat(service.modelsOf(created.getId()))
                .extracting(AiModelProfileModelEntity::getModelName)
                .containsExactly("test-model");
    }

    @Test
    @DisplayName("更新时整体替换清单，删掉的模型真的消失")
    void updateReplacesWholeList() {
        AiModelProfileEntity created = service.create(
                new SaveProfileCommand("c", null, null, "OPENAI", "sk", null, null, null,
                        List.of("qwen-max", "qwen-plus"), null));

        service.update(created.getId(), new SaveProfileCommand("c", null, null, "OPENAI", "sk",
                null, null, null, List.of("qwen-turbo"), null));

        assertThat(service.modelsOf(created.getId()))
                .as("增量合并会让「删除」永远不生效，必须整体替换")
                .extracting(AiModelProfileModelEntity::getModelName)
                .containsExactly("qwen-turbo");
    }

    @Test
    @DisplayName("同时提交清单与单值时以清单为准")
    void listWinsOverSingleField() {
        AiModelProfileEntity created = service.create(
                new SaveProfileCommand("c", null, null, "OPENAI", "sk", "old-model", null, null,
                        List.of("new-model"), null));

        assertThat(created.getChatModel())
                .as("两个来源同时生效会造出「界面显示 A、实际用 B」")
                .isEqualTo("new-model");
    }

    @Test
    @DisplayName("modelsOf 把对话与嵌入分开，且对话在前")
    void modelsOfGroupsByKind() {
        AiModelProfileEntity created = service.create(
                new SaveProfileCommand("c", null, null, "OPENAI", "sk", null, null, null,
                        List.of("qwen-max"), List.of("text-embedding-v3")));

        List<AiModelProfileModelEntity> models = service.modelsOf(created.getId());

        assertThat(models).hasSize(2);
        assertThat(models.get(0).kindEnum()).isEqualTo(AiModelProfileModelEntity.Kind.CHAT);
        assertThat(models.get(1).kindEnum()).isEqualTo(AiModelProfileModelEntity.Kind.EMBEDDING);
    }

    @Test
    @DisplayName("批量取清单时不漏任何一套配置")
    void modelsOfBatchCoversAllProfiles() {
        AiModelProfileEntity first = service.create(
                new SaveProfileCommand("a", null, null, "OPENAI", "sk", null, null, null,
                        List.of("m1"), null));
        AiModelProfileEntity second = service.create(
                new SaveProfileCommand("b", null, null, "OPENAI", "sk", null, null, null,
                        List.of("m2"), null));

        Map<UUID, List<AiModelProfileModelEntity>> grouped =
                service.modelsOf(List.of(first, second));

        assertThat(grouped.get(first.getId()))
                .extracting(AiModelProfileModelEntity::getModelName).containsExactly("m1");
        assertThat(grouped.get(second.getId()))
                .extracting(AiModelProfileModelEntity::getModelName).containsExactly("m2");
    }

    @Test
    @DisplayName("modelsOf 对空配置列表返回空 Map，不抛异常")
    void modelsOfEmptyProfiles() {
        assertThat(service.modelsOf(List.of())).isEmpty();
    }

    @Test
    @DisplayName("清单为空且单值列也为空时，清单就是空的（没有模型）")
    void noModelsAtAll() {
        AiModelProfileEntity created = service.create(
                new SaveProfileCommand("c", null, null, "OPENAI", "sk", null, null, null,
                        List.of(), List.of()));

        assertThat(service.modelsOf(created.getId())).isEmpty();
    }

    // ------------------------------------------------------------------ 替身

    /** 内存替身里的「按配置取清单」，排序与真实仓储一致（kind 升序、再按 sortOrder） */
    private List<AiModelProfileModelEntity> modelsOf(UUID profileId) {
        return modelTable.values().stream()
                .filter(entity -> profileId.equals(entity.getProfileId()))
                .sorted((a, b) -> {
                    int byKind = a.getKind().compareTo(b.getKind());
                    return byKind != 0 ? byKind : Integer.compare(a.getSortOrder(), b.getSortOrder());
                })
                .toList();
    }

    /**
     * 给模型清单项补一个 id（理由同 {@link #assignIdIfMissing}）。
     */
    private static void assignModelIdIfMissing(AiModelProfileModelEntity entity) {
        try {
            var field = AiModelProfileModelEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            if (field.get(entity) == null) {
                field.set(entity, MODEL_ID_SEQUENCE.incrementAndGet());
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法为测试模型清单项注入 id", ex);
        }
    }

    /** 模型清单的自增 id */
    private static final java.util.concurrent.atomic.AtomicLong MODEL_ID_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    private static SaveProfileCommand command(String name, String presetId) {
        return new SaveProfileCommand(name, presetId, "https://api.example.com", null, "sk-test",
                "test-model", null, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return (ObjectProvider<T>) new StaticObjectProvider<>(List.of());
    }

    /**
     * 给实体补一个 id。
     *
     * <p>{@code @GeneratedValue} 的 id 在真实持久化时才由 JPA 填充，而这里的仓储是
     * 内存替身、不会代劳。不补的话每次新建出来的 id 都是 null，
     * {@code table} 里只剩一个键为 null 的条目，所有用例都会互相覆盖。
     * 用反射是刻意的最小侵入：为测试在实体上加一个 setter 反而更糟 ——
     * 那会让人在业务代码里也去手工设 id。
     */
    private static void assignIdIfMissing(AiModelProfileEntity entity) {
        try {
            var field = AiModelProfileEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            if (field.get(entity) == null) {
                field.set(entity, UUID.randomUUID());
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法为测试实体注入 id", ex);
        }
    }
}
