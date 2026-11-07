package com.personalos.chat.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.chat.storage.AttachmentObjectStore;
import com.personalos.common.json.JsonCodec;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;
class ChatAttachmentBoundaryTest {
 @TempDir Path dir;final JsonCodec json=new JsonCodec(JsonMapper.builder().build());
 @Test void remoteMetadataUsesStoredLengthAndCleanupFailureRemainsBestEffort()throws Exception{
  var remote=mock(AttachmentObjectStore.class);ObjectProvider<AttachmentObjectStore> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(remote);var s=new ChatAttachmentService(json,dir.toString(),4097,provider);
  MultipartFile file=mock(MultipartFile.class);when(file.getSize()).thenReturn(3L);when(file.getBytes()).thenReturn(new byte[]{1,2,3});
  var dto=s.store(file);assertThat(dto.filename()).isEqualTo(dto.id());assertThat(dto.contentType()).isEqualTo("application/octet-stream");verify(remote).put(eq(dto.id()),eq(new byte[]{1,2,3}),eq("application/octet-stream"));
  when(remote.size(dto.id())).thenReturn(3L);when(remote.load(dto.id())).thenReturn(new ByteArrayResource(new byte[]{1,2,3}));assertThat(s.readBytes(dto.id())).containsExactly(1,2,3);assertThat(s.verify(Arrays.asList(null,new ChatAttachmentDto(null,null,null,0),new ChatAttachmentDto("bad",null,null,0),new ChatAttachmentDto(dto.id(),"display","type",999)))).containsExactly(new ChatAttachmentDto(dto.id(),"display","type",3));
  String absent=UUID.randomUUID().toString();when(remote.size(absent)).thenReturn(null);assertThat(s.verify(List.of(new ChatAttachmentDto(absent,null,null,4)))).isEmpty();assertThatThrownBy(()->s.load(absent)).hasMessageContaining("不存在");
  s.pruneRemote(Set.of(dto.id()),Instant.EPOCH);verify(remote).prune(Set.of(dto.id()),Instant.EPOCH);doThrow(new IllegalStateException("object unavailable")).when(remote).delete(dto.id());s.deleteAll(Arrays.asList(null,dto,dto));verify(remote,times(2)).delete(dto.id());
  when(file.getSize()).thenReturn(4098L);assertThatThrownBy(()->s.store(file)).hasMessageContaining("4097 字节");
 }
 @Test void localStorageIgnoresMalformedSnapshotsAndRemovesOnlyValidObjects()throws Exception{
  var s=new ChatAttachmentService(json,dir.toString(),1025);assertThatThrownBy(()->s.store(null)).hasMessageContaining("文件为空");MultipartFile file=mock(MultipartFile.class);when(file.isEmpty()).thenReturn(true);assertThatThrownBy(()->s.store(file)).hasMessageContaining("文件为空");
  when(file.isEmpty()).thenReturn(false);when(file.getInputStream()).thenThrow(new IOException("fixture"));assertThatThrownBy(()->s.store(file)).hasMessageContaining("附件写入失败");
  assertThat(s.toJson(null)).isNull();assertThat(s.toJson(List.of())).isNull();for(String text:Arrays.asList(null," ","null","broken"))assertThat(s.fromJson(text)).isEmpty();
  s.delete(null);s.delete("../private");s.deleteAll(null);s.deleteAll(List.of());s.pruneRemote(Set.of(),Instant.EPOCH);
  String id=UUID.randomUUID().toString();Files.write(dir.resolve(id),new byte[]{5});var dto=new ChatAttachmentDto(id,"a","type",99);assertThat(s.verify(List.of(dto)).getFirst().size()).isEqualTo(1);s.deleteAll(Arrays.asList(null,dto));assertThat(Files.exists(dir.resolve(id))).isFalse();s.delete(id);
  Path blocked=dir.resolve(UUID.randomUUID().toString());Files.createDirectories(blocked);Files.writeString(blocked.resolve("child"),"keep");s.delete(blocked.getFileName().toString());assertThat(Files.exists(blocked.resolve("child"))).isTrue();
 }
 @Test void attachmentLimitLabelsAndNullableLogNamesMatchPublicMessages(){
  for(long limit:new long[]{1024,1024*1024}){var service=new ChatAttachmentService(json,dir.toString(),limit);var file=mock(MultipartFile.class);when(file.getSize()).thenReturn(limit+1);assertThatThrownBy(()->service.store(file)).hasMessageContaining(limit==1024?"1KB":"1MB");}
  assertThat((String)org.springframework.test.util.ReflectionTestUtils.invokeMethod(ChatAttachmentService.class,"sanitizeForLog",(String)null)).isEmpty();
 }
}
