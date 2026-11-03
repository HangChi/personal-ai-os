package com.personalos.chat;

import static org.mockito.Mockito.*;
import com.personalos.chat.controller.ChatSessionController;
import com.personalos.chat.dto.*;
import com.personalos.chat.entity.*;
import com.personalos.chat.repository.*;
import com.personalos.chat.service.*;
import com.personalos.common.ai.*;
import com.personalos.common.exception.GlobalExceptionHandler;
import com.personalos.common.json.JsonCodec;
import com.personalos.common.search.SearchPatterns;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 原服务 + 原 MVC 的离线合成夹具；不装配 Spring 应用或读写任何文件附件。 */
public class ChatStorageOracle {
 static final Instant WHEN=Instant.parse("2026-10-27T02:00:00Z");
 static ChatMessageEntity message(int i) {var message=new ChatMessageEntity("c-existing",i%2==1?MessageRole.USER:MessageRole.ASSISTANT,"m"+i,i,WHEN.minusSeconds(i));org.springframework.test.util.ReflectionTestUtils.setField(message,"id",(long)i);return message;}
 public static void main(String[] args)throws Exception {
  var mapper=JsonMapper.builder().build();var input=mapper.readTree(System.in);var output=new LinkedHashMap<String,Object>();
  var attachments=new ChatAttachmentService(new JsonCodec(mapper),"/synthetic-unused-chat-oracle",6291456);
  var binds=new ArrayList<Object>();
  for(var c:input.get("bindings")){
   var sessions=mock(ChatSessionRepository.class);var messages=mock(ChatMessageRepository.class);var citations=mock(ChatCitationService.class);
   var entity=new ChatSessionEntity("c-existing","旧标题",WHEN,WHEN.plusSeconds(60));
   when(sessions.findById(anyString())).thenAnswer(inv->inv.getArgument(0).equals("c-existing")?Optional.of(entity):Optional.empty());
   when(sessions.save(any(ChatSessionEntity.class))).thenAnswer(inv->inv.getArgument(0));
   when(messages.countByConversationId(anyString())).thenReturn(7L);
   var all=java.util.stream.IntStream.rangeClosed(1,7).mapToObj(ChatStorageOracle::message).toList();
   when(messages.findByConversationIdOrderBySequenceNoAsc(anyString())).thenReturn(all);
   when(messages.findRecentByConversationId(anyString(),any(Pageable.class))).thenAnswer(inv->{var page=(Pageable)inv.getArgument(1);return all.reversed().stream().limit(page.getPageSize()).toList();});
   when(messages.findBeforeSequenceNo(anyString(),anyInt(),any(Pageable.class))).thenAnswer(inv->{int before=inv.getArgument(1);var page=(Pageable)inv.getArgument(2);return all.reversed().stream().filter(m->m.getSequenceNo()<before).limit(page.getPageSize()).toList();});
   when(messages.deleteFromSequence(anyString(),anyInt())).thenReturn(2);
   when(citations.findByMessageIds(any())).thenReturn(Map.of());
   var svc=new ChatSessionService(sessions,messages,attachments);
   var msgSvc=new ChatMessageService(messages,svc,citations,attachments);
   var exporter=new ChatMarkdownExporter(svc,msgSvc,ZoneId.of("Asia/Shanghai"));
   var controller=new ChatSessionController(svc,msgSvc,mock(ChatCompletionService.class),mock(AiProperties.class),mock(ContextBudgetService.class),exporter,mock(UsageStatsService.class));
   var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
   var uri=java.net.URI.create(c.get("path").asText());
   var req=MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(c.get("method").asText()),uri.getPath()).contentType(c.get("contentType").asText()).content(c.get("body").asText());
   if(uri.getRawQuery()!=null)for(String parameter:uri.getRawQuery().split("&")){String[] parts=parameter.split("=",2);req.queryParam(java.net.URLDecoder.decode(parts[0],StandardCharsets.UTF_8),parts.length>1?java.net.URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"");}
   var response=mvc.perform(req).andReturn().getResponse();var body=mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
   var data=body.get("data");if(data instanceof ObjectNode obj&&obj.has("conversationId")){obj.remove("createdAt");obj.remove("updatedAt");if(c.get("method").asText().equals("POST"))obj.put("conversationId","<generated>");}
   var row=new LinkedHashMap<String,Object>();row.put("status",response.getStatus());row.put("code",body.get("code").asInt());row.put("message",body.get("message").asText());row.put("data",data);binds.add(row);
  }output.put("bindings",binds);
  var exports=new ArrayList<Object>();for(var c:input.get("exports")){
   var svc=mock(ChatSessionService.class);var msgs=mock(ChatMessageService.class);var session=new ChatSessionEntity("c-existing",c.get("title").asText(),WHEN,WHEN);
   when(svc.requireSession("c-existing")).thenReturn(session);var list=new ArrayList<ChatMessageDto>();
   for(var m:c.get("messages")){var cs=mapper.treeToValue(m.get("citations"),ChatCitationDto[].class);var at=mapper.treeToValue(m.get("attachments"),ChatAttachmentDto[].class);list.add(new ChatMessageDto(m.get("id").asLong(),m.get("role").asText(),m.get("content").asText(),m.get("sequenceNo").asInt(),WHEN,List.of(cs),List.of(at)));}
   when(msgs.list("c-existing")).thenReturn(list);var doc=new ChatMarkdownExporter(svc,msgs,ZoneId.of(c.get("zone").asText())).export("c-existing");
   var row=new LinkedHashMap<String,Object>();row.put("filename",doc.filename());row.put("markdown",doc.markdown());row.put("encodedFilename",java.net.URLEncoder.encode(doc.filename(),StandardCharsets.UTF_8).replace("+","%20"));exports.add(row);
  }output.put("exports",exports);
  var snapshots=new ArrayList<Object>();for(var c:input.get("snapshots"))snapshots.add(attachments.fromJson(c.isNull()?null:c.asText()));output.put("snapshots",snapshots);
  var queries=new ArrayList<Object>();for(var c:input.get("queries")){var q=new ChatSessionQuery(c.get("keyword").asText(),c.get("page").asInt(),c.get("size").asInt());var row=new LinkedHashMap<String,Object>();row.put("page",q.page());row.put("size",q.size());row.put("pattern",q.keyword()==null?"":SearchPatterns.likePattern(q.keyword()));queries.add(row);}output.put("queries",queries);
  var titles=new ArrayList<Object>();for(var c:input.get("titles")){var repo=mock(ChatSessionRepository.class);var msgs=mock(ChatMessageRepository.class);var session=new ChatSessionEntity("c-existing",c.get("title").asText(),WHEN,WHEN);when(repo.findById("c-existing")).thenReturn(Optional.of(session));when(msgs.countByConversationId("c-existing")).thenReturn(c.get("count").asLong());new ChatSessionService(repo,msgs,attachments).initializeTitleFromFirstMessage("c-existing",c.get("content").asText());
   // 固定 JDBC 的 UTF-8 编码后文本：Java 截断孤立代理项实际写入问号。
   titles.add(new String(session.getTitle().getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8));}output.put("titles",titles);
  System.out.println("ORACLE "+mapper.writeValueAsString(output));
 }
}
