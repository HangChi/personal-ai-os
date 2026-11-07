package com.personalos.common.exception;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.validation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
class GlobalExceptionBoundaryTest {
 @Test void emptyValidationDetailsStillReturnJsonAndPartNameIsIdentified(){
  var handler=new GlobalExceptionHandler();var ex=mock(MethodArgumentNotValidException.class);
  var result=new BeanPropertyBindingResult(new Object(),"body");when(ex.getBindingResult()).thenReturn(result);
  assertThat(handler.handleMethodArgumentNotValid(ex).getStatusCode().value()).isEqualTo(400);
  result.addError(new FieldError("body","title","required"));
  assertThat(handler.handleMethodArgumentNotValid(ex).getBody().message()).contains("title");
  assertThat(handler.handleConstraintViolation(new ConstraintViolationException(Set.of())).getStatusCode().value()).isEqualTo(400);
  assertThat(handler.handleMissingParameter(new MissingServletRequestPartException("upload")).getBody().message()).contains("upload");
 }
 @Test void workspaceConstraintExposesOnlyTheIntendedFirstLine(){
  var handler=new GlobalExceptionHandler();
  for(SQLException sql:new SQLException[]{new SQLException("ERROR: workspace:invalid workspace"+System.lineSeparator()+"sensitive internal SQL","23514"),new SQLException("internal","23505"),new SQLException((String)null,"23514"),new SQLException("other constraint","23514")}){
   var response=handler.handleDataIntegrityViolation(new DataIntegrityViolationException("database",sql));
   assertThat(response.getBody().message()).doesNotContain("sensitive","internal","SQL");
   if(sql.getMessage()!=null&&sql.getMessage().startsWith("ERROR: workspace:"))assertThat(response.getStatusCode().value()).isEqualTo(400);
  }
  assertThat(handler.handleDataIntegrityViolation(new DataIntegrityViolationException("opaque")).getBody().message()).doesNotContain("opaque");
 }
}
