package com.personalos.calendar.push;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.http.*;
import java.security.*;
import java.util.Base64;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

class WebPushTransportTest {
    @Test void encryptsPayloadAndSignsVapidWithoutSendingToInternet() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "BC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        KeyPair vapid = generator.generateKeyPair();
        KeyPair browser = generator.generateKeyPair();
        var settings = new WebPushSettings(true, Base64.getUrlEncoder().withoutPadding().encodeToString(nl.martijndwars.webpush.Utils.encode((org.bouncycastle.jce.interfaces.ECPublicKey) vapid.getPublic())),
                Base64.getUrlEncoder().withoutPadding().encodeToString(nl.martijndwars.webpush.Utils.encode((org.bouncycastle.jce.interfaces.ECPrivateKey) vapid.getPrivate())), "mailto:test@example.com");
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(201);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var transport = new WebPushTransport(settings, client);
        assertThat(transport.send("https://fcm.googleapis.com/test", Base64.getUrlEncoder().withoutPadding().encodeToString(nl.martijndwars.webpush.Utils.encode((org.bouncycastle.jce.interfaces.ECPublicKey) browser.getPublic())),
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]), "private reminder".getBytes())).isEqualTo(201);
        var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any());
        assertThat(request.getValue().headers().firstValue("Content-Encoding")).contains("aes128gcm");
        assertThat(request.getValue().headers().firstValue("Authorization").orElseThrow()).startsWith("vapid ");
        assertThat(request.getValue().timeout()).contains(java.time.Duration.ofSeconds(10));
        assertThat(request.getValue().bodyPublisher().orElseThrow().contentLength()).isGreaterThan(50);
    }

    @Test void disabledTransportDoesNotUseKeys() throws Exception {
        var transport = new WebPushTransport(new WebPushSettings(false, "", "", ""));
        assertThatThrownBy(() -> transport.send("", "", "", new byte[0])).isInstanceOf(IllegalStateException.class);
    }
}
