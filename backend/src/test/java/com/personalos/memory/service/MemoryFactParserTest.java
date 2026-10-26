package com.personalos.memory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.common.json.JsonCodec;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link MemoryFactParser} 的单元测试。
 *
 * <p>这个解析器最容易出问题：模型会加代码块围栏、加前后缀说明、返回自然语言。
 * 它一旦写错，症状是「静默写进垃圾记忆」——比抛异常难查得多，所以边界要逐一钉住。
 */
class MemoryFactParserTest {

    private final JsonCodec codec = new JsonCodec(JsonMapper.builder().build());

    @Test
    @DisplayName("标准 JSON 数组：原样取出")
    void parsesPlainArray() {
        List<String> facts = MemoryFactParser.parse(codec, "[\"我喜欢用中文回答\",\"每周五交周报\"]", 5);

        assertThat(facts).containsExactly("我喜欢用中文回答", "每周五交周报");
    }

    @Test
    @DisplayName("带 ```json 围栏与前缀说明：剥掉外壳再解析")
    void parsesFencedOutput() {
        String raw = """
                好的，以下是提取结果：
                ```json
                ["用户的时区是东八区"]
                ```
                以上。
                """;

        assertThat(MemoryFactParser.parse(codec, raw, 5)).containsExactly("用户的时区是东八区");
    }

    @Test
    @DisplayName("非 JSON 输出返回空列表（宁可不沉淀，也不写脏数据）")
    void returnsEmptyForNonJson() {
        assertThat(MemoryFactParser.parse(codec, "这段对话没有值得记住的内容。", 5)).isEmpty();
        assertThat(MemoryFactParser.parse(codec, "[这不是合法 JSON}", 5)).isEmpty();
        assertThat(MemoryFactParser.parse(codec, null, 5)).isEmpty();
        assertThat(MemoryFactParser.parse(codec, "[]", 5)).isEmpty();
    }

    @Test
    @DisplayName("过短与过长的条目被剔除")
    void filtersByLength() {
        String tooShort = "好的";
        String tooLong = "x".repeat(MemoryService.CONTENT_MAX_LENGTH + 1);
        List<String> facts = MemoryFactParser.parse(
                codec, "[\"" + tooShort + "\",\"正常长度的一条记忆\",\"" + tooLong + "\"]", 5);

        assertThat(facts).containsExactly("正常长度的一条记忆");
    }

    @Test
    @DisplayName("maxItems 生效，且顺序保持模型给出的顺序")
    void respectsMaxItems() {
        List<String> facts = MemoryFactParser.parse(codec, "[\"第一条有效记忆\",\"第二条有效记忆\",\"第三条有效记忆\"]", 2);

        assertThat(facts).containsExactly("第一条有效记忆", "第二条有效记忆");
    }
}
