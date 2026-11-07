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

 @Test void nullResponsesAndNullableUsageNeverInventTokens(){
  var provider=mock(AiChatClientProvider.class);var client=mock(ChatClient.class,RETURNS_DEEP_STUBS);when(provider.requirePlainChatClient()).thenReturn(client);
  for(int shape=0;shape<5;shape++){
   org.springframework.ai.chat.model.ChatResponse value=null;
   if(shape>0){value=mock(org.springframework.ai.chat.model.ChatResponse.class);if(shape>1){var metadata=mock(org.springframework.ai.chat.metadata.ChatResponseMetadata.class);when(value.getMetadata()).thenReturn(metadata);if(shape>2){var usage=mock(org.springframework.ai.chat.metadata.Usage.class);when(metadata.getUsage()).thenReturn(usage);when(usage.getPromptTokens()).thenReturn(shape==3?null:-1);when(usage.getCompletionTokens()).thenReturn(shape==3?null:2);when(value.getResult()).thenReturn(response("{}").getResult());}}}
   when(client.prompt().system(anyString()).user(anyString()).call().chatResponse()).thenReturn(value);var gateway=new RagModelGateway(provider,new ObjectMapper());var meters=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();gateway.metrics(meters);
   try{if(shape<3)assertThatThrownBy(()->gateway.call("rules","payload",1000)).hasMessageContaining("Invalid");else{assertThat(gateway.call("rules","payload",1000).isObject()).isTrue();if(shape==4){assertThat(meters.get("personalos.knowledge.rag.tokens").tag("kind","input").counter().count()).isZero();assertThat(meters.get("personalos.knowledge.rag.tokens").tag("kind","output").counter().count()).isEqualTo(2);}}}finally{gateway.close();meters.close();}
  }
  var gateway=new RagModelGateway(provider,new ObjectMapper());Thread.currentThread().interrupt();try{assertThatThrownBy(()->gateway.call("rules","payload",1000)).hasMessageContaining("interrupted");}finally{Thread.interrupted();gateway.close();}
 }
}
