package com.personalos.knowledge.rag;

import static org.assertj.core.api.Assertions.*;

import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.util.*;
import org.junit.jupiter.api.Test;

class RagResultContractTest {
    final KnowledgeHitDto a = hit(1, 0, "甲[*]");
    final KnowledgeHitDto b = hit(2, 1, "乙_库");

    static KnowledgeHitDto hit(int id, int chunk, String title) {
        return new KnowledgeHitDto(new UUID(0, id), title, "fixture.md", chunk, "合成原文", .8);
    }

    RagResult result(String status, List<RagResult.Disagreement> conflicts, String clarification) {
        return new RagResult(List.of(a, b), List.of("问题"), List.of(), List.of(), status, conflicts, clarification);
    }

    @Test void hitIdentityUsesDocumentAndChunkInsteadOfScoreOrTitle() {
        assertThat(RagResult.sameHit(a, hit(1, 0, "不同标题"))).isTrue();
        assertThat(RagResult.sameHit(a, hit(2, 0, "甲"))).isFalse();
        assertThat(RagResult.sameHit(a, hit(1, 1, "甲"))).isFalse();
        assertThat(RagResult.sameHit(a, new KnowledgeHitDto(null, "", "", 0, "", null))).isFalse();
    }

    @Test void disagreementIdentityRequiresBothExactQuotesInEitherDirection() {
        var original = new RagResult.Disagreement(a, b, "报价甲", "报价乙", "差异");
        assertThat(RagResult.sameDisagreement(original, new RagResult.Disagreement(a, b, "报价甲", "报价乙", "别的解释"))).isTrue();
        assertThat(RagResult.sameDisagreement(original, new RagResult.Disagreement(b, a, "报价乙", "报价甲", "反向"))).isTrue();
        var other = hit(3, 0, "第三方");
        for (var mismatch : List.of(
                new RagResult.Disagreement(other, b, "报价甲", "报价乙", ""),
                new RagResult.Disagreement(a, other, "报价甲", "报价乙", ""),
                new RagResult.Disagreement(a, b, "伪造甲", "报价乙", ""),
                new RagResult.Disagreement(a, b, "报价甲", "伪造乙", ""),
                new RagResult.Disagreement(b, other, "报价乙", "报价甲", ""),
                new RagResult.Disagreement(b, a, "伪造乙", "报价甲", ""),
                new RagResult.Disagreement(b, a, "报价乙", "伪造甲", ""))) {
            assertThat(RagResult.sameDisagreement(original, mismatch)).as(mismatch.toString()).isFalse();
        }
    }

    @Test void preparedAnswerAndStatusControlGuardedRendering() {
        assertThat(RagResult.legacy(List.of(), "问题").status()).isEqualTo("INSUFFICIENT");
        assertThat(RagResult.legacy(List.of(a), "问题").guarded()).isFalse();
        for (var status : List.of("CONFLICT", "CLARIFY", "UNVERIFIED")) {
            var r = result(status, List.of(), "请确认范围");
            assertThat(r.guarded()).isTrue();
            assertThat(r.withPreparedAnswer(null).guardedAnswer()).contains("可核对的来源", "请确认范围");
            assertThat(r.withPreparedAnswer("  ").guardedAnswer()).contains("[1]", "[2]");
            assertThat(r.withPreparedAnswer("已审核的答复").guardedAnswer()).isEqualTo("已审核的答复");
        }
        assertThat(new RagResult(List.of(), List.of(), List.of(), List.of(), "CLARIFY", List.of(), "").guardedAnswer()).doesNotContain("可核对的来源");
    }

    @Test void conflictRenderingEscapesMarkdownAndPreservesEveryQuotedLine() {
        var outside = hit(9, 0, "未编号来源");
        var c = new RagResult.Disagreement(a, b, String.join(System.lineSeparator(), "甲第一行", "甲第二行"), "乙原文", "");
        var r = result("CONFLICT", List.of(c), "请确认<范围>");
        assertThat(r.guardedAnswer()).contains("同一事项存在不同说法", "> 甲第二行", "[1]", "[2]", "&lt;范围&gt;", "该项暂不能确定唯一结论");
        var multiple = result("CONFLICT", List.of(c, new RagResult.Disagreement(outside, b, "其他原文", "乙原文", "第二项")), "").disagreementText();
        assertThat(multiple).contains("分歧 2", "以上分歧暂不能确定唯一结论", "> 未编号来源：其他原文");
        assertThat(result("READY", List.of(), "").disagreementText()).isEmpty();
        String slash = Character.toString(92);
        assertThat(RagResult.safeText("[*]_<>")).isEqualTo(slash + "[" + slash + "*" + slash + "]" + slash + "_&lt;&gt;");
    }

    @Test void globalIndexesReplaceOnlyUnescapedLocalCitations() {
        String escaped = Character.toString(92) + "[1]";
        var r = result("CLARIFY", List.of(), "").withPreparedAnswer("[1] 与 [2]，保留 [0]、[3] 和 " + escaped);
        assertThat(r.answerWithIndexes(List.of(7, 11))).isEqualTo("[7] 与 [11]，保留 [0]、[3] 和 " + escaped);
        assertThat(r.answerWithIndexes(List.of(1))).isEmpty();
        assertThat(r.answerWithIndexes(Arrays.asList(null, 2))).isEmpty();
        assertThat(r.answerWithIndexes(List.of(1, 0))).isEmpty();
        assertThat(r.answerWithIndexes(List.of(-1, 2))).isEmpty();
    }
}
