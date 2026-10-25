package com.personalos.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 关键词提取（混合检索的关键词通道入口）。
 *
 * <p>提取规则直接决定关键词通道能救回哪些查询，也决定它会不会引入噪声：
 * 提错一个停用词，就会让大量无关片段挤进候选。
 */
class KeywordExtractorTest {

    @Test
    @DisplayName("提取英文标识符：评测里失败的那几条都能提出来")
    void shouldExtractIdentifiers() {
        assertThat(KeywordExtractor.extract("unknown 和 any 到底差在哪"))
                .containsExactly("unknown", "any");
        assertThat(KeywordExtractor.extract("2PC 有什么问题，3PC 改进了什么"))
                .containsExactly("2pc", "3pc");
        assertThat(KeywordExtractor.extract("同一 IP 哈希分配会有什么副作用"))
                // ip 也算 token：它确实是英文，关键词通道命中 ip_hash 是合理的
                .contains("ip");
        assertThat(KeywordExtractor.extract("watch 的回调什么时候执行"))
                .containsExactly("watch");
    }

    @Test
    @DisplayName("保留标识符里的下划线与特殊字符")
    void shouldKeepIdentifierPunctuation() {
        assertThat(KeywordExtractor.extract("ip_hash 怎么配")).containsExactly("ip_hash");
        assertThat(KeywordExtractor.extract("C++ 和 C# 的区别")).containsExactly("c++", "c#");
        assertThat(KeywordExtractor.extract("Object.defineProperty 为什么不响应"))
                .containsExactly("object.defineproperty");
    }

    @Test
    @DisplayName("过滤英文停用词：它们在任何文档里都出现，只会引入噪声")
    void shouldDropStopWords() {
        assertThat(KeywordExtractor.extract("what is the difference")).containsExactly("difference");
        assertThat(KeywordExtractor.extract("how do I use it")).isEmpty();
        // 停用词被过滤后，实义词仍然保留
        assertThat(KeywordExtractor.extract("what is GIL")).containsExactly("gil");
    }

    @Test
    @DisplayName("纯中文查询得到空列表 —— 刻意边界：中文语义交给向量")
    void chineseOnlyQueryYieldsNothing() {
        assertThat(KeywordExtractor.extract("索引在什么情况下会失效")).isEmpty();
        assertThat(KeywordExtractor.extract("死循环为什么不饿死其他协程")).isEmpty();
    }

    @Test
    @DisplayName("单字母与纯数字都不算 token（没有区分度）")
    void singleLetterAndPureNumberAreNotTokens() {
        assertThat(KeywordExtractor.extract("T 和 U 的区别")).isEmpty();
        // 纯数字在技术文档里随处可见，做关键词匹配只会引入噪声
        assertThat(KeywordExtractor.extract("100 和 2026 年")).isEmpty();
    }

    @Test
    @DisplayName("数字开头的标识符要能完整提取（2PC 不能退化成 PC）")
    void numericLeadingIdentifierKeptWhole() {
        assertThat(KeywordExtractor.extract("3PC 改进了什么")).containsExactly("3pc");
        assertThat(KeywordExtractor.extract("1Panel 怎么部署")).containsExactly("1panel");
    }

    @Test
    @DisplayName("最多 3 个 token：越多越像 AND，越容易一条都不命中")
    void shouldCapTokenCount() {
        List<String> tokens = KeywordExtractor.extract("alpha beta gamma delta epsilon");

        assertThat(tokens).hasSize(3);
        assertThat(tokens).containsExactly("alpha", "beta", "gamma");
    }

    @Test
    @DisplayName("去重且保持出现顺序")
    void shouldDedupeAndKeepOrder() {
        assertThat(KeywordExtractor.extract("Kafka kafka KAFKA 分区")).containsExactly("kafka");
    }

    @Test
    @DisplayName("空输入不抛异常")
    void shouldHandleBlankInput() {
        assertThat(KeywordExtractor.extract(null)).isEmpty();
        assertThat(KeywordExtractor.extract("")).isEmpty();
        assertThat(KeywordExtractor.extract("   ")).isEmpty();
        assertThat(KeywordExtractor.extract("。。。")).isEmpty();
    }
}
