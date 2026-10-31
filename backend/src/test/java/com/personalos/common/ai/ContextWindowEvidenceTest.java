package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ContextWindowEvidenceTest {
    @Test
    void parsesExplicitLimitsWithoutMistakingRequestedCountsForCapacity() {
        var limits = ContextWindowEvidence.limitsFrom(new IllegalArgumentException(
                "This model's maximum context length is 8,192 tokens. However you requested 12000 tokens"));
        assertThat(limits.contextTokens()).isEqualTo(8192);
        assertThat(limits.inputTokens()).isNull();
        assertThat(ContextWindowEvidence.limitsFrom(new IllegalArgumentException("requested a context length of 12000 tokens")).contextTokens()).isNull();
        limits = ContextWindowEvidence.limitsFrom(new IllegalArgumentException("Range of input length should be [1, 30720]"));
        assertThat(limits.inputTokens()).isEqualTo(30720);
        limits = ContextWindowEvidence.limitsFrom(new IllegalArgumentException("prompt is too long: 201000 tokens > 200000 maximum"));
        assertThat(limits.inputTokens()).isEqualTo(200000);
    }

    @Test
    void refusesInvalidLimitsAndUnrelatedErrors() {
        var evidence = new ContextWindowEvidence();
        evidence.rejected(0, "model", new IllegalArgumentException("401 unauthorized; max_input_tokens=8192"), 12000);
        assertThat(evidence.snapshot("model").inputTokens()).isNull();
        assertThat(ContextWindowEvidence.limitsFrom(new IllegalStateException("maximum context length is 0" )).contextTokens()).isNull();
        assertThat(ContextWindowEvidence.limitsFrom(new IllegalStateException("maximum context length is 999999999999999999999" )).contextTokens()).isNull();
    }

    @Test
    void successProvesOnlyALowerBoundAndNeverInflatesTheModelWindow() {
        var evidence = new ContextWindowEvidence();
        evidence.accepted(0, "model", 1000, 1500);
        evidence.accepted(0, "model", 500, 400);
        var result = evidence.snapshot("model");
        assertThat(result.verifiedInputTokens()).isEqualTo(1500);
        assertThat(result.tokenRatio()).isEqualTo(1.5);
        assertThat(result.contextTokens()).isNull();
        assertThat(result.inputTokens()).isNull();
    }

    @Test
    void limitsAreIsolatedByModelAndRuntimeGeneration() {
        var evidence = new ContextWindowEvidence();
        evidence.rejected(0, "small", new IllegalStateException("maximum context length is 8192"), 15000);
        assertThat(evidence.snapshot("small").contextTokens()).isEqualTo(8192);
        assertThat(evidence.snapshot("large").contextTokens()).isNull();
        evidence.reset();
        evidence.accepted(0, "small", 1000, 1200);
        evidence.rejected(0, "small", new IllegalStateException("maximum context length is 4096"), 15000);
        assertThat(evidence.snapshot("small").contextTokens()).isNull();
        assertThat(evidence.snapshot("small").verifiedInputTokens()).isZero();
    }

    @Test
    void unspecifiedOverflowCreatesConservativeBudgetNotAPretendWindow() {
        var evidence = new ContextWindowEvidence();
        evidence.rejected(0, "model", new IllegalStateException("context_length_exceeded"), 12000);
        assertThat(evidence.snapshot("model").inputTokens()).isEqualTo(7800);
        assertThat(evidence.snapshot("model").explicitInputLimit()).isFalse();
        assertThat(evidence.snapshot("model").contextTokens()).isNull();
        evidence.rejected(0, "tiny", new IllegalStateException("context_length_exceeded"), 30);
        assertThat(evidence.snapshot("tiny").inputTokens()).isNull();
    }
}
