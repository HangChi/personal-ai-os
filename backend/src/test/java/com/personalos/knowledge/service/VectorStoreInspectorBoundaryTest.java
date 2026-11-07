package com.personalos.knowledge.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.knowledge.config.KnowledgeProperties;
import java.sql.ResultSet;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
class VectorStoreInspectorBoundaryTest {
 @Test void nullableCountsEmptyQueriesAndUnsafeIdentifiers(){
  var jdbc=mock(JdbcTemplate.class);var props=mock(KnowledgeProperties.class);
  var inspector=new VectorStoreInspector(jdbc,props,"vectors","public");
  assertThat(inspector.countAll()).isZero();assertThat(inspector.countByDocumentId(UUID.randomUUID())).isZero();
  assertThat(inspector.countWithUnexpectedDimension()).isZero();
  assertThat(inspector.searchByKeywords(List.of(),10)).isEmpty();assertThat(inspector.searchByKeywords(List.of("a"),0)).isEmpty();
  assertThat(inspector.projectionChunks(List.of())).isEmpty();
  for(String id:new String[]{null,"a;drop","a b"}){
   assertThatIllegalStateException().isThrownBy(()->new VectorStoreInspector(jdbc,props,id,"public"));
   assertThatIllegalStateException().isThrownBy(()->new VectorStoreInspector(jdbc,props,"vectors",id));
  }
 }
 @Test void columnMetadataCanBeAbsentNullOrPresent()throws Exception {
  var jdbc=mock(JdbcTemplate.class);var props=mock(KnowledgeProperties.class);var rs=mock(ResultSet.class);
  when(jdbc.query(anyString(),any(ResultSetExtractor.class),any(Object[].class))).thenAnswer(i->((ResultSetExtractor<?>)i.getArgument(1)).extractData(rs));
  var inspector=new VectorStoreInspector(jdbc,props,"vectors","public");
  when(rs.next()).thenReturn(false);assertThat(inspector.embeddingColumnType()).isEmpty();
  when(rs.next()).thenReturn(true);assertThat(inspector.embeddingColumnType()).isEmpty();
  when(rs.getString(1)).thenReturn("vector(2)");assertThat(inspector.embeddingColumnType()).contains("vector(2)");
 }
}
