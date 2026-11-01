package com.personalos.common.auth;

import com.personalos.common.api.ApiResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 为 SPA 提供当前会话的 CSRF 令牌。 */
@RestController
@RequestMapping("/api/v1/auth")
public class CsrfController {
    @GetMapping("/csrf")
    public ApiResponse<CsrfTokenDto> token(CsrfToken token) {
        return ApiResponse.ok(token == null ? new CsrfTokenDto(null, "X-CSRF-TOKEN")
                : new CsrfTokenDto(token.getToken(), token.getHeaderName()));
    }

    public record CsrfTokenDto(String token, String headerName) {}
}
