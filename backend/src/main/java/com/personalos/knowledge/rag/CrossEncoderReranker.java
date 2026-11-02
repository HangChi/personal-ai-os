package com.personalos.knowledge.rag;

import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Optional Cohere-compatible cross-encoder. Disabled unless explicitly configured. */
@Component
@ConditionalOnProperty(name="app.knowledge.rag.reranker.enabled",havingValue="true")
public class CrossEncoderReranker {
    private final URI endpoint;private final String key,model,protocol;private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public CrossEncoderReranker(String endpoint,String key,String model,ObjectMapper json) {
        this(endpoint,key,model,"cohere",json);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public CrossEncoderReranker(@Value("${app.knowledge.rag.reranker.endpoint:}") String endpoint,
            @Value("${app.knowledge.rag.reranker.api-key:}") String key,@Value("${app.knowledge.rag.reranker.model:}") String model,@Value("${app.knowledge.rag.reranker.protocol:cohere}") String protocol,ObjectMapper json) {
        this.endpoint=URI.create(endpoint);this.key=key;this.model=model;this.json=json;this.protocol=protocol.toLowerCase(Locale.ROOT);
        if(!Set.of("cohere","dashscope").contains(this.protocol))throw new IllegalArgumentException("Unsupported rerank protocol");
        if(!Set.of("http","https").contains(this.endpoint.getScheme())||this.endpoint.getUserInfo()!=null||model.isBlank())throw new IllegalArgumentException("Reranker endpoint/model required");
    }
    private io.micrometer.core.instrument.MeterRegistry meters;
    @org.springframework.beans.factory.annotation.Autowired
    void metrics(io.micrometer.core.instrument.MeterRegistry meters){this.meters=meters;}
    public record Ranking(List<KnowledgeHitDto> hits,Map<String,Double> scores) { }
    public Ranking rank(String query,List<KnowledgeHitDto> candidates,long timeoutMs) {
        if(timeoutMs<100)throw new IllegalStateException("Rerank budget exhausted");
        var documents=candidates.stream().map(h->h.content().substring(0,Math.min(2000,h.content().length()))).toList();
        Map<String,Object> payload=protocol.equals("dashscope")
            ?Map.of("model",model,"input",Map.of("query",query,"documents",documents),"parameters",Map.of("top_n",candidates.size(),"return_documents",false))
            :Map.of("model",model,"query",query,"documents",documents,"top_n",candidates.size());
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(timeoutMs)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        if(!key.isBlank())request.header("Authorization","Bearer "+key);
        try {
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;try(var body=response.body()){bytes=body.readNBytes(1_000_001);}
            if(response.statusCode()!=200||bytes.length>1_000_000)throw new IllegalStateException("Reranker unavailable");
            var root=json.readTree(bytes);
            if(meters!=null && root.path("usage").path("total_tokens").isNumber())
                meters.counter("personalos.knowledge.rag.rerank.tokens").increment(Math.max(0,root.path("usage").path("total_tokens").asLong()));
            var result=(protocol.equals("dashscope")?root.path("output"):root).path("results");var seen=new HashSet<Integer>();var ordered=new ArrayList<KnowledgeHitDto>();var scores=new LinkedHashMap<String,Double>();
            if(!result.isArray())throw new IllegalArgumentException("Missing rerank results");
            for(var row:result){int index=row.path("index").asInt(-1);double score=row.path("relevance_score").asDouble(Double.NaN);
                if(index<0||index>=candidates.size()||!seen.add(index)||!Double.isFinite(score)||score<0||score>1)throw new IllegalArgumentException("Invalid rerank result");
                var hit=candidates.get(index);ordered.add(hit);scores.put(hit.documentId()+":"+hit.chunkIndex(),score);}
            if(ordered.size()!=candidates.size())throw new IllegalArgumentException("Incomplete rerank results");
            ordered.sort(Comparator.comparingDouble((KnowledgeHitDto h)->scores.get(h.documentId()+":"+h.chunkIndex())).reversed());
            return new Ranking(List.copyOf(ordered),Map.copyOf(scores));
        }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("Rerank interrupted");}
        catch(java.io.IOException ex){throw new IllegalStateException("Reranker unavailable");}
    }
}
