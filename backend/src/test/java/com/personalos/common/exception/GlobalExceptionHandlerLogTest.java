package com.personalos.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;

/**
 * 405 的日志必须带上请求路径。
 *
 * <h3>为什么单独为一行日志写测试</h3>
 * 这行日志原先只记 {@code method} 与 {@code supported}，而
 * {@code supported=[DELETE, PUT]} 这个组合在项目里能对上多处端点
 * （{@code /settings/model/profiles/{id}}、{@code /calendar/events/{id}} 等）。
 *
 * <p>2026-10-29 线上出现每 5 秒一次的 405，靠这行日志**完全无法定位**是哪条 URL ——
 * 只能反查所有控制器的映射去猜。端点路径是这类问题唯一的抓手，缺了它，
 * 日志记了等于没记。所以这里用 Logback 的 {@code ListAppender} 把真实日志行抓出来断言，
 * 而不是「读一遍代码觉得记了就行」。
 */
class GlobalExceptionHandlerLogTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private Logger handlerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        handlerLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("405 日志带出请求路径，否则无法定位是哪条 URL")
    void methodNotSupportedLogsRequestUri() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/settings/model/profiles/42");
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("GET", List.of("PUT", "DELETE"));

        handler.handleMethodNotSupported(ex, request);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        String message = event.getFormattedMessage();
        assertThat(message).contains("/api/v1/settings/model/profiles/42");
        assertThat(message).contains("method=GET");
    }

    @Test
    @DisplayName("日志里不出现查询串：路径已足够定位，查询串可能含用户输入")
    void methodNotSupportedLogsPathWithoutQueryString() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/memories/7");
        request.setQueryString("keyword=我的私密笔记");
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("GET", List.of("DELETE"));

        handler.handleMethodNotSupported(ex, request);

        String message = appender.list.getFirst().getFormattedMessage();
        assertThat(message).contains("/api/v1/memories/7");
        assertThat(message).doesNotContain("我的私密笔记");
    }
}
