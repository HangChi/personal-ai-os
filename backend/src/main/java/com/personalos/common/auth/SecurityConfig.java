package com.personalos.common.auth;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.json.JsonCodec;
import jakarta.servlet.http.HttpServletResponse;
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

/**
 * 访问控制（阶段 15）。单用户、会话 Cookie。
 *
 * <h2>关闭 CSRF 的取舍（这是本类最需要解释的一行）</h2>
 * Spring Security 6+ 在 SPA 场景下要正确工作，需要 {@code SpaCsrfTokenRequestHandler}
 * 加前端读取 {@code XSRF-TOKEN} cookie；用默认 handler 时「cookie 里的 token」与
 * 「校验用的 token」会不一致，表现为时有时无的 403，极难排查。
 *
 * <p>对当前 API 的实际情况：
 * <ul>
 *   <li>所有写接口都要求 {@code Content-Type: application/json}，跨站表单无法构造；</li>
 *   <li>跨站 {@code fetch} 会先被 CORS 预检拦下（本应用没有开放 CORS）；</li>
 *   <li>会话 cookie 设了 {@code SameSite=Lax}，跨站 POST 不会带上它。</li>
 * </ul>
 *
 * <p><b>残余风险</b>：同站子域（{@code evil.example.com} 对 {@code app.example.com}）
 * 不受 SameSite 保护。要彻底消除需启用 CSRF token；该权衡同时写在 README 与
 * {@code specs/015-single-user-auth/spec.md}，不藏在注释里。
 *
 * <h2>为什么 401 要手写 JSON</h2>
 * Spring Security 的默认行为是 302 到表单登录页（返回 HTML）。SPA 收到 HTML 会解析失败，
 * 症状是「点按钮没反应」。所以 {@code AuthenticationEntryPoint} 必须返回与
 * {@code GlobalExceptionHandler} 一致的信封。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

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
            "/error",
    };

    private final AuthProperties properties;
    private final JsonCodec jsonCodec;

    public SecurityConfig(AuthProperties properties, JsonCodec jsonCodec) {
        this.properties = properties;
        this.jsonCodec = jsonCodec;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 理由见类注释：JSON-only API + SameSite=Lax，残余风险已记录
                .csrf(AbstractHttpConfigurer::disable)
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
                        }));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
