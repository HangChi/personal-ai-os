package com.personalos.calendar.push;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.time.*;
import java.util.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class WebPushServiceTest {
    final WebPushRepository repository = mock(WebPushRepository.class);
    final PushTransport transport = mock(PushTransport.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    final WebPushSettings settings = new WebPushSettings(true, "public", "private", "mailto:test@example.com");
    final WebPushService service = new WebPushService(settings, repository, transport,
            new JsonCodec(new ObjectMapper()), clock);
    final WebPushRepository.Delivery delivery = new WebPushRepository.Delivery(UUID.randomUUID(), UUID.randomUUID(),
            1, "https://fcm.googleapis.com/test", "key", "auth", "meeting");

    @Test void disabledDoesNotDispatchOrExposeKeys() {
        var disabled = new WebPushService(new WebPushSettings(false, "hidden", "secret", ""), repository,
                transport, new JsonCodec(new ObjectMapper()), clock);
        disabled.dispatch();
        assertThat(disabled.status().enabled()).isFalse();
        assertThat(disabled.status().publicKey()).isNull();
        assertThatThrownBy(() -> disabled.subscribe(delivery.endpoint(), "key", "auth")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository, transport);
        assertThat(service.status().publicKey()).isEqualTo("public");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://fcm.googleapis.com/test", "https://localhost/test", "https://127.0.0.1/test",
            "https://fcm.googleapis.com.evil.test/x", "https://user@fcm.googleapis.com/x",
            "https://fcm.googleapis.com:8443/x", "https://fcm.googleapis.com/x#fragment", "not a url"})
    void rejectsUntrustedEndpoints(String endpoint) {
        assertThatThrownBy(() -> WebPushService.validateEndpoint(endpoint)).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://updates.push.services.mozilla.com/wpush/x", "https://web.push.apple.com/x",
            "https://abc.notify.windows.com/x", "https://fcm.googleapis.com/test"})
    void acceptsBrowserPushPlatforms(String endpoint) { WebPushService.validateEndpoint(endpoint); }

    @Test void validatesKeysAndEnforcesSubscriptionLimit() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        var generator = java.security.KeyPairGenerator.getInstance("EC", "BC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(nl.martijndwars.webpush.Utils.encode((org.bouncycastle.jce.interfaces.ECPublicKey) generator.generateKeyPair().getPublic()));
        String auth = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
        UUID id = UUID.randomUUID();
        when(repository.subscribe(eq(delivery.endpoint()), eq(key), eq(auth), any())).thenReturn(id);
        assertThat(service.subscribe(delivery.endpoint(), key, auth)).isEqualTo(id);
        when(repository.count()).thenReturn(20);
        assertThatThrownBy(() -> service.subscribe(delivery.endpoint(), key, auth)).isInstanceOf(BusinessException.class);
        when(repository.exists(delivery.endpoint())).thenReturn(true);
        assertThat(service.subscribe(delivery.endpoint(), key, auth)).isEqualTo(id);
        assertThatThrownBy(() -> service.subscribe(delivery.endpoint(), "wrong", auth)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.subscribe(delivery.endpoint(), key, "wrong")).isInstanceOf(BusinessException.class);
        service.unsubscribe(delivery.endpoint());
        verify(repository).unsubscribe(delivery.endpoint());
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 404, 410, 400, 403, 429, 500})
    void classifiesTransportResults(int status) throws Exception {
        when(repository.claim(clock.instant())).thenReturn(List.of(delivery));
        when(transport.send(anyString(), anyString(), anyString(), any())).thenReturn(status);
        service.dispatch();
        if (status == 404 || status == 410) {
            verify(repository).unsubscribe(delivery.endpoint());
            verify(repository, never()).finish(any(), any(), any());
        } else {
            String state = status < 300 ? "SENT" : status == 429 || status >= 500 ? "PENDING" : "FAILED";
            verify(repository).finish(eq(delivery), eq(state), eq(clock.instant().plusSeconds(60)));
        }
        var payload = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(transport).send(eq(delivery.endpoint()), eq("key"), eq("auth"), payload.capture());
        assertThat(new String(payload.getValue(), StandardCharsets.UTF_8)).contains("meeting", "/calendar");
    }

    @Test void temporaryFailuresRetryButStopAtLimit() throws Exception {
        var last = new WebPushRepository.Delivery(delivery.subscriptionId(), delivery.reminderId(), 5,
                delivery.endpoint(), "key", "auth", "x".repeat(400));
        when(repository.claim(any())).thenReturn(List.of(last));
        when(transport.send(anyString(), anyString(), anyString(), any())).thenThrow(new java.io.IOException("secret endpoint"));
        service.dispatch();
        verify(repository).finish(eq(last), eq("FAILED"), any());
    }

    @Test void interruptionLeavesLeaseAndPreservesInterruptFlag() throws Exception {
        when(repository.claim(any())).thenReturn(List.of(delivery));
        when(transport.send(anyString(), anyString(), anyString(), any())).thenThrow(new InterruptedException());
        try {
            service.dispatch();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(repository, never()).finish(any(), any(), any());
        } finally { Thread.interrupted(); }
    }

    @Test void missingKeysFailOnlyWhenEnabled() {
        assertThatThrownBy(() -> new WebPushSettings(true, "", "", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebPushSettings(true, "a", "", "mailto:x@y")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebPushSettings(true, "a", "b", "invalid")).isInstanceOf(IllegalArgumentException.class);
    }
}
