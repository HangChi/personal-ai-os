package com.personalos.common;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.knowledge.controller.KnowledgeController;
import com.personalos.knowledge.service.*;
import com.personalos.knowledge.spi.KnowledgeSearchPort;
import com.personalos.memory.controller.MemoryController;
import com.personalos.memory.service.MemoryService;
import com.personalos.chat.controller.ChatAttachmentController;
import com.personalos.chat.service.ChatAttachmentService;
import com.personalos.calendar.controller.CalendarController;
import com.personalos.calendar.service.CalendarEventService;
import com.personalos.common.auth.CsrfController;
import com.personalos.common.ai.WebSource;
import java.util.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;
class ControllerInputBoundaryTest {
 @Test void emptyAndUnreadableKnowledgeUploadsAreRejectedBeforeIndexing() throws Exception {
  var docs=mock(KnowledgeDocumentService.class);
  var controller=new KnowledgeController(docs,mock(KnowledgeSearchPort.class),mock(KnowledgeQaService.class));
  var empty=mock(MultipartFile.class);when(empty.isEmpty()).thenReturn(true);
  for(MultipartFile file:Arrays.asList(null,empty)){
   assertThatThrownBy(()->controller.upload(file,null)).hasMessageContaining("请选择");
   assertThatThrownBy(()->controller.update(UUID.randomUUID(),file)).hasMessageContaining("请选择");
  }
  var unreadable=mock(MultipartFile.class);when(unreadable.getBytes()).thenThrow(new IOException("private"));
  assertThatThrownBy(()->controller.upload(unreadable,null)).hasMessageContaining("读取上传文件失败");
  assertThatThrownBy(()->controller.update(UUID.randomUUID(),unreadable)).hasMessageContaining("读取上传文件失败");
  verifyNoInteractions(docs);
 }
 @Test void nullableMemoryRequestsReachValidationAndPinRequiresBody(){
  var memory=mock(MemoryService.class);var controller=new MemoryController(memory);
  controller.create(null);controller.update("id",null);
  verify(memory).create(null);verify(memory).update("id",null);
  assertThatThrownBy(()->controller.pin("id",null)).hasMessageContaining("pinned");
 }
 @Test void attachmentNamesFallbackAndEncodeUnicodeAndSpaces(){
  var controller=new ChatAttachmentController(mock(ChatAttachmentService.class));
  for(String name:Arrays.asList(null," "))assertThat(controller.content("id",name).getHeaders().getFirst("Content-Disposition")).endsWith("''id");
  assertThat(controller.content("id","资料 a.md").getHeaders().getFirst("Content-Disposition")).contains("%20a.md");
 }
 @Test void calendarOpenBoundsAndAbsentCsrfRemainValid(){
  var service=mock(CalendarEventService.class);var controller=new CalendarController(service);
  controller.search(null," ",0,100);verify(service).search(null,null,0,100);
  assertThat(new CsrfController().token(null).data().headerName()).isEqualTo("X-CSRF-TOKEN");
 }
 @Test void webSourcesRejectCredentialsUnsupportedSchemesAndUnusableHosts(){
  for(String url:List.of("x".repeat(4097),"mailto:demo@example.test","ftp://example.test","https://user@example.test","http:///missing","http://[invalid")){
   assertThat(WebSource.from("title",url,"text")).isNull();
  }
  assertThat(WebSource.from("title","http://example.test"," ")).isNull();
  assertThat(WebSource.from(" ","http://example.test","text").title()).isEqualTo("example.test");
 }
}
