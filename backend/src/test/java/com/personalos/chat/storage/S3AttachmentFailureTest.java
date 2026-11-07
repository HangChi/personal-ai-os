package com.personalos.chat.storage;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.sync.RequestBody;
import com.personalos.common.json.JsonCodec;
class S3AttachmentFailureTest {
 final S3Client client=mock(S3Client.class);final String id=UUID.randomUUID().toString();
 S3AttachmentStore store(boolean cleanup){return new S3AttachmentStore(client,"test-bucket","fixture",cleanup,10);}
 HeadObjectResponse head(String owner,long length){return HeadObjectResponse.builder().contentLength(length).metadata(Map.of("personalos-owner",owner)).build();}
 @Test void sizeAndLoadFailClosedForMissingForeignAndOversizedObjects() throws Exception {
  var s=store(true);
  when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
  assertThat(s.size(id)).isNull();assertThatThrownBy(()->s.load(id).contentLength()).hasMessageContaining("不存在");assertThatThrownBy(()->s.load(id).getInputStream()).hasMessageContaining("不存在");s.delete(id);
  when(client.headObject(any(HeadObjectRequest.class))).thenReturn(head("foreign",5));assertThat(s.size(id)).isNull();s.delete(id);
  verify(client,never()).deleteObject(any(DeleteObjectRequest.class));
  when(client.headObject(any(HeadObjectRequest.class))).thenReturn(head(JsonCodec.sha256Hex("fixture"),11));assertThatThrownBy(()->s.size(id)).hasMessageContaining("上限");
  assertThatThrownBy(()->s.put(id,new byte[11],"text/plain")).hasMessageContaining("上限");
  when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(403).build());assertThatThrownBy(()->s.size(id)).hasMessageContaining("暂不可用");
  when(client.headObject(any(HeadObjectRequest.class))).thenThrow(new IllegalStateException("secret"));assertThatThrownBy(()->s.size(id)).hasMessageNotContaining("secret");
 }
 @Test void objectFailuresAreRedactedAndPruneChecksOwnership() {
  var s=store(true);var owner=JsonCodec.sha256Hex("fixture");var prefix="personalos/"+owner+"/attachments/";
  doThrow(new IllegalStateException("secret")).when(client).putObject(any(PutObjectRequest.class),any(RequestBody.class));assertThatThrownBy(()->s.put(id,new byte[1],"text/plain")).hasMessageNotContaining("secret");
  when(client.headObject(any(HeadObjectRequest.class))).thenReturn(head(owner,5));when(client.getObject(any(GetObjectRequest.class))).thenThrow(new IllegalStateException("secret"));
  assertThatThrownBy(()->s.load(id).getInputStream()).hasMessageContaining("暂不可用");assertThat(s.load(id).getFilename()).isEqualTo(id);assertThat(s.load(id).getDescription()).contains(id);
  var before=Instant.now();var stale=UUID.randomUUID().toString();var kept=UUID.randomUUID().toString();var recent=UUID.randomUUID().toString();
  when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder().contents(
    S3Object.builder().key(prefix+"invalid").lastModified(before.minusSeconds(1)).build(),
    S3Object.builder().key(prefix+kept).lastModified(before.minusSeconds(1)).build(),
    S3Object.builder().key(prefix+recent).lastModified(before).build(),
    S3Object.builder().key(prefix+stale).lastModified(before.minusSeconds(1)).build()).build());
  assertThat(s.prune(Set.of(kept),before)).isEqualTo(1);verify(client).deleteObject(argThat((DeleteObjectRequest r)->r.key().endsWith(stale)));
  when(client.headObject(any(HeadObjectRequest.class))).thenReturn(head("foreign",5));assertThat(s.prune(Set.of(kept),before)).isZero();
  assertThat(store(false).prune(Set.of(),before)).isZero();
  for(String namespace:Arrays.asList(null," "))assertThatThrownBy(()->new S3AttachmentStore(client,"test-bucket",namespace,true,10)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->new S3AttachmentStore(client,"BAD","x",true,10)).isInstanceOf(IllegalArgumentException.class);
 }
}
