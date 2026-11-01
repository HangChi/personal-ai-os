package com.personalos.common.auth;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.json.JsonCodec;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * 访问控制（阶段 15）。单用户、会话 Cookie。
 *
 * <p>本地默认关闭 CSRF 以保持既有开发体验；生产 profile 通过
 * {@code HttpSessionCsrfTokenRepository} 开启会话绑定令牌。SPA 从
 * {@code /api/v1/auth/csrf} 获取令牌并发送 {@code X-CSRF-TOKEN} 请求头。
 *
 *
 * <h2>为什么 401 要手写 JSON</h2>
 * Spring Security 的默认行为是 302 到表单登录页（返回 HTML）。SPA 收到 HTML 会解析失败，
 * 症状是「点按钮没反应」。所以 {@code AuthenticationEntryPoint} 必须返回与
 * {@code GlobalExceptionHandler} 一致的信封。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * 始终放行的路径。
     *
     * <p>健康检查进白名单是刻意的：监控探针不该持有凭据，否则「服务挂了」与
     * 「探针没登录」会混成同一个告警。
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/health",
            "/actuator/health",
            "/actuator/health/**",
            "/api/v1/auth/login",
            "/api/v1/auth/logout",
            "/api/v1/auth/me",
            "/api/v1/auth/csrf",
            "/error",
    };

    private final AuthProperties properties;
    private final JsonCodec jsonCodec;
    private final boolean csrfEnabled;

    public SecurityConfig(AuthProperties properties, JsonCodec jsonCodec,
                          @Value("${server.servlet.session.cookie.secure:false}") boolean cookieSecure,
                          @Value("${app.security.csrf-enabled:false}") boolean csrfEnabled) {
        this.properties = properties;
        this.jsonCodec = jsonCodec;
        this.csrfEnabled = csrfEnabled;
        if (properties.enabled() && !cookieSecure) {
            // 不拦启动（本地 http://localhost 也要能登录），但必须在日志里说清楚：
            // 这是「开了认证却仍可能被旁路」的唯一配置组合
            log.warn("已开启访问控制，但会话 Cookie 未设置 Secure（SESSION_COOKIE_SECURE=false）。"
                    + "若通过明文 HTTP 访问，JSESSIONID 会随之发出；对公网提供服务时"
                    + "请置于 HTTPS 反代之后并设置 SESSION_COOKIE_SECURE=true。");
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> {
                    if (!csrfEnabled) {
                        csrf.disable();
                    } else {
                        csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                                // MCP clients are protocol clients rather than browser forms;
                                // authentication still applies when AUTH_ENABLED=true.
                                .ignoringRequestMatchers("/mcp", "/mcp/**");
                    }
                })
                // 不用 Spring 自带的表单登录与 Basic：前端是 SPA，需要 JSON 接口
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // IF_REQUIRED：登录时才建会话；未登录的读请求不产生无用的 session
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(PUBLIC_PATHS).permitAll();
                    if (properties.enabled()) {
                        auth.anyRequest().authenticated();
                    } else {
                        // 默认配置：全部放行，行为与阶段 14 完全一致
                        auth.anyRequest().permitAll();
                    }
                })
                .exceptionHandling(handling -> handling.authenticationEntryPoint(
                        (request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
                            response.getWriter().write(
                                    jsonCodec.toJson(ApiResponse.error(ErrorCode.UNAUTHORIZED)));
                        }).accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
                            response.getWriter().write(
                                    jsonCodec.toJson(ApiResponse.error(ErrorCode.FORBIDDEN)));
                        }));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
