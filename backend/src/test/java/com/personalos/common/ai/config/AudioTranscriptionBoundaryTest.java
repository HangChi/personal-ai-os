package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import com.personalos.common.ai.config.dto.TranscriptionResult;

class AudioTranscriptionBoundaryTest {
 @Test void networkFailuresGiveActionableCauses(){
  Throwable[] causes={new UnknownHostException(),new ConnectException(),new HttpTimeoutException("timeout"),new SocketTimeoutException(),new SSLException("tls"),new IOException("proxy TUNNEL"),new IOException(),new IOException(""),new IOException("reset"),new IllegalStateException(),new IllegalStateException(" "),new IllegalStateException("broken")};
  String[] expected={"域名解析","连接被拒绝","超时","超时","TLS","隧道","IOException","IOException","reset","IllegalStateException","IllegalStateException","broken"};
  for(int i=0;i<causes.length;i++){String message=ReflectionTestUtils.invokeMethod(AudioTranscriptionService.class,"describeNetworkError",causes[i]);assertThat(message).contains(expected[i]);}
 }
 @Test void responseShapesAndStatusesPreserveFrontendSemantics(){
  var service=new AudioTranscriptionService(mock(ModelConfigService.class),new ObjectMapper());
  for(int status:new int[]{199,200,204,300,400,401,403,404,413,429,500}){
   for(String body:Arrays.asList(null,"","bad","null","{}","{\"text\":42}","{\"text\":\" \"}","{\"text\":\"hello\"}","{\"data\":{\"text\":\"nested\"}}","x".repeat(250))){
    @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body);
    TranscriptionResult result=ReflectionTestUtils.invokeMethod(service,"interpret",URI.create("https://example.test/v1/audio/transcriptions"),response,8L);
    assertThat(result.unavailable()).isFalse();if(status<200||status>=300)assertThat(result.ok()).isFalse();
    if(status==200&&body!=null&&body.contains("hello"))assertThat(result.text()).isEqualTo("hello");
    if(status==200&&body!=null&&body.contains("nested"))assertThat(result.text()).isEqualTo("nested");
   }
  }
 }
 @Test void unavailableAndEmptyAudioNeverCallTransport() throws Exception{
  var config=mock(ModelConfigService.class);var client=mock(HttpClient.class);var s=new AudioTranscriptionService(config,new ObjectMapper());ReflectionTestUtils.setField(s,"httpClient",client);
  when(config.current()).thenReturn(new ModelConfigService.ResolvedConfig("https://example.test",null,"chat",0d,"embed",null,1,Instant.EPOCH,Map.of()));assertThat(s.available()).isFalse();assertThat(s.transcribe(new byte[]{1},null,null).unavailable()).isTrue();
  when(config.current()).thenReturn(new ModelConfigService.ResolvedConfig("https://example.test",null,"chat",0d,"embed","audio",1,Instant.EPOCH,Map.of()));assertThat(s.blockReason()).contains("API Key");
  when(config.current()).thenReturn(new ModelConfigService.ResolvedConfig("https://example.test","synthetic","chat",0d,"embed","audio",1,Instant.EPOCH,Map.of()));assertThat(s.available()).isTrue();
  assertThat(s.transcribe(null,null,null).ok()).isFalse();assertThat(s.transcribe(new byte[0],null,null).ok()).isFalse();assertThat(s.transcribe(new byte[(int)AudioTranscriptionService.MAX_AUDIO_BYTES+1],null,null).ok()).isFalse();verifyNoInteractions(client);
  when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("reset"));
  assertThat(s.transcribe(new byte[]{1}," "," ").error()).contains("reset");
  assertThat(s.transcribe(new byte[]{1},null,null).error()).contains("reset");
 }
}
