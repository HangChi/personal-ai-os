package com.personalos.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求链路标识：为每个请求分配 {@code requestId}，写进 MDC 与响应头。
 *
 * <h2>为什么需要它</h2>
 * 本项目有 30 多个类会打日志，但在它之前**没有任何字段能把一次请求的多条日志串起来**。
 * 排查「这一轮对话为什么失败」时，只能靠时间戳猜，而 SSE 流一跑就是几十秒，
 * 期间并发的其它请求会把日志搅在一起。有了 requestId，一次 {@code grep} 就能还原全过程。
 *
 * <h2>为什么排在最前面</h2>
 * 优先级设为 {@link Ordered#HIGHEST_PRECEDENCE}：它必须在 Spring Security 之前。
 * 否则「401 被拦下」这类最需要排查的请求，恰恰没有 requestId。
 *
 * <h2>为什么入站值要校验</h2>
 * 头是客户端可控的，直接回显会带来两个问题：**日志注入**（塞入换行符伪造日志行）
 * 与**超长值**（撑爆日志、污染索引）。所以只接受
 * {@code [A-Za-z0-9._-]{1,64}}，不合规就自己生成一个新的 —— 不报错，
 * 因为「透传失败」不该让业务请求失败。
 *
 * <p>格式故意限制为 ASCII：日志检索、跨系统传递（如透传到上游）都不涉及编码问题。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** 对外契约的头名（响应里回显，方便用户报错时提供） */
    public static final String HEADER = "X-Request-Id";

    /** MDC 键名，与 {@code logging.pattern.console} 里的 {@code %X{requestId}} 对应 */
    public static final String MDC_KEY = "requestId";

    /**
     * 入站 requestId 的白名单。
     *
     * <p>长度上限 64 而不是更长：常见实现（如 OpenTelemetry trace id）是 32 位十六进制，
     * 64 已留足余量；再长只会让日志行变宽而不增加可区分性。
     */
    private static final Pattern SAFE_INBOUND = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        // 回显给调用方：用户截图报错时，这一个值就能定位到全部相关日志
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理：Tomcat 线程是复用的，残留会把下一个请求的日志串到本次链路上
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 取入站值，不合规则生成新值。
     *
     * <p>截断到 8 位十六进制：单用户场景下碰撞概率可以忽略，而完整 UUID 会让每行日志
     * 都多出 28 个字符，反而降低可读性。需要跨系统关联时（如透传到上游模型服务），
     * 调用方可以自行传一个更长的合规值进来。
     */
    private String resolve(String inbound) {
        if (inbound != null) {
            String trimmed = inbound.trim();
            if (SAFE_INBOUND.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
