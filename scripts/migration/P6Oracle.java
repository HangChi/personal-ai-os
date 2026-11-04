package com.personalos.migration;

import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.service.*;
import com.personalos.knowledge.splitter.MarkdownStructureTextSplitter;
import com.personalos.knowledge.tool.KnowledgeTools;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.vectorstore.*;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public final class P6Oracle {
 public static void main(String[] args)throws Exception{
  var json=JsonMapper.builder().build();var input=json.readTree(System.in);var output=new LinkedHashMap<String,Object>();
  var names=new ArrayList<Object>();var filename=Class.forName("com.personalos.knowledge.service.DocumentFilenameSupport").getDeclaredMethod("sanitizeFilename",String.class);filename.setAccessible(true);
  for(var row:input.get("filenames"))names.add(filename.invoke(null,row.isNull()?null:row.asText()));output.put("filenames",names);
  var keywords=new ArrayList<Object>();for(var row:input.get("queries"))keywords.add(KeywordExtractor.extract(row.asText()));output.put("keywords",keywords);
  var split=MarkdownStructureTextSplitter.class.getDeclaredMethod("splitText",String.class);split.setAccessible(true);
  var splits=new ArrayList<Object>();for(var row:input.get("splits"))splits.add(split.invoke(new MarkdownStructureTextSplitter(row.get("target").asInt(),row.get("minimum").asInt(),row.get("overlap").asInt()),row.get("text").asText()));output.put("splits",splits);
  var definitions=new ArrayList<Object>();for(var callback:ToolCallbacks.from(new KnowledgeTools(null,null))){var d=callback.getToolDefinition();definitions.add(Map.of("name",d.name(),"description",d.description(),"parameters",json.readTree(d.inputSchema())));}output.put("tools",definitions);
  var citations=new ArrayList<Object>();var qa=new KnowledgeQaService(null,null);var build=KnowledgeQaService.class.getDeclaredMethod("buildCitations",String.class,List.class);build.setAccessible(true);
  var hits=List.of(new KnowledgeHitDto(UUID.fromString("11111111-1111-4111-8111-111111111111"),"A","a.md",0,"原文证据一",0.9),new KnowledgeHitDto(UUID.fromString("22222222-2222-4222-8222-222222222222"),"B","b.md",1,"原文证据二",0.8));
  for(var row:input.get("answers"))citations.add(build.invoke(qa,row.asText(),hits));output.put("citations",citations);
  // Controlled vectors isolate retrieval/citation parity from external model quality.
  var recalls=new ArrayList<Object>();
  for(var row:input.get("recalls")){
   var vector=mock(VectorStore.class);var inspector=mock(VectorStoreInspector.class);
   var docs=new ArrayList<Document>();for(var d:row.get("documents")){var meta=Map.<String,Object>of("document_id",d.get("id").asText(),"document_title",d.get("title").asText(),"filename",d.get("filename").asText(),"chunk_index",0);docs.add(Document.builder().id(d.get("id").asText()).text(d.get("content").asText()).metadata(meta).score(d.get("score").asDouble()).build());}
   when(vector.similaritySearch(any(SearchRequest.class))).thenAnswer(inv->{SearchRequest req=inv.getArgument(0);return docs.stream().filter(d->d.getScore()>=req.getSimilarityThreshold()).sorted(Comparator.comparingDouble((Document d)->d.getScore()).reversed()).limit(req.getTopK()).toList();});
   when(inspector.searchByKeywords(anyList(),anyInt())).thenAnswer(inv->{List<String> tokens=inv.getArgument(0);int limit=inv.getArgument(1);var matches=new ArrayList<VectorStoreInspector.KeywordMatch>();for(String token:tokens){var found=docs.stream().filter(d->d.getText().toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT))).sorted(Comparator.comparingInt((Document d)->d.getText().length()).thenComparing(Document::getId)).toList();for(var d:found.stream().limit(limit).toList())matches.add(new VectorStoreInspector.KeywordMatch(token,new VectorStoreInspector.KeywordChunk(d.getId(),d.getText(),json.writeValueAsString(d.getMetadata())),found.size(),docs.size()));}return matches;});
   var props=new KnowledgeProperties(1024,4,0.3,5242880,List.of("md"),10,1200,0,true,60,1.0);
   var service=new KnowledgeRetrievalService(vector,inspector,props,json);long start=System.nanoTime();var found=service.search(row.get("query").asText(),4);long micros=(System.nanoTime()-start)/1000;
   recalls.add(Map.of("ids",found.stream().map(h->h.documentId().toString()).toList(),"citations",build.invoke(qa,"[1]",found),"latencyMicros",micros));
  }output.put("recalls",recalls);
  System.out.println("ORACLE "+json.writeValueAsString(output));
 }
}
