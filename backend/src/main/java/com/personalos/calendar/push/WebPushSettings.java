package com.personalos.calendar.push;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 默认关闭；密钥从环境配置注入，不写入数据库、不在状态接口返回私钥。 */
@Component
public class WebPushSettings {
    final boolean enabled;
    final String publicKey;
    final String privateKey;
    final String subject;

    public WebPushSettings(@Value("${app.web-push.enabled:false}") boolean enabled,
            @Value("${app.web-push.public-key:}") String publicKey,
            @Value("${app.web-push.private-key:}") String privateKey,
            @Value("${app.web-push.subject:}") String subject) {
        if (enabled && (publicKey.isBlank() || privateKey.isBlank()
                || !(subject.startsWith("mailto:") || subject.startsWith("https://")))) {
            throw new IllegalArgumentException("启用 Web Push 需要公私钥和 mailto:/https:// 联系地址");
        }
        this.enabled = enabled;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.subject = subject;
    }
}
