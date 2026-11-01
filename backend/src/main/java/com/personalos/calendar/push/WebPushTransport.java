package com.personalos.calendar.push;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Security;
import java.time.Duration;
import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

/** 只借助库构造加密/VAPID 请求，用 JDK 客户端限制连接和请求时长。 */
@Component
public class WebPushTransport implements PushTransport {
    private final RequestFactory factory;
    private final HttpClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public WebPushTransport(WebPushSettings settings) throws java.security.GeneralSecurityException {
        this(settings, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    WebPushTransport(WebPushSettings settings, HttpClient client) throws java.security.GeneralSecurityException {
        this.client = client;
        if (settings.enabled) {
            Security.addProvider(new BouncyCastleProvider());
            factory = new RequestFactory(settings);
        } else {
            factory = null;
        }
    }

    @Override
    public int send(String endpoint, String publicKey, String auth, byte[] payload) throws Exception {
        if (factory == null) throw new IllegalStateException("Web Push 未启用");
        var prepared = factory.prepare(new Notification(endpoint, publicKey, auth, payload, 3600));
        var builder = HttpRequest.newBuilder(URI.create(prepared.getUrl())).timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(prepared.getBody()));
        prepared.getHeaders().forEach(builder::header);
        return client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static final class RequestFactory extends AbstractPushService<RequestFactory> {
        RequestFactory(WebPushSettings settings) throws java.security.GeneralSecurityException {
            super(settings.publicKey, settings.privateKey, settings.subject);
        }

        nl.martijndwars.webpush.HttpRequest prepare(Notification notification) throws Exception {
            return prepareRequest(notification, Encoding.AES128GCM);
        }
    }
}
