package com.personalos.knowledge.rag;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.service.KnowledgeRetrievalService;
import com.personalos.knowledge.spi.KnowledgeSearchPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service @Primary
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.knowledge.rag.enabled",havingValue="true",matchIfMissing=true)
public class RagRetrievalPipeline implements KnowledgeSearchPort {
    private final KnowledgeRetrievalService recall;
    private final RagModelGateway model;
    private final EvidenceReview review;
    private final KnowledgeProperties properties;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final long budget;
    public RagRetrievalPipeline(KnowledgeRetrievalService recall,RagModelGateway model,EvidenceReview review,KnowledgeProperties properties,
            ObjectMapper json,MeterRegistry meters,@Value("${app.knowledge.rag.enabled:true}") boolean enabled,
            @Value("${app.knowledge.rag.budget-ms:20000}") long budget) {
        this.recall=recall;this.model=model;this.review=review;this.properties=properties;this.json=json;this.meters=meters;
        this.enabled=enabled;this.budget=Math.max(1000,Math.min(60000,budget));
    }
    private com.personalos.knowledge.governance.KnowledgeGovernance governance;
    @org.springframework.beans.factory.annotation.Autowired
    void configureGovernance(com.personalos.knowledge.governance.KnowledgeGovernance governance){this.governance=governance;}
    private List<KnowledgeHitDto> enrich(List<KnowledgeHitDto> hits){return governance==null?hits:governance.enrich(hits);}
    @Override public List<KnowledgeHitDto> search(String query,Integer topK){return retrieve(query,topK).hits();}
    @Override public RagResult retrieve(String query,Integer topK) {
        if(query==null || query.isBlank() || query.length()>2000) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"查询不能为空且不能超过 2000 字符");
        if(!enabled) return RagResult.legacy(recall.search(query,topK),query);
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budget);
        var stages=new ArrayList<RagResult.Stage>(); var warnings=new ArrayList<String>();
        var queries=new LinkedHashSet<String>(); queries.add(query.strip());
        // Empty libraries avoid auxiliary LLM calls too.
        long start=System.nanoTime(); var initial=enrich(recall.candidates(query,40));
        stage(stages,"recall",start,1,initial.size(),"OK");
        if(initial.isEmpty() && !recall.hasContent()) return new RagResult(List.of(),List.copyOf(queries),stages,warnings,"INSUFFICIENT",List.of(),"");
        start=System.nanoTime();
        try {
            var rewritten=model.call("保持原问题的对象、时间和限制，只补充检索用同义词和拆分子问题，不生成答案，不虚构实体。返回 {\"queries\":[\"改写1\",\"改写2\"]}，最多两条，每条不超过500字符。",json.writeValueAsString(Map.of("query",query)),remaining(deadline));
            if(!rewritten.path("queries").isArray() || rewritten.path("queries").size()>2) throw new IllegalArgumentException("Invalid rewrites");
            for(var q:rewritten.path("queries")) {String text=q.asString("").strip();if(text.isEmpty()||text.length()>500) throw new IllegalArgumentException("Invalid query");queries.add(text);}
            stage(stages,"rewrite",start,1,queries.size(),"OK");
        } catch(RuntimeException ex) {warnings.add("查询改写不可用，保留原问题召回");stage(stages,"rewrite",start,1,1,"FALLBACK");}
        Map<String,KnowledgeHitDto> candidates=new LinkedHashMap<>(); Map<String,Double> rrf=new HashMap<>();
        add(initial,candidates,rrf);
        for(String rewritten:queries) if(!rewritten.equals(query.strip()) && System.nanoTime()<deadline) {
            try {add(enrich(recall.candidates(rewritten,40)),candidates,rrf);} catch(RuntimeException ex){warnings.add("部分改写召回失败");}
        }
        var ranked=new ArrayList<>(candidates.values().stream().sorted(Comparator.comparingDouble((KnowledgeHitDto h)->rrf.get(key(h))).reversed()).limit(40).toList());
        if(ranked.isEmpty())return new RagResult(List.of(),List.copyOf(queries),stages,warnings,"INSUFFICIENT",List.of(),"");
        start=System.nanoTime();
        try {
            var order=model.call("对候选按能否直接回答问题重排，不回答问题。返回 {\"order\":[1,2,...]}，必须包含所有资料编号一次，不增删编号。相似度不是事实真实性。",EvidenceReview.payload(query,ranked,json),remaining(deadline));
            var reordered=new ArrayList<KnowledgeHitDto>(); var seen=new HashSet<Integer>();
            for(var node:order.path("order")) {int i=node.asInt(0)-1;if(i<0||i>=ranked.size()||!seen.add(i))throw new IllegalArgumentException("Invalid rank");reordered.add(ranked.get(i));}
            if(reordered.size()!=ranked.size()) throw new IllegalArgumentException("Incomplete rank");
            ranked=reordered;stage(stages,"rerank",start,candidates.size(),ranked.size(),"OK");
        } catch(RuntimeException ex){warnings.add("重排不可用，采用 RRF 排名");stage(stages,"rerank",start,candidates.size(),ranked.size(),"FALLBACK");}
        // Review diverse sources before topK, so topK=1 cannot silently discard an opposing source.
        var pool=diverse(ranked,16,2);
        if(governance!=null)pool=governance.expandConflictingSources(pool);
        start=System.nanoTime();
        String status="UNVERIFIED",clarification=""; List<RagResult.Disagreement> conflicts=List.of(); List<KnowledgeHitDto> selected=pool;
        try {
            var assessed=review.review(query,pool,remaining(deadline));conflicts=assessed.conflicts();clarification=assessed.clarification();
            if(governance!=null) {assessed=governance.applyDecisions(assessed,pool,warnings);governance.record(assessed.conflicts());}
            conflicts=assessed.conflicts();
            selected=assessed.relevant();status=!conflicts.isEmpty()?"CONFLICT":!clarification.isBlank()?"CLARIFY":selected.isEmpty()?"INSUFFICIENT":"READY";
            stage(stages,"evidence",start,pool.size(),selected.size(),"OK");
        } catch(RuntimeException ex){warnings.add("证据精审未完成，展示来源并暂停确定作答");stage(stages,"evidence",start,pool.size(),pool.size(),"FALLBACK");}
        if(status.equals("READY")) selected=diverse(selected,properties.normalizeTopK(topK),2);
        else if(status.equals("CONFLICT")) {
            var keep=new LinkedHashSet<KnowledgeHitDto>();for(var c:conflicts){keep.add(c.left());keep.add(c.right());} selected=List.copyOf(keep);
        }
        meters.counter("personalos.knowledge.rag.results","status",status).increment();
        return new RagResult(selected,List.copyOf(queries),List.copyOf(stages),List.copyOf(warnings),status,conflicts,clarification);
    }
    private static long remaining(long deadline){return Math.min(8000,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));}
    private void stage(List<RagResult.Stage> stages,String name,long start,int in,int out,String result) {
        long nanos=System.nanoTime()-start;stages.add(new RagResult.Stage(name,result,TimeUnit.NANOSECONDS.toMillis(nanos),in,out));
        meters.timer("personalos.knowledge.rag.stage","stage",name,"result",result).record(nanos,TimeUnit.NANOSECONDS);
    }
    private static String key(KnowledgeHitDto h){return h.documentId()+":"+h.chunkIndex();}
    private void add(List<KnowledgeHitDto> hits,Map<String,KnowledgeHitDto> candidates,Map<String,Double> ranks) {
        for(int i=0;i<hits.size();i++){var h=hits.get(i);if(h.documentId()==null)continue;candidates.putIfAbsent(key(h),h);ranks.merge(key(h),1.0/(60+i+1),Double::sum);}
    }
    private static List<KnowledgeHitDto> diverse(List<KnowledgeHitDto> hits,int limit,int perDocument) {
        Map<UUID,Integer> count=new HashMap<>();var selected=new ArrayList<KnowledgeHitDto>();
        // Give each document one position first, then a second evidence chunk.
        for(int pass=1;pass<=perDocument;pass++) for(var h:hits) {
            if(selected.size()>=limit)return selected;
            if(!selected.contains(h)&&count.getOrDefault(h.documentId(),0)<pass){selected.add(h);count.merge(h.documentId(),1,Integer::sum);}
        }
        return selected;
    }
}
