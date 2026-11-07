package com.personalos.knowledge.rag;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
class EvidenceAnswerBoundaryTest {
 final ObjectMapper json=new ObjectMapper();
 Map<String,Object> ref(Object index,String quote){return Map.of("index",index,"quote",quote);}
 Map<String,Object> part(){return new LinkedHashMap<>(Map.of("topic","规则","status","SUPPORTED","text","每天工作八小时","evidence",List.of(ref(1,"每天工作八小时"))));}
 RagResult retrieval(List<KnowledgeHitDto> hits,String status){return new RagResult(hits,List.of("工时"),List.of(),List.of(),status,List.of(),"");}
 List<KnowledgeHitDto> hits(){return List.of(new KnowledgeHitDto(UUID.randomUUID(),"制度","a.md",0,"每天工作八小时"+"原文".repeat(500),.9));}
 String run(List<?> parts,Object check){var model=mock(RagModelGateway.class);when(model.call(anyString(),anyString(),anyLong())).thenReturn(json.valueToTree(Map.of("parts",parts)),json.valueToTree(check));return new EvidenceAnswer(model,json).prepare("工时",retrieval(hits(),"CLARIFY"),8000);}
 @Test void invalidGeneratedPartsFailClosed(){
  var variants=new ArrayList<Map<String,Object>>();
  for(var change:List.of(Map.of("topic"," "),Map.of("topic","a".repeat(81)),Map.of("text","a".repeat(1201)),Map.of("topic","标题[1]"),Map.of("status","UNKNOWN"),Map.of("evidence","bad"),Map.of("evidence",Collections.nCopies(5,ref(1,"每天工作八小时"))),Map.of("evidence",List.of(ref(0,"每天工作八小时"))),Map.of("evidence",List.of(ref(2,"每天工作八小时"))),Map.of("evidence",List.of(ref(1,"每天工作八小时"),ref(1,"每天工作八小时"))),Map.of("evidence",List.of(ref(1,"abc"))),Map.of("evidence",List.of(ref(1,"a".repeat(801)))),Map.of("evidence",List.of(ref(1,"没有这段原文"))),Map.of("status","UNRESOLVED"),Map.of("text"," "),Map.of("evidence",List.of()),Map.of("evidence",List.of(ref(1.5,"每天工作八小时"))))){var p=part();p.putAll(change);variants.add(p);}
  var unresolved=part();unresolved.put("status","UNRESOLVED");unresolved.put("text","");variants.add(unresolved);
  var check=Map.of("complete",true,"conflictsRespected",true,"parts",List.of(Map.of("index",1,"supported",true)));
  for(var p:variants)assertThat(run(List.of(p),check)).as(p.toString()).isEmpty();
  assertThat(run(List.of(),check)).isEmpty();assertThat(run(Collections.nCopies(9,part()),check)).isEmpty();
  var longPart=part();longPart.put("text","x".repeat(1200));assertThat(run(Collections.nCopies(6,longPart),check)).isEmpty();
 }
 @Test void independentVerificationMustCoverEveryPartExactlyOnce(){
  var parts=List.of(part(),part());
  for(Object check:List.of(Map.of(),Map.of("complete","true"),Map.of("complete",false),Map.of("complete",true,"conflictsRespected","true"),Map.of("complete",true,"conflictsRespected",false)))assertThat(run(parts,check)).isEmpty();
  for(Object rows:List.of("bad",List.of(),List.of(Map.of("index",1,"supported",true)),List.of(Map.of("index",0,"supported",true),Map.of("index",2,"supported",true)),List.of(Map.of("index",3,"supported",true),Map.of("index",2,"supported",true)),List.of(Map.of("index",1,"supported",true),Map.of("index",1,"supported",true)),List.of(Map.of("index",1,"supported","true"),Map.of("index",2,"supported",true)),List.of(Map.of("index",1,"supported",false),Map.of("index",2,"supported",true))))assertThat(run(parts,Map.of("complete",true,"conflictsRespected",true,"parts",rows))).isEmpty();
 }
 @Test void unresolvedAndSuggestionRenderingAndPreflightLimits(){
  var model=mock(RagModelGateway.class);var answer=new EvidenceAnswer(model,json);
  for(var r:List.of(retrieval(hits(),"READY"),retrieval(List.of(),"CLARIFY"),retrieval(Collections.nCopies(41,hits().getFirst()),"CONFLICT")))assertThat(answer.prepare("问",r,8000)).isEmpty();
  assertThat(answer.prepare("问",retrieval(hits(),"CLARIFY"),99)).isEmpty();verifyNoInteractions(model);
  var u=part();u.put("status","UNRESOLVED");u.put("text","");u.put("evidence",List.of());assertThat(run(List.of(u),Map.of())).isEmpty();
  var s=part();s.put("status","SUGGESTION");
  var check=Map.of("complete",true,"conflictsRespected",true,"parts",List.of(Map.of("index",1,"supported",true),Map.of("index",2,"supported",true)));
  assertThat(run(List.of(s,u),check)).contains("建议的人工验证","尚不能确定");
  when(model.call(anyString(),anyString(),anyLong())).thenReturn(json.valueToTree(Map.of("parts",List.of(part()))),json.valueToTree(Map.of("complete",true,"conflictsRespected",true,"parts",List.of(Map.of("index",1,"supported",true)))));
  Thread.currentThread().interrupt();try{assertThat(answer.prepare("问",retrieval(hits(),"CLARIFY"),8000)).isEmpty();}finally{Thread.interrupted();}
  assertThatThrownBy(()->EvidenceAnswer.integer(json.valueToTree(Long.MAX_VALUE))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->EvidenceAnswer.string(json.valueToTree(1))).isInstanceOf(IllegalArgumentException.class);
 }
}
