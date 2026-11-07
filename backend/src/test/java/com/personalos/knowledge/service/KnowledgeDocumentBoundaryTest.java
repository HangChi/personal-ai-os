package com.personalos.knowledge.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.entity.KnowledgeDocumentEntity;
import com.personalos.knowledge.reader.DocumentReaderFactory;
import com.personalos.knowledge.repository.KnowledgeDocumentRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class KnowledgeDocumentBoundaryTest {
 static class Fixture {
  final UUID id=UUID.randomUUID();
  final KnowledgeDocumentRepository repo=mock(KnowledgeDocumentRepository.class);
  final VectorStore vectors=mock(VectorStore.class);
  final VectorStoreInspector inspector=mock(VectorStoreInspector.class);
  final DocumentWriteLock locks=mock(DocumentWriteLock.class);
  final DocumentReaderFactory readers=mock(DocumentReaderFactory.class);
  final EmbeddingModel model=mock(EmbeddingModel.class);
  final KnowledgeDocumentService service;
  Fixture(){
   service=new KnowledgeDocumentService(repo,vectors,new KnowledgeProperties(2,4,.3,64,List.of("md"),3,20,0,true,60,1),inspector,readers,locks,mock(PlatformTransactionManager.class));
   service.configureEmbedding(model,new JsonCodec(new ObjectMapper()));
   when(repo.findById(id)).thenReturn(Optional.of(mock(KnowledgeDocumentEntity.class)));
   DocumentReader reader=()->List.of(new Document("有效知识正文"));
   when(readers.create(anyString(),anyString(),any(),anyMap())).thenReturn(reader);
  }
 }
 @Test void uploadValidatesBeforeParsingAndNormalizesTitles(){
  var f=new Fixture();
  for(String name:List.of("no-extension","x.exe"))assertThatThrownBy(()->f.service.prepareUpload(name,null,new byte[]{1})).isInstanceOf(BusinessException.class);
  for(byte[] data:new byte[][]{null,new byte[0],new byte[65]})assertThatThrownBy(()->f.service.prepareUpload("x.md",null,data)).isInstanceOf(BusinessException.class);
  verifyNoInteractions(f.readers,f.vectors);
  for(String title:Arrays.asList(null,"  "," 指定标题 ")){
   var result=f.service.prepareUpload("文件.md",title,new byte[]{1});
   assertThat(result.title()).isEqualTo(title==null||title.isBlank()?"文件":"指定标题");
   assertThat(result.charCount()).isEqualTo(6);
  }
 }
 @Test void replacementRejectsInvalidInputAndAlwaysReleasesLease(){
  for(String name:List.of("x","x.exe","x.md"))for(byte[] bytes:new byte[][]{null,new byte[0],new byte[65]}){
   var f=new Fixture();
   assertThatThrownBy(()->f.service.update(f.id,name,bytes)).isInstanceOf(BusinessException.class);
   verify(f.locks).release(f.id);verifyNoInteractions(f.inspector,f.vectors,f.model,f.readers);
  }
 }
 @Test void parserAndEmbeddingFailuresNeverDeleteExistingChunks(){
  for(String kind:List.of("short","blank","count","dimension","dims","connect","refused","timeout","unknownhost","other","null-message","self-cause")){
   var f=new Fixture();
   if(kind.equals("short")||kind.equals("blank"))when(f.readers.create(anyString(),anyString(),any(),anyMap())).thenReturn(()->List.of(new Document(kind.equals("short")?"短":"     ")));
   else if(!kind.equals("count")){
    RuntimeException failure=kind.equals("null-message")?new IllegalStateException((String)null):new IllegalStateException(kind);
    if(kind.equals("self-cause"))failure=new IllegalStateException("loop"){@Override public synchronized Throwable getCause(){return this;}};
    when(f.model.embed(anyList(),any(),any())).thenThrow(failure);
   }
   var thrown=catchThrowable(()->f.service.update(f.id,"new.md",new byte[]{1}));
   assertThat(thrown).as(kind).isInstanceOf(RuntimeException.class);
   if(List.of("dimension","dims").contains(kind))assertThat(((BusinessException)thrown).getErrorCode()).isEqualTo(ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
   if(List.of("connect","refused","timeout","unknownhost").contains(kind))assertThat(((BusinessException)thrown).getErrorCode()).isEqualTo(ErrorCode.AI_UPSTREAM_ERROR);
   if(kind.equals("count"))assertThat(thrown).hasMessageContaining("向量数量");
   verify(f.locks).release(f.id);verifyNoInteractions(f.inspector,f.vectors);verify(f.repo,never()).save(any());
  }
 }
 @Test void deletionDetectsRemainingVectorsAndAlwaysReleasesLock(){
  var f=new Fixture();
  when(f.inspector.countByDocumentId(f.id)).thenReturn(4,1);
  assertThatThrownBy(()->f.service.delete(f.id)).isInstanceOf(IllegalStateException.class).hasMessageContaining("仍残留 1 条");
  verify(f.locks).release(f.id);
 }
 @Test @SuppressWarnings({"unchecked", "rawtypes"}) void listQueryBuildsBlankAndEscapedKeywordPredicates(){
  var f=new Fixture();
  var root=mock(jakarta.persistence.criteria.Root.class);
  var builder=mock(jakarta.persistence.criteria.CriteriaBuilder.class);
  var predicate=mock(jakarta.persistence.criteria.Predicate.class);
  var path=mock(jakarta.persistence.criteria.Path.class);
  var expression=mock(jakarta.persistence.criteria.Expression.class);
  when(root.get(anyString())).thenReturn(path);
  when(builder.lower(any(jakarta.persistence.criteria.Expression.class))).thenReturn(expression);
  when(builder.conjunction()).thenReturn(predicate);
  when(builder.or(any(jakarta.persistence.criteria.Predicate.class),any(jakarta.persistence.criteria.Predicate.class))).thenReturn(predicate);
  when(builder.like(any(jakarta.persistence.criteria.Expression.class),anyString(),anyChar())).thenReturn(predicate);
  when(f.repo.findAll(any(org.springframework.data.jpa.domain.Specification.class),any(org.springframework.data.domain.Pageable.class))).thenAnswer(invocation->{
   org.springframework.data.jpa.domain.Specification<KnowledgeDocumentEntity> spec=invocation.getArgument(0);
   assertThat(spec.toPredicate(root,null,builder)).isSameAs(predicate);
   return org.springframework.data.domain.Page.empty();
  });
  for(String keyword:Arrays.asList(null," ","100%_"))f.service.search(new com.personalos.knowledge.dto.KnowledgeDocumentQuery(keyword,0,20));
  verify(builder,times(2)).conjunction();
  verify(builder).or(predicate,predicate);
 }
}
