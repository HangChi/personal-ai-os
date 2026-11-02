package com.personalos.chat;

import static org.assertj.core.api.Assertions.assertThat;
import com.personalos.chat.dto.ChatStreamEvents;
import com.personalos.chat.service.RunEventBuffer;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class RunEventBufferTest {
    @Test void coalescesDeltasAndFlushesBeforeControlAndTerminal() {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            List<String> events = new ArrayList<>();
            var buffer = new RunEventBuffer((name, payload) -> events.add(name + ":" +
                    (payload instanceof ChatStreamEvents.Delta delta ? delta.content() : payload)), scheduler);
            buffer.accept("delta", new ChatStreamEvents.Delta("你"));
            buffer.accept("delta", new ChatStreamEvents.Delta("好"));
            buffer.accept("activity", "tool");
            buffer.accept("delta", new ChatStreamEvents.Delta("！"));
            buffer.accept("done", "结束");
            buffer.accept("delta", new ChatStreamEvents.Delta("迟到"));
            assertThat(events).containsExactly("delta:你好", "activity:tool", "delta:！", "done:结束");
        } finally { scheduler.shutdownNow(); }
    }
}
