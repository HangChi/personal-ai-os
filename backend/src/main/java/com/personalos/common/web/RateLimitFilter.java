package com.personalos.common.web;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.json.JsonCodec;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 给「会花钱的端点」加限流。
 *
 * <h2>拦哪些、不拦哪些</h2>
 * 只拦会触发模型调用的写接口：
 * <ul>
 *   <li>{@code POST /chat/sessions/*}{@code /stream} —— 对话，最贵的那个；</li>
 *   <li>{@code POST /chat/uploads} —— 附件上传，读图会调多模态模型；</li>
 *   <li>{@code POST /knowledge/documents} —— 文档入库，逐块调嵌入模型。</li>
 * </ul>
 * 其余接口（查询、日程、任务、导出）都是本地数据库操作，不产生外部成本，
 * 拦它们只会在用户翻页翻快了的时候制造莫名其妙的 429。
 *
 * <h2>为什么不按 IP 或用户分桶</h2>
 * 单用户本地应用，只有一个调用方。分桶只会增加「桶泄漏」与「键怎么取」的复杂度。
 * 将来开放公网时必须改成按用户分桶 —— 那时的限流语义是「防止一个用户挤占别人」，
 * 与现在「防止一个客户端失控」不是同一件事。
 *
 * <p>顺序 {@code HIGHEST_PRECEDENCE + 10}：排在 {@link RequestIdFilter} 之后，
 * 这样被限流的请求也能在日志里带上 requestId。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 受保护的路径模式（相对 context path）。改这里等于改「什么算贵」。 */
    private static final List<String> GUARDED_PATHS = List.of(
            "/api/v1/chat/sessions/*/stream",
            "/api/v1/chat/uploads",
            "/api/v1/knowledge/documents");

    private final TokenBucketRateLimiter limiter;
    private final JsonCodec jsonCodec;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RateLimitFilter(TokenBucketRateLimiter limiter, JsonCodec jsonCodec) {
        this.limiter = limiter;
        this.jsonCodec = jsonCodec;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只限 POST：GET 不会触发模型调用，且对 SSE 之外的端点做方法判断成本更低
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return GUARDED_PATHS.stream().noneMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (limiter.tryAcquire()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 只记路径与 method：请求体可能含用户隐私，限流日志不该成为第二个数据副本
        log.warn("模型调用被限流 method={} path={}", request.getMethod(), request.getRequestURI());

        response.setStatus(ErrorCode.RATE_LIMITED.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        // 用与全局异常处理一致的信封，前端 readErrorMessage 才能直接拿到可读文案
        response.getWriter().write(jsonCodec.toJson(ApiResponse.error(ErrorCode.RATE_LIMITED)));
    }
}
