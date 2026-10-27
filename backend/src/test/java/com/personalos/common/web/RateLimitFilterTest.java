package com.personalos.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personalos.common.config.RateLimitProperties;
import com.personalos.common.json.JsonCodec;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link RateLimitFilter} 的路径判定与拒绝响应测试。
 *
 * <p>重点不在「限流器算得对不对」（那是 {@link TokenBucketRateLimiterTest}），
 * 而在**拦对了哪些请求**：多拦会造成莫名其妙的 429，少拦则保护形同虚设。
 */
class RateLimitFilterTest {

    /** 容量 1、周期 1 小时：取一次就空，且测试期间不会补回来 */
    private TokenBucketRateLimiter exhaustedLimiter() {
        TokenBucketRateLimiter limiter =
                new TokenBucketRateLimiter(new RateLimitProperties(true, 1, Duration.ofHours(1)));
        assertThat(limiter.tryAcquire()).isTrue();
        return limiter;
    }

    private RateLimitFilter filterWith(TokenBucketRateLimiter limiter) {
        JsonCodec codec = mock(JsonCodec.class);
        when(codec.toJson(any())).thenReturn("{\"code\":42900,\"message\":\"请求过于频繁，请稍后再试\"}");
        return new RateLimitFilter(limiter, codec);
    }

    @Test
    @DisplayName("桶空时对话端点返回 429 与统一信封（前端能直接取到可读文案）")
    void rejectsGuardedPathWith429() throws Exception {
        RateLimitFilter filter = filterWith(exhaustedLimiter());
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/chat/sessions/abc/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> fail("桶已空，不应放行"));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).contains("42900");
    }

    @Test
    @DisplayName("知识库入库同样受限（它会逐块调嵌入模型）")
    void guardCoversKnowledgeUpload() throws Exception {
        RateLimitFilter filter = filterWith(exhaustedLimiter());
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/knowledge/documents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> fail("桶已空，不应放行"));

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("普通写接口不受限流 —— 翻页、建任务翻快了不该 429")
    void allowsUnguardedPath() throws Exception {
        RateLimitFilter filter = filterWith(exhaustedLimiter());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(201));

        assertThat(response.getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("GET 不计入限流（查询不产生模型成本）")
    void skipsNonPostMethods() throws Exception {
        RateLimitFilter filter = filterWith(exhaustedLimiter());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/chat/sessions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(200));

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
