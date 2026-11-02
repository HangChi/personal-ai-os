package com.personalos.common.web;

import com.personalos.common.config.RuntimeRole;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Worker 的业务监听端口不接受请求；健康与指标在独立管理端口。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkerIsolationFilter extends OncePerRequestFilter {
    private final RuntimeRole role;
    public WorkerIsolationFilter(RuntimeRole role) { this.role=role; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        if (role.worker() && !request.getRequestURI().startsWith("/actuator/")) {
            response.setStatus(404); return;
        }
        chain.doFilter(request,response);
    }
}
