package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.ai.config.dto.*;
import com.personalos.common.exception.BusinessException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModelConfigControllerBoundaryTest {
 @Test void rejectsInvalidFieldsBeforeSaveOrRuntimeSwitch(){
  var service=mock(ModelConfigService.class);var controller=new ModelConfigController(service,mock(ModelConnectionTestService.class));
  var requests=new ArrayList<SaveModelConfigRequest>();requests.add(null);
  requests.add(new SaveModelConfigRequest("x".repeat(501),null,null,null,null));
  requests.add(new SaveModelConfigRequest(null,"x".repeat(501),null,null,null));
  requests.add(new SaveModelConfigRequest(null,null,"x".repeat(121),null,null));
  requests.add(new SaveModelConfigRequest(null,null,null,null,"x".repeat(121)));
  requests.add(new SaveModelConfigRequest(null,null,null,-0.1,null));
  requests.add(new SaveModelConfigRequest(null,null,null,2.1,null));
  requests.add(new SaveModelConfigRequest("file:///etc/passwd",null,null,null,null));
  for(var request:requests)assertThatThrownBy(()->controller.save(request)).isInstanceOf(BusinessException.class);
  verifyNoInteractions(service);
 }
 @Test void savesBeforeApplyingAndMasksEveryResponse(){
  var service=mock(ModelConfigService.class);var probe=mock(ModelConnectionTestService.class);var controller=new ModelConfigController(service,probe);
  var resolved=new ModelConfigService.ResolvedConfig("https://example.test","synthetic-secret-api-key","chat",0.5,"embed",2,Instant.EPOCH,Map.of());
  when(service.save(any())).thenReturn(resolved);when(service.resolve()).thenReturn(resolved);
  for(String base:Arrays.asList(null,""," "," http://localhost ","https://example.test")){
   for(Double temp:Arrays.asList(null,0d,2d)){
    clearInvocations(service);var response=controller.save(new SaveModelConfigRequest(base,null,null,temp,null));
    assertThat(response.data().apiKeyMasked()).doesNotContain("synthetic-secret-api-key");
    var order=inOrder(service);order.verify(service).save(any());order.verify(service).applyToRuntime(resolved);
   }
  }
  assertThat(controller.get().data().hasApiKey()).isTrue();
  var result=new ModelConnectionTestResult(true,5,"chat","ok",null);when(probe.test()).thenReturn(result);assertThat(controller.test().data()).isSameAs(result);
  doThrow(new IllegalStateException("reload failed")).when(service).applyToRuntime(resolved);
  assertThatThrownBy(()->controller.save(new SaveModelConfigRequest(null,null,null,null,null))).hasMessage("reload failed");
 }
}
