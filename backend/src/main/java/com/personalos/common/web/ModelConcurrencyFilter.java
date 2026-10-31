package com.personalos.common.web;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.json.JsonCodec;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 全局在途请求上限；SSE 持有许可直至异步请求结束，避免只限制“建流”瞬间。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class ModelConcurrencyFilter extends OncePerRequestFilter {
    private final Semaphore permits;
    private final JsonCodec codec;

    public ModelConcurrencyFilter(@Value("${app.model-max-concurrent-requests:4}") int maxConcurrent,
                                   JsonCodec codec) {
        if (maxConcurrent < 1) throw new IllegalArgumentException("模型并发上限必须为正数");
        permits = new Semaphore(maxConcurrent);
        this.codec = codec;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) { return !ModelRequestPaths.matches(request); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!permits.tryAcquire()) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(codec.toJson(ApiResponse.error(ErrorCode.RATE_LIMITED)));
            return;
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) permits.release(); };
        try {
            chain.doFilter(request, response);
        } finally {
            if (!request.isAsyncStarted()) {
                release.run();
            } else {
                try {
                    request.getAsyncContext().addListener(new AsyncListener() {
                        public void onComplete(AsyncEvent event) { release.run(); }
                        public void onError(AsyncEvent event) { release.run(); }
                        public void onTimeout(AsyncEvent event) { release.run(); }
                        public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                    });
                } catch (IllegalStateException ex) {
                    release.run(); // 已在注册监听器之前完成。
                }
            }
        }
    }
}
