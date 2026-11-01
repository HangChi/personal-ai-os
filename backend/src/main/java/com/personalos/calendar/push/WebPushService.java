package com.personalos.calendar.push;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WebPushService {
    private static final Logger log = LoggerFactory.getLogger(WebPushService.class);
    private static final Set<String> HOSTS = Set.of("fcm.googleapis.com", "updates.push.services.mozilla.com",
            "web.push.apple.com");
    private final WebPushSettings settings;
    private final WebPushRepository repository;
    private final PushTransport transport;
    private final JsonCodec json;
    private final Clock clock;

    public WebPushService(WebPushSettings settings, WebPushRepository repository, PushTransport transport,
                          JsonCodec json, Clock clock) {
        this.settings = settings;
        this.repository = repository;
        this.transport = transport;
        this.json = json;
        this.clock = clock;
    }

    public Status status() {
        return new Status(settings.enabled, settings.enabled ? settings.publicKey : null);
    }

    public UUID subscribe(String endpoint, String key, String auth) {
        if (!settings.enabled) throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "服务端尚未启用后台推送");
        validateEndpoint(endpoint);
        try {
            byte[] publicKey = Base64.getUrlDecoder().decode(key);
            if (publicKey.length != 65 || publicKey[0] != 4 || Base64.getUrlDecoder().decode(auth).length != 16) {
                throw new IllegalArgumentException();
            }
            nl.martijndwars.webpush.Utils.loadPublicKey(key);
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推送订阅密钥无效");
        }
        if (!repository.exists(endpoint) && repository.count() >= 20) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "最多订阅 20 个浏览器，请先取消旧订阅");
        }
        return repository.subscribe(endpoint, key, auth, clock.instant());
    }

    public void unsubscribe(String endpoint) { repository.unsubscribe(endpoint); }

    /** 端点由浏览器推送平台生成，拒绝任意 URL 与重定向，避免请求内部服务。 */
    static void validateEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !(HOSTS.contains(host) || host.endsWith(".notify.windows.com"))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的浏览器推送端点");
        }
    }

    public void dispatch() {
        if (!settings.enabled) return;
        var now = clock.instant();
        repository.prune(now);
        repository.enqueue(now);
        for (var delivery : repository.claim(now)) {
            String state = "PENDING";
            try {
                validateEndpoint(delivery.endpoint());
                // 有界标题，避免超过推送平台约 4KB 的负载上限。
                String title = delivery.title().substring(0, Math.min(delivery.title().length(), 120));
                byte[] payload = json.toJson(Map.of("title", title, "body", "你有一条到点提醒",
                        "tag", delivery.reminderId().toString(), "url", "/calendar")).getBytes(StandardCharsets.UTF_8);
                int status = transport.send(delivery.endpoint(), delivery.publicKey(), delivery.auth(), payload);
                if (status == 404 || status == 410) {
                    repository.unsubscribe(delivery.endpoint());
                    continue;
                }
                state = status >= 200 && status < 300 ? "SENT"
                        : status == 429 || status >= 500 ? "PENDING" : "FAILED";
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                // 端点/密钥与异常信息均不写日志；失败状态可从数据库检查。
                log.warn("后台提醒投递失败，将按策略重试 id={}", delivery.reminderId());
            }
            if (delivery.attempts() >= 5 && state.equals("PENDING")) state = "FAILED";
            repository.finish(delivery, state, clock.instant().plusSeconds(30L << delivery.attempts()));
        }
    }

    public record Status(boolean enabled, String publicKey) { }
}
