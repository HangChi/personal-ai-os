package com.personalos.calendar.push;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.web-push.enabled", havingValue = "true")
public class WebPushScheduler {
    private final WebPushService service;
    private final com.personalos.common.config.RuntimeRole role;
    public WebPushScheduler(WebPushService service,com.personalos.common.config.RuntimeRole role) { this.service = service; this.role=role; }

    @Scheduled(fixedDelayString = "${app.web-push.interval-ms:30000}", initialDelay = 15000,
            scheduler = "webPushTaskScheduler")
    public void dispatch() { if (role.allows("delivery")) service.dispatch(); }
}
