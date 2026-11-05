package com.personalos.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personalos.calendar.entity.RecurrenceType;
import com.personalos.common.json.JsonCodec;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class DataImportCalendarTest {
    @ParameterizedTest
    @EnumSource(RecurrenceType.class)
    void importsEveryExportedRecurrence(RecurrenceType rule) {
        assertImported(rule.name(), rule.name());
        assertImported(" " + rule.name().toLowerCase(java.util.Locale.ROOT) + " ", rule.name());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"YEARLY", "unknown"})
    void keepsInvalidRecurrenceFallbackConsistentWithGo(String rule) {
        assertImported(rule, "NONE");
    }

    private void assertImported(String rule, String expected) {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyInt(), nullable(Object.class))).thenReturn(query);
        var codec = new JsonCodec(JsonMapper.builder().build());
        var service = new DataImportService(em, null, null, codec,
                mock(PlatformTransactionManager.class), null);
        var event = new ImportPayload.CalendarPayload(UUID.randomUUID(), "例会", null, null, false,
                Instant.parse("2026-11-05T09:00:00Z"), null, rule,
                Instant.parse("2026-12-31T09:00:00Z"), 30, null);
        var payload = new ImportPayload("1", null, null, List.of(event), null, null);
        // Exercise exported JSON binding as well as the actual SQL write path.
        var restored = codec.fromJson(codec.toJson(payload), ImportPayload.class);
        int count = ReflectionTestUtils.invokeMethod(service, "importCalendar", restored.calendarEvents());
        assertThat(count).isEqualTo(1);
        verify(query).setParameter(8, expected);
        verify(query).setParameter(10, 30);
        verify(query).executeUpdate();
    }
}
