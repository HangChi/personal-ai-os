package com.personalos.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link RequestIdFilter} 单元测试。
 *
 * <p>不启动 Spring、不连数据库：过滤器只依赖 servlet 抽象，用 spring-test 提供的
 * Mock 请求/响应对象即可覆盖全部分支。
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        // 各用例自己断言了清理；这里兜底，失败用例不该把状态带到下一个
        MDC.clear();
    }

    @Test
    @DisplayName("合法的入站 ID：透传、写 MDC、回显响应头，请求结束后从 MDC 移除")
    void passesThroughSafeInboundId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader(RequestIdFilter.HEADER, "trace-abc_123.xy");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringChain = new AtomicReference<>();

        filter.doFilter(request, response, captureMdc(duringChain));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("trace-abc_123.xy");
        assertThat(duringChain.get()).as("请求处理期间应能从 MDC 读到").isEqualTo("trace-abc_123.xy");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).as("结束后必须清理，否则会串到下一个请求").isNull();
    }

    @Test
    @DisplayName("换行注入的入站值被丢弃（否则可以伪造日志行）")
    void rejectsLogInjection() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader(RequestIdFilter.HEADER, "abc\n2026-10-27 INFO 伪造的一行日志");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, captureMdc(new AtomicReference<>()));

        assertThat(response.getHeader(RequestIdFilter.HEADER))
                .doesNotContain("\n")
                .matches("[0-9a-f]{12}");
    }

    @Test
    @DisplayName("超过 64 字符的入站值被丢弃，改为自行生成")
    void rejectsOverlongInboundId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader(RequestIdFilter.HEADER, "a".repeat(65));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, captureMdc(new AtomicReference<>()));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches("[0-9a-f]{12}");
    }

    @Test
    @DisplayName("未带头的请求也会拿到一个 ID")
    void generatesWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringChain = new AtomicReference<>();

        filter.doFilter(request, response, captureMdc(duringChain));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches("[0-9a-f]{12}");
        assertThat(duringChain.get()).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
    }

    /** 在链路执行期间抓一次 MDC，用来证明「写进去了」而不只是「响应头对了」 */
    private FilterChain captureMdc(AtomicReference<String> sink) {
        return (request, response) -> sink.set(MDC.get(RequestIdFilter.MDC_KEY));
    }
}
