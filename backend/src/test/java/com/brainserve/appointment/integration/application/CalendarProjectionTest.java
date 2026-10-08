package com.brainserve.appointment.integration.application;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CalendarProjectionTest {
    @Test void onlyApprovedStatesUpsertTerminalCancellationDeletesAndEverythingElseRetainsHistory() {
        for (String status : List.of("APPROVED", "CHECKED_IN", "IN_MEETING")) assertThat(CalendarProjection.action(status)).isEqualTo("UPSERT");
        for (String status : List.of("CANCELLED", "REJECTED", "NO_SHOW", "EXPIRED")) assertThat(CalendarProjection.action(status)).isEqualTo("DELETE");
        for (String status : List.of("DRAFT", "PENDING_APPROVAL", "RESCHEDULE_REQUESTED", "RESCHEDULED", "CHECKED_OUT", "COMPLETED")) assertThat(CalendarProjection.action(status)).isEqualTo("SKIP");
    }
    @Test void stableIdsSurviveRetriesAndDifferentOwnersCannotCollide() {
        UUID connection = UUID.randomUUID(), resource = UUID.randomUUID();
        String id = CalendarProjection.eventId(connection, resource);
        assertThat(id).matches("[0-9a-v]{5,1024}").hasSize(66);
        assertThat(CalendarProjection.eventId(connection, resource)).isEqualTo(id);
        assertThat(CalendarProjection.eventId(UUID.randomUUID(), resource)).isNotEqualTo(id);
        assertThat(CalendarProjection.eventId(connection, UUID.randomUUID())).isNotEqualTo(id);
    }
}
