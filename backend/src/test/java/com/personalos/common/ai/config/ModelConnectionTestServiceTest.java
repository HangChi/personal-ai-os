package com.personalos.common.ai.config;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.ai.AiChatClientProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
class ModelConnectionTestServiceTest {
    final AiChatClientProvider provider=mock(AiChatClientProvider.class,RETURNS_DEEP_STUBS);
    @Test void unavailableModelReturnsConfigurationAdvice() {
        assertThat(new ModelConnectionTestService(provider).test().ok()).isFalse();
        verify(provider,never()).requirePlainChatClient();
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"","  正常  ","abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"})
    void replyPreviewIsTrimmedAndBounded(String reply) {
        when(provider.isConfigured()).thenReturn(true);when(provider.currentModel()).thenReturn(null);
        when(provider.requirePlainChatClient().prompt().user(anyString()).call().content()).thenReturn(reply);
        var r=new ModelConnectionTestService(provider).test();assertThat(r.ok()).isTrue();
        String expected=reply==null?"":reply.strip();if(expected.length()>60)expected=expected.substring(0,60)+"…";
        assertThat(r.replyPreview()).isEqualTo(expected);assertThat(r.model()).isNull();
    }
    @ParameterizedTest @CsvSource({"401,认证失败","unauthorized,认证失败","403,无权限","forbidden,无权限","404,未找到","not found,未找到","429,被限流","rate limit,被限流"})
    void upstreamErrorsHaveActionableMessages(String message,String expected) {
        when(provider.isConfigured()).thenReturn(true);when(provider.currentModel()).thenReturn(null);
        when(provider.requirePlainChatClient().prompt().user(anyString()).call().content()).thenThrow(new IllegalStateException(message));
        var r=new ModelConnectionTestService(provider).test();assertThat(r.ok()).isFalse();assertThat(r.error()).contains(expected);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","synthetic failure"})
    void absentOrUnknownErrorMessagesAreSafe(String message) {
        when(provider.isConfigured()).thenReturn(true);when(provider.currentModel()).thenReturn(null);
        when(provider.requirePlainChatClient().prompt().user(anyString()).call().content()).thenThrow(new IllegalStateException(message));
        var r=new ModelConnectionTestService(provider).test();assertThat(r.error()).isEqualTo(message==null||message.isBlank()?"IllegalStateException":message);
    }
    @Test void modelNameComesFromEffectiveOptionsAndErrorsAreBounded() {
        when(provider.isConfigured()).thenReturn(true);ChatModel model=mock(ChatModel.class);when(provider.currentModel()).thenReturn(model);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("effective-model").build());
        when(provider.requirePlainChatClient().prompt().user(anyString()).call().content()).thenThrow(new IllegalStateException("x".repeat(301)));
        var r=new ModelConnectionTestService(provider).test();assertThat(r.model()).isEqualTo("effective-model");assertThat(r.error()).isEqualTo("x".repeat(300)+"…");
        when(model.getOptions()).thenReturn(null);assertThat(new ModelConnectionTestService(provider).test().model()).isNotBlank();
    }
}
