package com.personalos.common.auth;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录 / 登出 / 当前用户。
 *
 * <p>不用 Spring 自带的 formLogin：它返回 HTML 重定向，SPA 无法处理。
 * 这里手动校验凭据并写入会话，行为与前端约定的 JSON 契约一致。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录。
     *
     * <p><b>会话固定攻击防护</b>：登录成功后先作废旧会话再建新会话，
     * 保证 session id 在认证前后不同。Spring 的 {@code sessionFixation()} 只对它的
     * 认证过滤器生效，手动登录必须自己做这一步。
     */
    @PostMapping("/login")
    public ApiResponse<CurrentUserDto> login(@Valid @RequestBody LoginRequest request,
                                             HttpServletRequest httpRequest,
                                             HttpServletResponse httpResponse) {
        if (!authService.enabled()) {
            // 认证关闭时登录没有意义：直接告诉调用方当前状态，不制造一个「已登录」的假象
            return ApiResponse.ok(CurrentUserDto.anonymous(false));
        }
        if (!authService.verify(request.username(), request.password())) {
            // 不区分「用户名错」与「密码错」，避免枚举用户名
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        HttpSession existing = httpRequest.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        httpRequest.getSession(true);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                authService.username(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        log.info("用户登录成功 username={}", authService.username());
        return ApiResponse.ok(new CurrentUserDto(authService.username(), true));
    }

    /**
     * 登出。会话失效后再请求受保护接口会重新得到 401。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ApiResponse.ok(null);
    }

    /**
     * 当前登录状态。
     *
     * <p>未登录返回 200 + {@code username: null}，**不是** 401：前端靠它决定
     * 「显示登录页」还是「直接进应用」，若做成 401 会与「会话过期」混为一谈。
     */
    @GetMapping("/me")
    public ApiResponse<CurrentUserDto> me() {
        if (!authService.enabled()) {
            return ApiResponse.ok(CurrentUserDto.anonymous(false));
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                // 关键：匿名 token 的 isAuthenticated() 也是 true，必须单独排除
                && !(authentication instanceof AnonymousAuthenticationToken);
        return ApiResponse.ok(authenticated
                ? new CurrentUserDto(authentication.getName(), true)
                : CurrentUserDto.anonymous(true));
    }
}
