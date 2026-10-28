package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 多配置实体的纯单测。
 *
 * <p>只测**语义**（revision 起点、改名与改配置的区别、active 标志），
 * 不测 getter —— 那由编译器与 {@code ddl-auto=validate} 保证。
 */
class AiModelProfileEntityTest {

    private static final Instant NOW = Instant.parse("2026-10-28T06:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);

    @Test
    @DisplayName("新建实体 revision 为 0 且不生效")
    void newEntityStartsFresh() {
        AiModelProfileEntity entity = new AiModelProfileEntity("测试", null, 0, NOW);

        assertThat(entity.getRevision()).isZero();
        assertThat(entity.isActive()).isFalse();
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("首次 overwrite 后 revision 为 1，之后逐次递增")
    void overwriteAdvancesRevisionFromZero() {
        AiModelProfileEntity entity = new AiModelProfileEntity("测试", null, 0, NOW);

        entity.overwrite("测试", "dashscope", "https://a", "sk", "m", 0.5, "e", NOW);
        // 初值是 0 而不是 1，首次覆盖得到的正是 1，与「这是第几次保存」一致
        assertThat(entity.getRevision()).isEqualTo(1);

        entity.overwrite("测试", "dashscope", "https://a", "sk", "m", 0.5, "e", LATER);
        assertThat(entity.getRevision()).isEqualTo(2);
        assertThat(entity.getUpdatedAt()).isEqualTo(LATER);
    }

    /**
     * 改名不推进 revision。
     *
     * <p>revision 表达的是「配置内容改了几次」。把改名也算进去，会让
     * 前端「保存后回读确认 revision 变了没」这类判断误以为内容变了。
     */
    @Test
    @DisplayName("rename 只改名字与时间，不动 revision")
    void renameLeavesRevisionAlone() {
        AiModelProfileEntity entity = new AiModelProfileEntity("旧名", null, 3, NOW);
        entity.overwrite("旧名", null, "https://a", "sk", "m", null, null, NOW);

        entity.rename("新名", LATER);

        assertThat(entity.getName()).isEqualTo("新名");
        assertThat(entity.getRevision()).isEqualTo(1);
        assertThat(entity.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    @DisplayName("activate / deactivate 只动 active 标志")
    void activeFlagToggles() {
        AiModelProfileEntity entity = new AiModelProfileEntity("测试", null, 0, NOW);

        entity.activate(LATER);
        assertThat(entity.isActive()).isTrue();
        assertThat(entity.getUpdatedAt()).isEqualTo(LATER);

        entity.deactivate(LATER);
        assertThat(entity.isActive()).isFalse();
    }

    @Test
    @DisplayName("reorder 既不推进 revision 也不改更新时间")
    void reorderTouchesNothingButOrder() {
        AiModelProfileEntity entity = new AiModelProfileEntity("测试", null, 0, NOW);
        entity.overwrite("测试", null, "https://a", "sk", "m", null, null, NOW);
        Instant before = entity.getUpdatedAt();

        entity.reorder(7);

        assertThat(entity.getSortOrder()).isEqualTo(7);
        assertThat(entity.getRevision()).isEqualTo(1);
        assertThat(entity.getUpdatedAt()).as("纯展示顺序，不是内容变更").isEqualTo(before);
    }

    @Test
    @DisplayName("所有配置字段可为 null，表示回落到环境变量")
    void configFieldsAreNullable() {
        AiModelProfileEntity entity = new AiModelProfileEntity("测试", null, 0, NOW);
        entity.overwrite("测试", null, null, null, null, null, null, NOW);

        assertThat(entity.getBaseUrl()).isNull();
        assertThat(entity.getApiKey()).isNull();
        assertThat(entity.getChatModel()).isNull();
        assertThat(entity.getTemperature()).isNull();
        assertThat(entity.getEmbeddingModel()).isNull();
        assertThat(entity.getPresetId()).isNull();
    }
}
