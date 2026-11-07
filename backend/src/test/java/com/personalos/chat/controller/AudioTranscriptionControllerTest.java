package com.personalos.chat.controller;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.ai.config.AudioTranscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;
class AudioTranscriptionControllerTest {
 @Test void capabilityAndUploadErrorsAreDistinct()throws Exception{
  var service=mock(AudioTranscriptionService.class);var controller=new AudioTranscriptionController(service);
  assertThat(controller.status().data().available()).isTrue();when(service.blockReason()).thenReturn("not configured");assertThat(controller.status().data().available()).isFalse();
  var file=mock(MultipartFile.class);
  assertThatThrownBy(()->controller.transcribe(null)).hasMessageContaining("不能为空");
  when(file.isEmpty()).thenReturn(true);assertThatThrownBy(()->controller.transcribe(file)).hasMessageContaining("不能为空");
  when(file.isEmpty()).thenReturn(false);when(file.getBytes()).thenThrow(new java.io.IOException());
  assertThatThrownBy(()->controller.transcribe(file)).hasMessageContaining("读取");
  doReturn(new byte[]{1}).when(file).getBytes();controller.transcribe(file);verify(service).transcribe(new byte[]{1},null,null);
 }
}
