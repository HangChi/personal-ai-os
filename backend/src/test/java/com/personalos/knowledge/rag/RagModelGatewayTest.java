package com.personalos.knowledge.rag;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.ai.AiChatClientProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.ObjectMapper;
class RagModelGatewayTest {
    @Test void structuredCallAcceptsFencedJsonAndRejectsOversizedResponses() {
        var provider=mock(AiChatClientProvider.class);var client=mock(ChatClient.class,RETURNS_DEEP_STUBS);
        when(provider.requirePlainChatClient()).thenReturn(client);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("```json\n{\"queries\":[]}\n```","x".repeat(64001));
        var gateway=new RagModelGateway(provider,new ObjectMapper());
        try {assertThat(gateway.call("rules","payload",1000).path("queries").isArray()).isTrue();
            assertThatThrownBy(()->gateway.call("rules","payload",1000)).hasMessageContaining("Invalid");
            assertThatThrownBy(()->gateway.call("rules","payload",1)).hasMessageContaining("budget");
        } finally {gateway.close();}
    }
    @Test void timeoutInterruptsAuxiliaryCallAndReturnsDegradableFailure() {
        var provider=mock(AiChatClientProvider.class);var client=mock(ChatClient.class,RETURNS_DEEP_STUBS);
        when(provider.requirePlainChatClient()).thenReturn(client);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenAnswer(invocation->{Thread.sleep(5000);return "{}";});
        var gateway=new RagModelGateway(provider,new ObjectMapper());
        try {long start=System.nanoTime();assertThatThrownBy(()->gateway.call("rules","payload",100)).hasMessageContaining("unavailable");
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-start)).isLessThan(java.time.Duration.ofSeconds(2));
        } finally {gateway.close();}
    }
}
