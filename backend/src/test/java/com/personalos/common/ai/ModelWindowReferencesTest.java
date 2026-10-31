package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ModelWindowReferencesTest {
    @Test
    void providerSpecificOfficialReferencesRetainExactTokenCounts() {
        var deepseek = ModelWindowReferences.find("deepseek-v4-pro", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        var qwen = ModelWindowReferences.find("qwen3.8-flash", "https://dashscope.aliyuncs.com");
        assertThat(qwen.tokens()).isEqualTo(1_000_000);
        assertThat(qwen.configurationWindow()).isEqualTo(983_616);
        assertThat(ModelWindowReferences.find("deepseek-v4.1-flash", "https://dashscope.aliyuncs.com").tokens()).isEqualTo(1_000_000);
        assertThat(deepseek.tokens()).isEqualTo(1_000_000);
        assertThat(deepseek.url()).contains("help.aliyun.com");
        assertThat(ModelWindowReferences.find("deepseek-v4-pro", "https://api.deepseek.com").tokens()).isEqualTo(1_048_576);
        assertThat(ModelWindowReferences.find("glm-5.3", "https://dashscope.aliyuncs.com").tokens()).isEqualTo(1_048_576);
        assertThat(ModelWindowReferences.find("MiniMax-M2.5", "https://dashscope.aliyuncs.com").tokens()).isEqualTo(204800);
    }
    @Test
    void customOrUnknownModelNamesCannotGetAnInventedReference() {
        assertThat(ModelWindowReferences.find("my-private-model", "https://gateway.example.com")).isNull();
        assertThat(ModelWindowReferences.find("deepseek-v4-pro-custom", "https://gateway.example.com")).isNull();
        assertThat(ModelWindowReferences.find("glm-5.3", "https://gateway.example.com")).isNull();
    }
}
