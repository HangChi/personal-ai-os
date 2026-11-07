package com.personalos.common.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.config.RuntimeRole;
import org.junit.jupiter.api.Test;

class ModelConcurrencyGateBoundaryTest {
    @Test void validatesAndRestoresLocalPermitOnEveryCoordinationFailure() {
        assertThatThrownBy(() -> new ModelConcurrencyGate(0)).isInstanceOf(IllegalArgumentException.class);
        var db = mock(DatabaseCoordination.class);
        var background = mock(DatabaseCoordination.Lease.class);
        var gate = new ModelConcurrencyGate(2);
        gate.coordinate(db, new RuntimeRole("index"));
        when(db.acquire("model-background", 1)).thenReturn(background);
        when(db.acquire("model-concurrency", 2)).thenReturn(null);
        assertThat(gate.tryAcquire()).isNull();
        verify(background).close();
        reset(db, background);
        when(db.acquire("model-background", 1)).thenReturn(background);
        when(db.acquire("model-concurrency", 2)).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(gate::tryAcquire).isInstanceOf(IllegalStateException.class);
        verify(background).close();
        reset(db);
        when(db.acquire("model-background", 1)).thenReturn(null);
        assertThat(gate.tryAcquire()).isNull();
        gate.coordinate(null, new RuntimeRole("api"));
        try (var first = gate.tryAcquire(); var second = gate.tryAcquire()) {
            assertThat(first).isNotNull(); assertThat(second).isNotNull();
            assertThat(gate.tryAcquire()).isNull();
        }
        try (var restored = gate.tryAcquire()) { assertThat(restored).isNotNull(); }
    }
    @Test void closesBothSharedLeasesOnceEvenWhenSharedReleaseFails() {
        var db = mock(DatabaseCoordination.class);
        var background = mock(DatabaseCoordination.Lease.class);
        var shared = mock(DatabaseCoordination.Lease.class);
        when(db.acquire("model-background", 1)).thenReturn(background);
        when(db.acquire("model-concurrency", 1)).thenReturn(shared);
        var gate = new ModelConcurrencyGate(1); gate.coordinate(db, new RuntimeRole("delivery"));
        var lease = gate.tryAcquire();
        doThrow(new IllegalStateException("failed")).when(shared).close();
        assertThatThrownBy(lease::close).isInstanceOf(IllegalStateException.class);
        lease.close(); verify(background).close(); verify(shared).close();
        reset(shared);
        var next = gate.tryAcquire(); assertThat(next).isNotNull(); next.close();
    }
    @Test void foregroundFailureAndUncoordinatedBackgroundRestorePermits() {
        var db = mock(DatabaseCoordination.class);
        var gate = new ModelConcurrencyGate(1);
        gate.coordinate(db, new RuntimeRole("api"));
        assertThat(gate.tryAcquire()).isNull();
        when(db.acquire("model-concurrency", 1)).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(gate::tryAcquire).isInstanceOf(IllegalStateException.class);
        gate.coordinate(null, new RuntimeRole("index"));
        try (var lease = gate.tryAcquire()) { assertThat(lease).isNotNull(); }
    }
}
