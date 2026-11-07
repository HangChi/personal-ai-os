package com.personalos.chat.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.chat.dto.*;
import com.personalos.chat.entity.*;
import com.personalos.chat.repository.*;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ChatCitationBoundaryTest {
 @Test void missingAssistantAndEmptyHitsAreHarmlessAndWebSnapshotsRoundTrip(){
  var citations=mock(ChatMessageCitationRepository.class);var messages=mock(ChatMessageRepository.class);var s=new ChatCitationService(citations,messages);
  s.attachToLatestAssistant("c",null);s.attachToLatestAssistant("c",List.of());assertThat(s.findByMessageIds(List.of())).isEmpty();
  verifyNoInteractions(messages,citations);
  when(messages.findRecentByConversationId(eq("c"),any())).thenReturn(List.of());
  s.attachToLatestAssistant("c",null,List.of(new ChatCitationDto(1,"web","title","file",0,"body","https://example.test")));
  verify(citations,never()).saveAll(any());
  var message=mock(ChatMessageEntity.class);when(message.getMessageType()).thenReturn(MessageRole.ASSISTANT);when(message.getId()).thenReturn(7L);
  when(messages.findRecentByConversationId(eq("c"),any())).thenReturn(List.of(message));
  s.attachToLatestAssistant("c",null,List.of(new ChatCitationDto(1,TurnCitationCollector.WEB_DOCUMENT_ID,"title","file",0,"body",null)));
  var hit=mock(KnowledgeHitDto.class);
  s.attachToLatestAssistant("c",List.of(hit));
  var rows=List.of(new ChatMessageCitationEntity(7L,1,TurnCitationCollector.WEB_DOCUMENT_ID,0,"title","file","legacy content",Instant.EPOCH),
    new ChatMessageCitationEntity(7L,2,TurnCitationCollector.WEB_DOCUMENT_ID,0,"title","file","https://example.test"+System.lineSeparator()+"body",Instant.EPOCH));
  when(citations.findByMessageIdInOrderByMessageIdAscCitationIndexAsc(List.of(7L))).thenReturn(rows);
  var found=s.findByMessageIds(List.of(7L)).get(7L);assertThat(found.get(0).url()).isNull();assertThat(found.get(1).url()).isEqualTo("https://example.test");
 }
}
