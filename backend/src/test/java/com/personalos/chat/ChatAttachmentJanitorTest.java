package com.personalos.chat;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.chat.service.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ChatAttachmentJanitorTest {
    @TempDir Path dir;
    final ChatMessageRepository messages=mock(ChatMessageRepository.class);
    final ChatAttachmentService attachments=mock(ChatAttachmentService.class);
    Path file(String name,boolean old) throws Exception {
        var path=Files.writeString(dir.resolve(name),"synthetic attachment");
        if(old)Files.setLastModifiedTime(path,FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        return path;
    }
    @Test void disabledJanitorTouchesNeitherDatabaseNorStorage() {
        new ChatAttachmentJanitor(messages,attachments,false).pruneOrphans(); verifyNoInteractions(messages,attachments);
    }
    @Test void onlyOldUnreferencedServerFilesAreDeleted() throws Exception {
        var orphan=file(UUID.randomUUID().toString(),true);var recent=file(UUID.randomUUID().toString(),false);
        var referenced=file(UUID.randomUUID().toString(),true);var unknown=file("user-notes.txt",true);
        var folder=Files.createDirectory(dir.resolve(UUID.randomUUID().toString()));
        when(attachments.uploadDirectory()).thenReturn(dir);when(messages.findAllAttachmentJson()).thenReturn(List.of("snapshot"));
        when(attachments.fromJson("snapshot")).thenReturn(Arrays.asList(null,new ChatAttachmentDto(null,"missing",null,0),new ChatAttachmentDto(referenced.getFileName().toString(),"saved","text/plain",1)));
        new ChatAttachmentJanitor(messages,attachments,true).pruneOrphans();
        assertThat(orphan).doesNotExist();for(Path p:List.of(recent,referenced,unknown,folder))assertThat(p).exists();
        verify(attachments).pruneRemote(eq(Set.of(referenced.getFileName().toString())),argThat(cutoff -> cutoff.isBefore(Instant.now().minus(Duration.ofHours(23)))));
    }
    @Test void absentDirectoryStillPrunesRemoteAndEmptyScanIsSafe() {
        when(attachments.uploadDirectory()).thenReturn(dir.resolve("absent"));new ChatAttachmentJanitor(messages,attachments,true).pruneOrphans();
        verify(attachments).pruneRemote(eq(Set.of()),any());
        when(attachments.uploadDirectory()).thenReturn(dir);new ChatAttachmentJanitor(messages,attachments,true).pruneOrphans();
        assertThat(dir).isEmptyDirectory();
    }
}
