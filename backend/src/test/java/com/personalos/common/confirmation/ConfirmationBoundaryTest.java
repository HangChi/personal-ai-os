package com.personalos.common.confirmation;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.json.JsonCodec;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
class ConfirmationBoundaryTest {
 @Test void listingTokenStateAndCompareAndSetFailuresAreExplicit(){
  var repository=mock(ConfirmationRepository.class);var registry=mock(ConfirmationRegistry.class);
  var tm=mock(PlatformTransactionManager.class);when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
  var service=new ConfirmationService(repository,registry,mock(JsonCodec.class),tm);var id=UUID.randomUUID();var entity=mock(ConfirmationEntity.class);
  when(repository.findById(id)).thenReturn(Optional.of(entity));when(entity.getToken()).thenReturn("token");when(entity.getExpiresAt()).thenReturn(Instant.now().plusSeconds(600));
  service.list(null,null);service.list("  ",ConfirmationStatus.FAILED);service.list("c",ConfirmationStatus.PENDING);
  verify(repository).findByStatusOrderByCreatedAtDesc(ConfirmationStatus.PENDING);
  verify(repository).findByStatusOrderByCreatedAtDesc(ConfirmationStatus.FAILED);
  for(String token:new String[]{null,"invalid"})assertThatThrownBy(()->service.confirm(id,token)).hasMessageContaining("令牌");
  for(var status:ConfirmationStatus.values())if(status!=ConfirmationStatus.PENDING){
   when(entity.getStatus()).thenReturn(status);assertThatThrownBy(()->service.confirm(id,"token")).hasMessageContaining("该确认已");
  }
  when(entity.getStatus()).thenReturn(ConfirmationStatus.PENDING);
  assertThatThrownBy(()->service.confirm(id,"token")).hasMessageContaining("已被处理");
  assertThatThrownBy(()->service.cancel(id,"token")).hasMessageContaining("已失效");
 }
 @Test void registryRejectsMissingDuplicateAndUnknownActions(){
  assertThatThrownBy(()->new ConfirmationRegistry(List.of())).hasMessageContaining("缺少处理器");
  var handler=mock(ConfirmationActionHandler.class);when(handler.supportedActions()).thenReturn(EnumSet.allOf(ConfirmationAction.class));
  assertThatThrownBy(()->new ConfirmationRegistry(List.of(handler,handler))).hasMessageContaining("多个处理器");
  var registry=new ConfirmationRegistry(List.of(handler));for(var action:ConfirmationAction.values())assertThat(registry.require(action)).isSameAs(handler);
  assertThatThrownBy(()->registry.require(null)).hasMessageContaining("没有处理器");
 }
}
