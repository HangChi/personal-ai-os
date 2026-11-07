package com.personalos.knowledge.reader;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.ai.AiChatClientProvider;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
class VisionEmptyResponseTest {
 @Test void missingTextMarkerAndNullResponsesNormalizeToEmpty(){
  var provider=mock(AiChatClientProvider.class);var client=mock(ChatClient.class,RETURNS_DEEP_STUBS);when(provider.requirePlainChatClient()).thenReturn(client);
  for(String response:Arrays.asList(null," 无文本 "," 实际文字 ")){
   when(client.prompt().messages(any(Message[].class)).call().content()).thenReturn(response);
   assertThat(new ChatClientVisionTextExtractor(provider).extract(new byte[]{1},"image/png")).isEqualTo(response==null||response.strip().equals("无文本")?"":"实际文字");
  }
 }
}
