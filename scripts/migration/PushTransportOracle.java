package com.personalos.migration;

import tools.jackson.databind.json.JsonMapper;
import com.personalos.calendar.push.WebPushSettings;
import com.personalos.calendar.push.WebPushTransport;
import java.nio.file.Path;
import java.util.Map;

/** Uses the fixed Java production transport, without app state or stored credentials. */
public final class PushTransportOracle {
    public static void main(String[] args) {
        try {
            var mapper = JsonMapper.builder().build();
            var fixture = mapper.readTree(Path.of(args[0]).toFile());
            if (!"P8 paired java transport fixture".equals(fixture.path("marker").asText())) {
                throw new IllegalArgumentException();
            }
            var settings = new WebPushSettings(true, fixture.path("publicKey").asText(),
                    fixture.path("privateKey").asText(), "mailto:fixture@example.com");
            var subscription = fixture.path("subscription");
            var payload = mapper.writeValueAsBytes(Map.of("title", fixture.path("marker").asText(),
                    "body", "Paired transport fixture", "tag", "p8-java", "url", "/calendar"));
            int status = new WebPushTransport(settings).send(subscription.path("endpoint").asText(),
                    subscription.path("keys").path("p256dh").asText(),
                    subscription.path("keys").path("auth").asText(), payload);
            System.out.println("P8_PUSH_STATUS " + status);
            if (status < 200 || status >= 300) System.exit(1);
        } catch (Exception error) {
            System.err.println("Disposable transport failed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
