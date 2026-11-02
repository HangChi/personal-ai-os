package com.personalos.common.web;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.json.JsonCodec;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 全局在途请求上限；SSE 持有许可直至异步请求结束，避免只限制“建流”瞬间。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class ModelConcurrencyFilter extends OncePerRequestFilter {
    private final ModelConcurrencyGate gate;
    private final JsonCodec codec;

    public ModelConcurrencyFilter(ModelConcurrencyGate gate, JsonCodec codec) {
        this.gate = gate;
        this.codec = codec;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) { return !ModelRequestPaths.matches(request); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var lease = gate.tryAcquire();
        if (lease == null) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(codec.toJson(ApiResponse.error(ErrorCode.RATE_LIMITED)));
            return;
        }
        Runnable release = lease::close;
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
