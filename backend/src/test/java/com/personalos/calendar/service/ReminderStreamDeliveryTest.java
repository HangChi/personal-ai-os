package com.personalos.calendar.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.personalos.calendar.dto.ReminderDto;
import com.personalos.common.exception.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ReminderStreamDeliveryTest {
    final ReminderService reminders = mock(ReminderService.class);
    final ExecutorService writers = mock(ExecutorService.class);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final List<SseEmitter> emitters = new ArrayList<>();
    final List<Runnable> completions = new ArrayList<>();
    ReminderStreamDelivery delivery;

    @BeforeEach void setUp() {
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                .when(writers).execute(any(Runnable.class));
        when(reminders.list(null, true, 500)).thenReturn(List.of());
        delivery = new ReminderStreamDelivery(reminders, metrics, () -> {
            SseEmitter emitter = mock(SseEmitter.class);
            doAnswer(call -> { completions.add(call.getArgument(0)); return null; })
                    .when(emitter).onCompletion(any());
            emitters.add(emitter);
            return emitter;
        }, writers);
    }

    @AfterEach void tearDown() { delivery.close(); metrics.close(); }

    @Test void idleDoesNotQueryDatabase() {
        delivery.publish();
        verifyNoInteractions(reminders);
    }

    @Test void oneReadServesAllConnectionsAndUnchangedSnapshotsAreSilent() throws Exception {
        delivery.open(); delivery.open();
        delivery.publish();
        verify(reminders).list(null, true, 500);
        for (SseEmitter emitter : emitters) verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        delivery.publish();
        for (SseEmitter emitter : emitters) verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        when(reminders.list(null, true, 500)).thenReturn(List.of(reminder()));
        delivery.publish();
        for (SseEmitter emitter : emitters) verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(metrics.get("personalos.reminders.stream.snapshots").counter().count()).isEqualTo(4);
    }

    @Test void reconnectReceivesSnapshotEvenIfExistingConnectionHasSeenIt() throws Exception {
        when(reminders.list(null, true, 500)).thenReturn(List.of(reminder()));
        delivery.open(); delivery.publish();
        delivery.open(); delivery.publish();
        verify(emitters.getFirst(), times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitters.getLast()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test void readFailureDoesNotEraseSnapshotAndNextTickRecovers() throws Exception {
        delivery.open();
        when(reminders.list(null, true, 500)).thenThrow(new IllegalStateException("db down"));
        delivery.publish();
        verify(emitters.getFirst(), never()).send(any(SseEmitter.SseEventBuilder.class));
        doReturn(List.of()).when(reminders).list(null, true, 500);
        delivery.publish();
        verify(emitters.getFirst()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test void failedWriterIsRemovedWithoutAffectingOtherConnections() throws Exception {
        delivery.open(); delivery.open();
        doThrow(new IOException("closed")).when(emitters.getFirst()).send(any(SseEmitter.SseEventBuilder.class));
        delivery.publish();
        verify(emitters.getFirst()).complete();
        verify(emitters.getLast()).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(metrics.get("personalos.reminders.stream.connections").gauge().value()).isEqualTo(1);
    }

    @Test void slowWriterDoesNotAccumulateTasksAndReceivesLatestSnapshot() throws Exception {
        List<Runnable> pending = new ArrayList<>();
        doAnswer(call -> { pending.add(call.getArgument(0)); return null; }).when(writers).execute(any(Runnable.class));
        delivery.open(); delivery.publish();
        when(reminders.list(null, true, 500)).thenReturn(List.of(reminder()));
        delivery.publish(); delivery.publish();
        assertThat(pending).hasSize(1);
        pending.removeFirst().run();
        delivery.publish();
        pending.removeFirst().run();
        verify(emitters.getFirst(), times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test void completionReleasesAdmissionSlot() {
        for (int i = 0; i < 32; i++) delivery.open();
        assertThatThrownBy(() -> delivery.open()).isInstanceOf(BusinessException.class);
        completions.getFirst().run();
        assertThatCode(() -> delivery.open()).doesNotThrowAnyException();
    }

    @Test void saturatedWritersFallBackRatherThanQueuingMoreWork() {
        doThrow(new RejectedExecutionException()).when(writers).execute(any(Runnable.class));
        delivery.open(); delivery.publish();
        verify(emitters.getFirst()).complete();
        assertThat(metrics.get("personalos.reminders.stream.connections").gauge().value()).isZero();
    }

    @Test void shutdownRejectsNewStreamsAndStopsReading() {
        delivery.open(); delivery.close(); delivery.publish();
        verify(emitters.getFirst()).complete();
        verifyNoInteractions(reminders);
        assertThatThrownBy(() -> delivery.open()).isInstanceOf(BusinessException.class);
    }

    private ReminderDto reminder() {
        return new ReminderDto(UUID.randomUUID(), "TASK", UUID.randomUUID(), "提醒", Instant.now(), Instant.now(), "PENDING", null);
    }
}
