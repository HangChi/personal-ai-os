package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ContextWindowEvidenceBoundaryTest {
    @Test void acceptedSamplesAreConservativeBoundedAndScopedToGeneration() {
        var e = new ContextWindowEvidence();
        long epoch = e.generation();
        for (int actual : new int[]{-1, 0, 33554433}) e.accepted(epoch, "m", 256, actual);
        e.accepted(epoch + 1, "m", 256, 1000);
        e.accepted(epoch, null, 256, 1000);
        assertThat(e.snapshot("m").verifiedInputTokens()).isZero();
        e.accepted(epoch, "m", 255, 512);
        assertThat(e.snapshot("m").tokenRatio()).isEqualTo(1.0);
        e.accepted(epoch, "m", 256, 1024);
        e.accepted(epoch, "m", 2048, 256);
        assertThat(e.snapshot("m").verifiedInputTokens()).isEqualTo(1024);
        assertThat(e.snapshot("m").tokenRatio()).isEqualTo(4.0);
        assertThat(e.snapshot((String) null).contextTokens()).isNull();
        for (int i = 0; i < 256; i++) e.accepted(epoch, "other"+i, 256, 512);
        assertThat(e.snapshot("m").verifiedInputTokens()).isZero();
        e.reset();
        e.accepted(epoch, "stale", 256, 1000);
        e.rejected(epoch, "stale", new IllegalStateException("context_length_exceeded"), 4096);
        assertThat(e.generation()).isEqualTo(epoch+1);
        assertThat(e.snapshot(epoch, "stale").inputTokens()).isNull();
    }

    @Test void overflowClassificationAndLimitExtractionHandleAllProviderShapesAndCauseCycles() {
        for (String text : new String[]{"context_length_exceeded", "maximum context length", "context window exceeded", "prompt is too long", "input is too long", "input length should be", "exceeds the context window", "too many input tokens", "max_input_tokens exceeded"}) {
            assertThat(ContextWindowEvidence.isOverflow(new RuntimeException("wrapper", new RuntimeException(text.toUpperCase())))).as(text).isTrue();
        }
        assertThat(ContextWindowEvidence.isOverflow(null)).isFalse();
        assertThat(ContextWindowEvidence.isOverflow(new RuntimeException((String)null))).isFalse();
        assertThat(ContextWindowEvidence.isOverflow(new RuntimeException("max_input_tokens"))).isFalse();
        var a = new RuntimeException("temporary error");
        var b = new RuntimeException("unavailable", a); a.initCause(b);
        assertThat(ContextWindowEvidence.isOverflow(a)).isFalse();
        assertThat(ContextWindowEvidence.limitsFrom(a).contextTokens()).isNull();
        var limits = ContextWindowEvidence.limitsFrom(new RuntimeException("maximum context length: 32,768; context window limit 16,384; max_input_tokens 8192; max_input_tokens 512; maximum input length 4096", new RuntimeException("input length should be [1, 2048]; prompt is too long: 9000 > 1,024 maximum tokens; max_input_tokens 999999999999999999999; max_input_tokens 33554433")));
        assertThat(limits.contextTokens()).isEqualTo(16384);
        assertThat(limits.inputTokens()).isEqualTo(1024);
        assertThat(ContextWindowEvidence.limitsFrom(null)).isEqualTo(new ContextWindowEvidence.Limits(null,null));
    }

    @Test void inferredLimitsNeverOverrideStricterEvidenceAndExplicitLimitsPreserveProvenance() {
        var e = new ContextWindowEvidence(); long g=e.generation();
        e.rejected(g+1, "m", new RuntimeException("input is too long"), 4096);
        e.rejected(g, null, new RuntimeException("input is too long"), 4096);
        e.rejected(g, "m", new RuntimeException("unavailable"), 4096);
        assertThat(e.snapshot("m").inputTokens()).isNull();
        e.rejected(g, "m", new RuntimeException("input is too long"), 512);
        assertThat(e.snapshot("m").inputTokens()).isNull();
        e.accepted(g, "m", 1024, 2048);
        e.rejected(g, "m", new RuntimeException("input is too long"), 2048);
        assertThat(e.snapshot("m").inputTokens()).isNull();
        e.rejected(g, "m", new RuntimeException("input is too long"), 4096);
        assertThat(e.snapshot("m").inputTokens()).isEqualTo(2662);
        assertThat(e.snapshot("m").explicitInputLimit()).isFalse();
        e.rejected(g, "m", new RuntimeException("max_input_tokens exceeded 8192"), 5000);
        assertThat(e.snapshot("m").inputTokens()).isEqualTo(2662);
        assertThat(e.snapshot("m").explicitInputLimit()).isFalse();
        e.rejected(g, "m", new RuntimeException("max_input_tokens exceeded 2048"), 5000);
        e.rejected(g, "m", new RuntimeException("max_input_tokens exceeded 4096"), 5000);
        assertThat(e.snapshot("m").inputTokens()).isEqualTo(2048);
        assertThat(e.snapshot("m").explicitInputLimit()).isTrue();
        e.rejected(g, "m", new RuntimeException("input is too long"), 8192);
        assertThat(e.snapshot("m").explicitInputLimit()).isTrue();
        e.rejected(g, "m", new RuntimeException("maximum context length 8192"), 9000);
        e.rejected(g, "m", new RuntimeException("maximum context length 16384"), 20000);
        assertThat(e.snapshot("m").contextTokens()).isEqualTo(8192);
    }
}
