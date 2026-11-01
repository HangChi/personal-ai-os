package com.personalos.calendar.push;

import com.personalos.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reminders/push")
public class WebPushController {
    private final WebPushService service;
    public WebPushController(WebPushService service) { this.service = service; }

    @GetMapping
    public ApiResponse<WebPushService.Status> status() { return ApiResponse.ok(service.status()); }

    @PostMapping("/subscriptions")
    public ApiResponse<UUID> subscribe(@Valid @RequestBody Subscribe request) {
        return ApiResponse.ok(service.subscribe(request.endpoint(), request.keys().p256dh(), request.keys().auth()));
    }

    @DeleteMapping("/subscriptions")
    public ApiResponse<Void> unsubscribe(@Valid @RequestBody Endpoint request) {
        service.unsubscribe(request.endpoint());
        return ApiResponse.ok(null);
    }

    public record Subscribe(@NotBlank @Size(max = 2048) String endpoint,
                            @Valid @jakarta.validation.constraints.NotNull Keys keys) { }
    public record Keys(@NotBlank @Size(max = 128) String p256dh, @NotBlank @Size(max = 64) String auth) { }
    public record Endpoint(@NotBlank @Size(max = 2048) String endpoint) { }
}
