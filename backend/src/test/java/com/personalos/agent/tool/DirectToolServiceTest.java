package com.personalos.agent.tool;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.personalos.common.ai.ToolContextKeys;
import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.idempotency.*;
import com.personalos.common.json.JsonCodec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

class DirectToolServiceTest {
    static final String KEY = "10000000-0000-4000-8000-000000000001";
    final JsonCodec json = new JsonCodec(new ObjectMapper());

    DirectToolService service(ToolCallback callback) {
        var repository = mock(OperationRecordRepository.class);
        var records = new ConcurrentHashMap<String, OperationRecordEntity>();
        when(repository.findByIdempotencyKey(any())).thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(0))));
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            OperationRecordEntity row = call.getArgument(0);
            if (records.putIfAbsent(row.getIdempotencyKey(), row) != null) throw new DataIntegrityViolationException("duplicate");
            return row;
        });
        return new DirectToolService(List.of(callback), new IdempotencyService(repository), json);
    }
    ToolCallback callback(String name) {
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name(name).description("test").inputSchema("{\"type\":\"object\",\"properties\":{}}").build());
        return callback;
    }
    @Test void transportRetryBindsCanonicalArgumentsAndSession() {
        var callback = callback("mcp_test_change");
        var count = new AtomicInteger();
        when(callback.call(any(), any(ToolContext.class))).thenAnswer(call -> {
            count.incrementAndGet();
            ToolContext context = call.getArgument(1);
            assertThat(context.getContext()).containsEntry(ToolContextKeys.TURN_ID, "cmd:" + KEY).containsKey(ToolContextKeys.EXECUTION_BUDGET);
            return json.toJson(ToolExecutionResult.ok("done", Map.of("remaining", 2)));
        });
        var service = service(callback);
        var args = Map.<String,Object>of("nested", Map.of("z",2,"a",1));
        var first = service.execute("c", KEY, "mcp_test_change", args, () -> {});
        var replay = service.execute("c", KEY.toUpperCase(), "mcp_test_change", Map.of("nested",Map.of("a",1,"z",2)), () -> {});
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.result()).isEqualTo(first.result());
        assertThat(count).hasValue(1);
        assertThatThrownBy(() -> service.execute("other", KEY, "mcp_test_change", args, () -> {})).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.execute("c", KEY, "mcp_test_change", Map.of("nested",3), () -> {})).isInstanceOf(BusinessException.class);
        assertThat(count).hasValue(1);
    }
    @Test void concurrentRetryExecutesOnce() throws Exception {
        var callback = callback("createTask");
        var count = new AtomicInteger();
        when(callback.call(any(), any(ToolContext.class))).thenAnswer(call -> {count.incrementAndGet(); return json.toJson(ToolExecutionResult.ok("done",null));});
        var service = service(callback);
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<Void>> work = new ArrayList<>();
            for (int i=0; i<32; i++) work.add(() -> {try {service.execute("c",KEY,"createTask",Map.of("title","one"),()->{});} catch (BusinessException ignored) {} return null;});
            for (var future : executor.invokeAll(work)) future.get();
        }
        assertThat(count).hasValue(1);
    }
    @Test void confirmationAndReadRetryKeepExactOriginalReceipt() {
        var callback = callback("deleteTask");
        when(callback.call(any(),any(ToolContext.class))).thenReturn(json.toJson(ToolExecutionResult.requiresConfirmation("待确认","one")));
        var service = service(callback);
        assertThat(service.execute("c",KEY,"deleteTask",Map.of(),()->{}).result().confirmationId()).isEqualTo("one");
        var replay = service.execute("c",KEY,"deleteTask",Map.of(),()->{});
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.result().confirmationId()).isEqualTo("one");
        verify(callback,times(1)).call(any(),any(ToolContext.class));
        service.execute("c",UUID.randomUUID().toString(),"deleteTask",Map.of(),()->{});
        verify(callback,times(2)).call(any(),any(ToolContext.class));
    }
    @Test void failedRemoteActionNeverReexecutes() {
        var callback = callback("mcp_test_change");
        var quantity = new AtomicInteger(3);
        when(callback.call(any(),any(ToolContext.class))).thenAnswer(call -> {quantity.decrementAndGet();throw new IllegalStateException("lost response");});
        var service = service(callback);
        assertThat(service.execute("c",KEY,"mcp_test_change",Map.of(),()->{}).result().status()).isEqualTo("ERROR");
        assertThatThrownBy(()->service.execute("c",KEY,"mcp_test_change",Map.of(),()->{})).isInstanceOf(BusinessException.class).hasMessageContaining("核对");
        assertThat(quantity).hasValue(2);
    }
    @Test void validatesBeforeCallingAndFiltersRunControlTools() {
        var callback = callback("createTask");
        var service = service(callback);
        assertThatThrownBy(()->service.execute("c","bad","createTask",Map.of(),()->{})).isInstanceOf(BusinessException.class);
        for (String blocked:List.of("askUser","startPlanStep","missing")) assertThatThrownBy(()->service.execute("c",KEY,blocked,Map.of(),()->{})).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.execute("c",KEY,"createTask",null,()->{})).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.execute("c",KEY,"createTask",Map.of("big","a".repeat(65537)),()->{})).isInstanceOf(BusinessException.class);
        verify(callback,never()).call(any(),any(ToolContext.class));
        assertThat(service(callback("askUser")).catalog()).isEmpty();
        assertThat(service.catalog()).extracting(DirectToolService.Description::name).containsExactly("createTask");
    }
}
