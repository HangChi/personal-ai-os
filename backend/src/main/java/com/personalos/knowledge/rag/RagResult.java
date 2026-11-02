package com.personalos.knowledge.rag;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.util.*;
public record RagResult(List<KnowledgeHitDto> hits,List<String> queries,List<Stage> stages,
                        List<String> warnings,String status,List<Disagreement> disagreements,String clarification,Map<String,RankingScores> rankingScores) {
    public record RankingScores(double fusion,Double rerank) { }
    public RagResult(List<KnowledgeHitDto> hits,List<String> queries,List<Stage> stages,List<String> warnings,String status,List<Disagreement> disagreements,String clarification) {
        this(hits,queries,stages,warnings,status,disagreements,clarification,Map.of());
    }
    public record Stage(String name,String status,long durationMs,int inputCount,int outputCount) { }
    /** Evidence text must be exact substrings of the corresponding candidate. */
    public record Disagreement(KnowledgeHitDto left,KnowledgeHitDto right,String leftQuote,String rightQuote,String reason) { }
    public static RagResult legacy(List<KnowledgeHitDto> hits,String query) {
        return new RagResult(hits,List.of(query),List.of(),List.of(),hits.isEmpty()?"INSUFFICIENT":"READY",List.of(),"");
    }
    public boolean guarded(){return !Set.of("READY","INSUFFICIENT").contains(status);}
    public String guardedAnswer() {
        StringBuilder answer=new StringBuilder(switch(status) {
            case "CONFLICT" -> "资料之间存在待核实的不同说法，不能据此选定唯一答案。\n";
            case "CLARIFY" -> "现有资料的适用条件不同，需要先澄清问题。\n";
            default -> "本次证据核对未完成，暂不生成确定结论。\n";
        });
        for(int i=0;i<hits.size();i++) {
            var hit=hits.get(i); answer.append("\n[").append(i+1).append("] ").append(hit.documentTitle()).append("：\n")
                .append(hit.content(),0,Math.min(hit.content().length(),700)).append('\n');
        }
        if(!clarification.isBlank()) answer.append('\n').append(clarification);
        else answer.append("\n请补充适用对象、时间或制度版本，或到资料治理中核对来源。");
        return answer.toString();
    }
}
