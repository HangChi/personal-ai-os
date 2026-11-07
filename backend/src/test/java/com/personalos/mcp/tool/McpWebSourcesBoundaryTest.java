package com.personalos.mcp.tool;

import static org.assertj.core.api.Assertions.*;
import com.personalos.common.json.JsonCodec;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class McpWebSourcesBoundaryTest {
 final JsonCodec json=new JsonCodec(new ObjectMapper());
 String result(Object... items){return json.toJson(Map.of("content",List.of(items)));}
 Map<String,String> text(String body){return Map.of("type","text","text",body);}
 @Test void fetchedSourcesMustMatchRequestedUrlAndContainUsableBody(){
  var args=Map.<String,Object>of("url","https://example.test/page/");
  for(String raw:List.of(result(Map.of("type","image")),result(text("plain")),result(text("Contents of https://other.test/:\nbody")),result(text("Contents of https://example.test/page:\n<error>Denied"))))assertThat(McpWebSources.extract("fetch",args,raw,json)).isEmpty();
  assertThat(McpWebSources.extract("fetch",Map.of(),result(text("body")),json)).isEmpty();
  var extracted=McpWebSources.extract("fetch",args,result(text("Contents of https://example.test/page:\r\nEvidence text\n\n<error>Content truncated. ignored")),json);
  assertThat(extracted).hasSize(1);assertThat(extracted.getFirst().content()).isEqualTo("Evidence text");
  assertThat(McpWebSources.extract("fetch",args,json.toJson(Map.of("isError",true)),json)).isEmpty();
  assertThat(McpWebSources.extract("unknown",args,"{}",json)).isEmpty();
  assertThat(McpWebSources.extract("fetch",args,"x".repeat((1<<20)+1),json)).isEmpty();
 }
 @Test void searchAcceptsStructuredAndLegacyJsonButCapsAndFiltersEvidence(){
  var rows=new ArrayList<Map<String,String>>();
  rows.add(Map.of("url","javascript:evil","content","invalid"));
  rows.add(Map.of("url","https://example.test/1","snippet","snippet evidence"));
  rows.add(Map.of("url","https://example.test/2","description","description evidence"));
  for(int i=3;i<9;i++)rows.add(Map.of("url","https://example.test/"+i,"content","content evidence"));
  String payload=json.toJson(Map.of("results",rows));
  String structured=json.toJson(Map.of("structuredContent",Map.of("results",rows)));
  assertThat(McpWebSources.extract("web_search",Map.of(),structured,json)).hasSize(5);
  var legacy=McpWebSources.extract("search_web",Map.of(),result(text("plain"),text("{broken"),text("{}"),text(payload)),json);
  assertThat(legacy).hasSize(5);assertThat(legacy.get(0).content()).isEqualTo("snippet evidence");assertThat(legacy.get(1).content()).isEqualTo("description evidence");
  assertThat(McpWebSources.extract("web_search",Map.of(),result(text("plain")),json)).isEmpty();
 }
}
