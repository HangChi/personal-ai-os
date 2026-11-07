package com.personalos.task.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.idempotency.*;
import com.personalos.common.json.JsonCodec;
import com.personalos.task.dto.*;
import com.personalos.task.service.WorkspaceService;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorkspaceControllerBoundaryTest {
 @Test void directAndIdempotentWritesUseDistinctOperationIdentities(){
  var service=mock(WorkspaceService.class);var idem=mock(IdempotencyService.class);
  var codec=new JsonCodec(new ObjectMapper());var controller=new WorkspaceController(service,idem,codec);
  var change=new ProjectChange("project",null,false);
  for(String key:Arrays.asList(null," ","valid-key-123")){
   if(key!=null&&!key.isBlank())when(idem.execute(any(),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
   controller.create(key,change);controller.update(UUID.randomUUID(),key,change);
  }
  verify(service,times(6)).save(any(),eq(change));
  var requests=org.mockito.ArgumentCaptor.forClass(IdempotencyRequest.class);verify(idem,times(2)).execute(requests.capture(),any());
  assertThat(requests.getAllValues().get(0).operation()).startsWith("POST");
  assertThat(requests.getAllValues().get(1).operation()).startsWith("PATCH");
  for(String invalid:List.of("short","invalid key with spaces"))assertThatThrownBy(()->controller.create(invalid,change)).isInstanceOf(BusinessException.class);
  doReturn(IdempotentResult.of(null,IdempotencyOutcome.SUCCESS)).when(idem).execute(any(),any());
  assertThatThrownBy(()->controller.create("valid-key-456",change)).isInstanceOf(BusinessException.class);
 }
}
