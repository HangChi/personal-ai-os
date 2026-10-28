package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模型清单项实体的单元测试。
 *
 * <p>这个类几乎只有 getter，但 {@link AiModelProfileModelEntity#kindEnum()}
 * 值得单独测：它有一条「脏数据回落」的分支，而那条分支的失败方式是**静默的**
 * —— 嵌入模型被当成对话模型显示出来，界面上看不出任何异常，
 * 只是那一栏永远是空的。
 */
class AiModelProfileModelEntityTest {

    private static final Instant NOW = Instant.parse("2026-10-28T06:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static AiModelProfileModelEntity model(AiModelProfileModelEntity.Kind kind,
                                                   String name, int sortOrder) {
        return new AiModelProfileModelEntity(PROFILE_ID, kind, name, sortOrder, NOW);
    }

    @Test
    @DisplayName("构造后各字段就位，kind 以字符串落库")
    void constructorStoresFields() {
        AiModelProfileModelEntity entity = model(AiModelProfileModelEntity.Kind.CHAT, "qwen-max", 3);

        assertThat(entity.getProfileId()).isEqualTo(PROFILE_ID);
        assertThat(entity.getKind()).isEqualTo("CHAT");
        assertThat(entity.getModelName()).isEqualTo("qwen-max");
        assertThat(entity.getSortOrder()).isEqualTo(3);
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("kindEnum 把 CHAT 解析为 CHAT")
    void kindEnumParsesChat() {
        assertThat(model(AiModelProfileModelEntity.Kind.CHAT, "m", 0).kindEnum())
                .isEqualTo(AiModelProfileModelEntity.Kind.CHAT);
    }

    @Test
    @DisplayName("kindEnum 把 EMBEDDING 解析为 EMBEDDING")
    void kindEnumParsesEmbedding() {
        assertThat(model(AiModelProfileModelEntity.Kind.EMBEDDING, "text-embedding-3-small", 0).kindEnum())
                .isEqualTo(AiModelProfileModelEntity.Kind.EMBEDDING);
    }

    @Test
    @DisplayName("kindEnum 遇到空值回落到 CHAT，而不是抛异常")
    void kindEnumFallsBackToChatWhenNull() {
        AiModelProfileModelEntity entity = model(AiModelProfileModelEntity.Kind.CHAT, "m", 0);
        setKind(entity, null);

        assertThat(entity.kindEnum())
                .as("库层有 CHECK 约束，走到这里说明数据被手工改过；"
                        + "回落到 CHAT 至少能让它显示在某一栏，抛异常会让整个列表打不开")
                .isEqualTo(AiModelProfileModelEntity.Kind.CHAT);
    }

    @Test
    @DisplayName("kindEnum 遇到未知值回落到 CHAT")
    void kindEnumFallsBackToChatWhenUnknown() {
        AiModelProfileModelEntity entity = model(AiModelProfileModelEntity.Kind.CHAT, "m", 0);
        setKind(entity, "VISION");

        assertThat(entity.kindEnum()).isEqualTo(AiModelProfileModelEntity.Kind.CHAT);
    }

    @Test
    @DisplayName("Kind 枚举恰好三个值（V24 加入 AUDIO）")
    void kindHasExactlyThreeValues() {
        assertThat(AiModelProfileModelEntity.Kind.values())
                .as("增加取值必须同时改 ai_model_profile_model 的 CHECK 约束"
                        + "（V23 建表时只有 CHAT/EMBEDDING，V24 才放开到 AUDIO），"
                        + "否则写入会被库层拒绝 —— 表现为一个看不出原因的 500")
                .containsExactly(AiModelProfileModelEntity.Kind.CHAT,
                        AiModelProfileModelEntity.Kind.EMBEDDING,
                        AiModelProfileModelEntity.Kind.AUDIO);
    }

    @Test
    @DisplayName("AUDIO 能被 kindEnum 正确识别（不是被回落成 CHAT）")
    void kindEnumRecognizesAudio() {
        AiModelProfileModelEntity entity = model(AiModelProfileModelEntity.Kind.AUDIO,
                "paraformer-v2", 0);

        assertThat(entity.kindEnum()).isEqualTo(AiModelProfileModelEntity.Kind.AUDIO);
    }

    /** 反射改 kind：实体没有 setter（清单不可变），只有构造器能写 */
    private static void setKind(AiModelProfileModelEntity entity, String kind) {
        try {
            var field = AiModelProfileModelEntity.class.getDeclaredField("kind");
            field.setAccessible(true);
            field.set(entity, kind);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法设置 kind", ex);
        }
    }
}
