package com.personalos.task.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.task.dto.*;
import com.personalos.task.repository.WorkspaceRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
class WorkspaceBoundaryTest {
    @Test void createAndPatchNormalizeNamesWithoutErasingUnchangedFields() {
        var repo = mock(WorkspaceRepository.class);
        var dto = mock(ProjectDto.class);
        when(repo.save(any(), any())).thenReturn(dto);
        var service = new WorkspaceService(repo);
        assertThat(service.save(null, new ProjectChange(" name ", null, null))).isSameAs(dto);
        verify(repo).save(null, new ProjectChange("name", null, null));
        var id = UUID.randomUUID();
        assertThat(service.save(id, new ProjectChange(null, "description", true))).isSameAs(dto);
        assertThat(service.save(id, new ProjectChange(" rename ", "", false))).isSameAs(dto);
        verify(repo).save(id, new ProjectChange("rename", "", false));
        assertThatThrownBy(() -> service.save(id, new ProjectChange(null, "x".repeat(2001), null))).hasMessageContaining("2000");
        reset(repo);
        assertThatThrownBy(() -> service.save(id, new ProjectChange(null, null, null))).hasMessageContaining("项目不存在");
    }
}
