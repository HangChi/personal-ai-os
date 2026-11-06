package com.personalos.knowledge.rag;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.util.*;
public record RagResult(List<KnowledgeHitDto> hits,List<String> queries,List<Stage> stages,
                        List<String> warnings,String status,List<Disagreement> disagreements,String clarification,Map<String,RankingScores> rankingScores,
                        @com.fasterxml.jackson.annotation.JsonIgnore String preparedAnswer) {
    public record RankingScores(double fusion,Double rerank) { }
    public RagResult(List<KnowledgeHitDto> hits,List<String> queries,List<Stage> stages,List<String> warnings,String status,List<Disagreement> disagreements,String clarification,Map<String,RankingScores> rankingScores) {
        this(hits,queries,stages,warnings,status,disagreements,clarification,rankingScores,"");
    }
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
    public RagResult withPreparedAnswer(String answer) {
        return new RagResult(hits,queries,stages,warnings,status,disagreements,clarification,rankingScores,answer);
    }
    public String guardedAnswer() {
        if(preparedAnswer!=null && !preparedAnswer.isBlank())return preparedAnswer;
        StringBuilder answer=new StringBuilder(switch(status) {
            case "CONFLICT" -> "部分事项存在来源分歧，以下是具体对照。\n\n";
            case "CLARIFY" -> "部分事项的适用条件尚不明确。\n\n";
            default -> "本次证据核对未完成，暂不生成确定结论。\n\n";
        });
        if("CONFLICT".equals(status) && !disagreements.isEmpty())answer.append(disagreementText());
        else if(!hits.isEmpty()) {
            answer.append("可核对的来源：\n");
            for(int i=0;i<hits.size();i++)answer.append("\n[").append(i+1).append("] ").append(safeText(hits.get(i).documentTitle()));
        }
        if(!clarification.isBlank()) answer.append("\n\n").append(safeText(clarification));
        return answer.toString().stripTrailing();
    }
    /** 原文始终由服务端渲染，生成模型不能隐去冲突一方或自行选边。 */
    public String disagreementText() {
        if(disagreements.isEmpty())return "";
        var answer=new StringBuilder("**来源分歧**\n\n");
        for(int i=0;i<disagreements.size();i++) {
            var c=disagreements.get(i);
            answer.append("**分歧 ").append(i+1).append("：").append(safeText(c.reason().isBlank()?"同一事项存在不同说法":c.reason())).append("**\n");
            quote(answer,c.left(),c.leftQuote());answer.append(">\n");quote(answer,c.right(),c.rightQuote());answer.append('\n');
        }
        return answer.append(disagreements.size()==1?"该项暂不能确定唯一结论。":"以上分歧暂不能确定唯一结论。").toString();
    }
    private void quote(StringBuilder answer,KnowledgeHitDto hit,String text) {
        int index=-1;for(int i=0;i<hits.size();i++)if(sameHit(hits.get(i),hit)){index=i+1;break;}
        String line=(index<0?"":"["+index+"] ")+safeText(hit.documentTitle())+"："+safeText(text);
        for(String part:line.split("\\R",-1))answer.append("> ").append(part).append('\n');
    }
    public static boolean sameHit(KnowledgeHitDto a,KnowledgeHitDto b) {
        return Objects.equals(a.documentId(),b.documentId()) && a.chunkIndex()==b.chunkIndex();
    }
    public static boolean sameDisagreement(Disagreement a,Disagreement b) {
        return sameHit(a.left(),b.left()) && sameHit(a.right(),b.right()) && a.leftQuote().equals(b.leftQuote()) && a.rightQuote().equals(b.rightQuote())
            || sameHit(a.left(),b.right()) && sameHit(a.right(),b.left()) && a.leftQuote().equals(b.rightQuote()) && a.rightQuote().equals(b.leftQuote());
    }
    public static String safeText(String text) {
        return text.replace("\\","\\\\").replace("[","\\[").replace("]","\\]").replace("*","\\*").replace("`","\\`").replace("_","\\_").replace("<","&lt;").replace(">","&gt;");
    }
    /** 工具注册可能在网页/前次工具来源之后，不能复用局部编号。 */
    public String answerWithIndexes(List<Integer> indexes) {
        if(indexes.size()!=hits.size() || indexes.stream().anyMatch(i->i==null || i<1))return "";
        var matcher=java.util.regex.Pattern.compile("(?<!\\\\)\\[(\\d+)]").matcher(guardedAnswer());
        return matcher.replaceAll(match->{int i=Integer.parseInt(match.group(1));return i>0 && i<=indexes.size()?"["+indexes.get(i-1)+"]":match.group();});
    }
}
