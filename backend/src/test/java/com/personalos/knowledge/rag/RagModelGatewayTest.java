package com.personalos.knowledge.rag;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.ai.AiChatClientProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.ObjectMapper;
class RagModelGatewayTest {
    private static org.springframework.ai.chat.model.ChatResponse response(String text) {
        return new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(text))));
    }
    @Test void structuredCallAcceptsFencedJsonAndRejectsOversizedResponses() {
        var provider=mock(AiChatClientProvider.class);var client=mock(ChatClient.class,RETURNS_DEEP_STUBS);
        when(provider.requirePlainChatClient()).thenReturn(client);
        when(client.prompt().system(anyString()).user(anyString()).call().chatResponse()).thenReturn(response("```json\n{\"queries\":[]}\n```"),response("x".repeat(64001)));
        var gateway=new RagModelGateway(provider,new ObjectMapper());
        try {assertThat(gateway.call("rules","payload",1000).path("queries").isArray()).isTrue();
            assertThatThrownBy(()->gateway.call("rules","payload",1000)).hasMessageContaining("Invalid");
            assertThatThrownBy(()->gateway.call("rules","payload",1)).hasMessageContaining("budget");
        } finally {gateway.close();}
    }
    @Test void timeoutInterruptsAuxiliaryCallAndReturnsDegradableFailure() {
        var provider=mock(AiChatClientProvider.class);var client=mock(ChatClient.class,RETURNS_DEEP_STUBS);
        when(provider.requirePlainChatClient()).thenReturn(client);
        when(client.prompt().system(anyString()).user(anyString()).call().chatResponse()).thenAnswer(invocation->{Thread.sleep(5000);return response("{}");});
        var gateway=new RagModelGateway(provider,new ObjectMapper());
        try {long start=System.nanoTime();assertThatThrownBy(()->gateway.call("rules","payload",100)).hasMessageContaining("unavailable");
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-start)).isLessThan(java.time.Duration.ofSeconds(2));
        } finally {gateway.close();}
    }
}
