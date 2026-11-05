package com.personalos.chat.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.personalos.agent.tool.DirectToolService;
import com.personalos.chat.service.ChatSessionService;
import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.web.DatabaseCoordination;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DirectToolControllerTest {
    final DirectToolService tools = mock(DirectToolService.class);
    final ChatSessionService sessions = mock(ChatSessionService.class);
    final DatabaseCoordination coordination = mock(DatabaseCoordination.class);
    final DirectToolController controller = new DirectToolController(tools,sessions,coordination);

    @Test void catalogAndValidationDoNotInvokeToolOrModel() throws Exception {
        when(tools.catalog()).thenReturn(List.of(new DirectToolService.Description("createTask","创建任务","{}")));
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/v1/chat/tools")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("createTask"));
        mvc.perform(post("/api/v1/chat/sessions/c/tools").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/chat/sessions/c/tools").header("Idempotency-Key","key").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/chat/sessions/c/tools").header("Idempotency-Key","key").contentType(MediaType.APPLICATION_JSON).content("{\"tool\":\"createTask\",\"arguments\":[]}")).andExpect(status().isBadRequest());
        verify(tools,never()).execute(any(),any(),any(),any(),any());
        verifyNoInteractions(sessions,coordination);
    }
    @Test void busySessionCannotExecuteAndSuccessfulExecutionReleasesLease() {
        var request = new DirectToolController.Request("createTask", Map.of("title","one"));
        assertThatThrownBy(()->controller.execute("c","key",request)).isInstanceOf(BusinessException.class).hasMessageContaining("正在执行");
        verify(tools,never()).execute(any(),any(),any(),any(),any());
        var lease = mock(DatabaseCoordination.Lease.class);
        when(coordination.acquire("chat-session:c",1)).thenReturn(lease);
        when(lease.valid()).thenReturn(true);
        when(tools.execute(eq("c"),eq("key"),eq("createTask"),eq(request.arguments()),any())).thenAnswer(call-> {
            ((Runnable)call.getArgument(4)).run();
            return new DirectToolService.Execution("key","createTask",ToolExecutionResult.ok("done",null),false);
        });
        assertThat(controller.execute("c","key",request).data().result().status()).isEqualTo("OK");
        verify(lease).close();
    }
    @Test void lostLeaseBlocksActionAndStillReleases() {
        var lease=mock(DatabaseCoordination.Lease.class);
        when(coordination.acquire("chat-session:c",1)).thenReturn(lease);
        when(tools.execute(any(),any(),any(),any(),any())).thenAnswer(call-> {((Runnable)call.getArgument(4)).run();return null;});
        assertThatThrownBy(()->controller.execute("c","key",new DirectToolController.Request("createTask",Map.of()))).isInstanceOf(BusinessException.class).hasMessageContaining("租约");
        verify(lease).close();
    }
}
