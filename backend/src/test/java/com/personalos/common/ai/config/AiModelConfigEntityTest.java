package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 配置实体的纯单测。
 *
 * <p>只测**语义**（null 的含义、revision 起点、整体覆盖），不测 getter/setter：
 * 后者由编译器和 {@code ddl-auto=validate} 保证，写测试只是重复。
 */
class AiModelConfigEntityTest {

    private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);

    @Test
    @DisplayName("新建实体的 revision 为 0，首次覆盖后为 1")
    void revisionStartsAtZeroBeforeFirstSave() {
        AiModelConfigEntity entity = new AiModelConfigEntity(NOW);
        assertThat(entity.getRevision()).isZero();

        entity.overwrite("https://a", "sk", "m", 0.5, "e", LATER);

        // 初值是 0 而不是 1，才能让「首次保存 = revision 1」与字面含义一致
        assertThat(entity.getRevision()).isEqualTo(1);
    }

    @Test
    @DisplayName("每次覆盖都推进 revision 并更新时间")
    void overwriteAdvancesRevisionAndTime() {
        AiModelConfigEntity entity = new AiModelConfigEntity(NOW);
        entity.overwrite(null, null, null, null, null, NOW);
        entity.overwrite(null, null, null, null, null, LATER);

        assertThat(entity.getRevision()).isEqualTo(2);
        assertThat(entity.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    @DisplayName("所有字段可为 null，表示「回落到环境变量」")
    void allFieldsNullable() {
        AiModelConfigEntity entity = new AiModelConfigEntity(NOW);

        entity.overwrite(null, null, null, null, null, NOW);

        assertThat(entity.getBaseUrl()).isNull();
        assertThat(entity.getApiKey()).isNull();
        assertThat(entity.getChatModel()).isNull();
        assertThat(entity.getTemperature()).isNull();
        assertThat(entity.getEmbeddingModel()).isNull();
        assertThat(entity.getId()).isEqualTo(AiModelConfigEntity.SINGLETON_ID);
    }

    /**
     * 整体覆盖而非「只改有值的」。
     *
     * <p>设置页提交整份表单，用户清空某一项就是要「恢复该字段的环境变量取值」。
     * 若改成逐字段跳过 null，清空操作会静默失效 —— 用户看到界面空了，重启后值又回来。
     */
    @Test
    @DisplayName("覆盖时传入的 null 会真的把字段清空")
    void overwriteClearsFieldsWithNull() {
        AiModelConfigEntity entity = new AiModelConfigEntity(NOW);
        entity.overwrite("https://a", "sk", "m", 0.9, "e", NOW);

        entity.overwrite(null, null, null, null, null, LATER);

        assertThat(entity.getBaseUrl()).isNull();
        assertThat(entity.getApiKey()).isNull();
        assertThat(entity.getChatModel()).isNull();
        assertThat(entity.getTemperature()).isNull();
        assertThat(entity.getEmbeddingModel()).isNull();
    }

    @Test
    @DisplayName("温度 0.0 是可存的值，不会被当成「未设置」")
    void zeroTemperatureIsPreserved() {
        AiModelConfigEntity entity = new AiModelConfigEntity(NOW);

        entity.overwrite(null, null, null, 0.0, null, NOW);

        // 用 Double 而非 double 的原因就在这里：基本类型的 0.0 默认值与「没设过」无法区分
        assertThat(entity.getTemperature()).isEqualTo(0.0);
        assertThat(entity.getTemperature()).isNotNull();
    }
}
