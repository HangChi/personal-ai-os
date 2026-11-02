package com.personalos.knowledge.search;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.*;

/** 有界 REST 客户端；外部索引只返回候选 ID，正文必须回 PostgreSQL 校验。 */
public final class OpenSearchGateway {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI endpoint;
    private final String index;
    private final String authorization;
    private final int dimensions;
    private final ObjectMapper json;
    private volatile boolean initialized;
    public OpenSearchGateway(String endpoint,String index,String username,String password,int dimensions,ObjectMapper json) {
        this.endpoint=URI.create(endpoint.replaceAll("/$",""));
        if(!Set.of("http","https").contains(this.endpoint.getScheme()) || this.endpoint.getUserInfo()!=null
                || !index.matches("[a-z][a-z0-9_-]{0,62}")) throw new IllegalArgumentException("OpenSearch 配置非法");
        this.index=index; this.dimensions=dimensions; this.json=json;
        this.authorization=username.isBlank()?null:"Basic "+Base64.getEncoder().encodeToString((username+":"+password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private HttpResponse<String> request(String method,String path,String body) {
        try {
            var builder=HttpRequest.newBuilder(URI.create(endpoint+"/"+index+path)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
            if(authorization!=null) builder.header("Authorization",authorization);
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                byte[] bytes=stream.readNBytes(2_000_001);
                if(bytes.length>2_000_000) throw new IllegalStateException("OpenSearch 响应过大");
                return new TextResponse(response,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("OpenSearch 请求中断"); }
        catch(java.io.IOException ex) { throw new IllegalStateException("OpenSearch 暂不可用"); }
    }
    private record TextResponse(HttpResponse<?> original,String body) implements HttpResponse<String> {
        public int statusCode(){return original.statusCode();} public HttpRequest request(){return original.request();}
        public Optional<HttpResponse<String>> previousResponse(){return Optional.empty();}
        public HttpHeaders headers(){return original.headers();} public Optional<javax.net.ssl.SSLSession> sslSession(){return original.sslSession();}
        public URI uri(){return original.uri();} public HttpClient.Version version(){return original.version();}
    }
    public synchronized void initialize() {
        if(initialized) return;
        var head=request("HEAD","",null);
        if(head.statusCode()==404) {
            var mapping=Map.of("settings",Map.of("index",Map.of("knn",true,"number_of_shards",1,"number_of_replicas",0)),
                    "mappings",Map.of("properties",Map.of("content",Map.of("type","text","analyzer","cjk"),
                            "embedding",Map.of("type","knn_vector","dimension",dimensions,"method",Map.of("name","hnsw","engine","lucene","space_type","cosinesimil")))));
            var created=request("PUT","",json.writeValueAsString(mapping));
            if(created.statusCode()!=200 && created.statusCode()!=400) throw new IllegalStateException("搜索索引创建失败");
        } else if(head.statusCode()!=200) throw new IllegalStateException("搜索索引不可访问");
        // 必须检查现有维度；创建冲突不能当成映射正确。
        var mapped=request("GET","/_mapping",null);
        if(mapped.statusCode()!=200 || json.readTree(mapped.body()).path(index).path("mappings").path("properties").path("embedding").path("dimension").asInt()!=dimensions)
            throw new IllegalStateException("搜索索引向量维度不一致，请使用新索引名重建");
        initialized=true;
    }
    public void project(UUID id,long revision,String content,float[] vector) {
        initialize();
        String suffix="/_doc/"+id+"?version="+revision+"&version_type=external_gte&refresh=true";
        var response=content==null?request("DELETE",suffix,null):request("PUT",suffix,json.writeValueAsString(Map.of("content",content,"embedding",vector)));
        if(response.statusCode()!=200 && response.statusCode()!=201 && response.statusCode()!=404 && response.statusCode()!=409)
            throw new IllegalStateException("搜索投影写入失败");
    }
    public record Channels(List<UUID> lexical,List<UUID> vector) { }
    public Channels search(String query,float[] embedding,int limit) {
        initialize();
        return new Channels(ids(Map.of("match",Map.of("content",query)),limit),
                ids(Map.of("knn",Map.of("embedding",Map.of("vector",embedding,"k",limit))),limit));
    }
    private List<UUID> ids(Map<String,?> query,int limit) {
        var response=request("POST","/_search",json.writeValueAsString(Map.of("size",limit,"_source",false,"query",query)));
        if(response.statusCode()!=200) throw new IllegalStateException("搜索召回失败");
        var ids=new ArrayList<UUID>();
        for(JsonNode hit:json.readTree(response.body()).path("hits").path("hits")) ids.add(UUID.fromString(hit.path("_id").asString()));
        return ids;
    }
}
