package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ModelKeyConverterTest {
    private static String key(int fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }
    @Test void encryptionUsesFreshIvAndAuthenticatesCiphertext() {
        var converter = new ModelKeyConverter(key(1));
        String first = converter.convertToDatabaseColumn("test-key");
        String second = converter.convertToDatabaseColumn("test-key");
        assertThat(first).startsWith("{aesgcm:v1}").doesNotContain("test-key").isNotEqualTo(second);
        assertThat(converter.convertToEntityAttribute(first)).isEqualTo("test-key");
        assertThatThrownBy(() -> new ModelKeyConverter(key(2)).convertToEntityAttribute(first)).isInstanceOf(IllegalStateException.class);
        byte[] bytes = Base64.getDecoder().decode(first.substring("{aesgcm:v1}".length()));
        bytes[bytes.length - 1] ^= 1;
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{aesgcm:v1}" + Base64.getEncoder().encodeToString(bytes)))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void legacyAndDisabledBehaviorPreserveLocalConfiguration() {
        var disabled = new ModelKeyConverter("");
        var enabled = new ModelKeyConverter(key(1));
        assertThat(disabled.convertToDatabaseColumn("legacy-key")).isEqualTo("legacy-key");
        assertThat(enabled.convertToEntityAttribute("legacy-key")).isEqualTo("legacy-key");
        assertThat(enabled.convertToDatabaseColumn(null)).isNull();
        assertThat(enabled.convertToDatabaseColumn("")).isEmpty();
        assertThat(enabled.convertToEntityAttribute(null)).isNull();
        assertThatThrownBy(() -> disabled.convertToEntityAttribute(enabled.convertToDatabaseColumn("key")))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void invalidKeyAndPayloadAreRejectedWithoutLeakingInput() {
        assertThatThrownBy(() -> new ModelKeyConverter("not-base64")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("not-base64");
        assertThatThrownBy(() -> new ModelKeyConverter("YWJj")).isInstanceOf(IllegalArgumentException.class);
        var converter = new ModelKeyConverter(key(1));
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{aesgcm:v1}???")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{aesgcm:v1}YWJj")).isInstanceOf(IllegalStateException.class);
        assertThat(converter.convertToDatabaseColumn("a".repeat(500))).hasSizeLessThan(1000);
        String unicode = "密".repeat(500);
        String encoded = converter.convertToDatabaseColumn(unicode);
        assertThat(encoded).hasSizeLessThan(3000);
        assertThat(converter.convertToEntityAttribute(encoded)).isEqualTo(unicode);
    }
}
