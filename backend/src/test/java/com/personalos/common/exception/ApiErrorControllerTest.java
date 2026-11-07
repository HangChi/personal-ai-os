package com.personalos.common.exception;

import static org.assertj.core.api.Assertions.*;
import com.personalos.common.api.ErrorCode;
import jakarta.servlet.RequestDispatcher;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiErrorControllerTest {
 @Test void containerErrorsUseSafeStatusAndConsistentEnvelope(){
  var expected=Map.of(400,ErrorCode.VALIDATION_FAILED,404,ErrorCode.RESOURCE_NOT_FOUND,405,ErrorCode.METHOD_NOT_ALLOWED,409,ErrorCode.BUSINESS_CONFLICT,500,ErrorCode.INTERNAL_ERROR,503,ErrorCode.INTERNAL_ERROR,403,ErrorCode.VALIDATION_FAILED);
  var controller=new ApiErrorController();
  for(var e:expected.entrySet()){var request=new MockHttpServletRequest();request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE,e.getKey());var result=controller.handleError(request);assertThat(result.getStatusCode().value()).isEqualTo(e.getKey());assertThat(result.getBody().code()).isEqualTo(e.getValue().getCode());}
  for(Object value:Arrays.asList(null,"not-a-status",999)){var request=new MockHttpServletRequest();if(value!=null)request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE,value);assertThat(controller.handleError(request).getStatusCode().value()).isEqualTo(500);}
 }
}
