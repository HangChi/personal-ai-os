package com.personalos.calendar.push;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.web-push.enabled", havingValue = "true")
public class WebPushScheduler {
    private final WebPushService service;
    public WebPushScheduler(WebPushService service) { this.service = service; }

    @Scheduled(fixedDelayString = "${app.web-push.interval-ms:30000}", initialDelay = 15000,
            scheduler = "webPushTaskScheduler")
    public void dispatch() { service.dispatch(); }
}
