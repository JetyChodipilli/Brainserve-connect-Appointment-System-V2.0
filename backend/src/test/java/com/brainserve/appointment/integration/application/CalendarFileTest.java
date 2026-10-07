package com.brainserve.appointment.integration.application;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarFileTest {
    @Test void utcGenericEventsHaveOpaqueUidCrLfAndNoInvitations() {
        Instant start = Instant.parse("2026-10-08T09:00:00Z");
        String file = new String(CalendarFile.render(List.of(new CalendarFile.Event(UUID.randomUUID(), 2, start, start.plusSeconds(1800))), start.minusSeconds(60)), StandardCharsets.UTF_8);
        assertThat(file).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n").endsWith("END:VCALENDAR\r\n")
                .contains("DTSTART:20261008T090000Z\r\n", "DTEND:20261008T093000Z\r\n", "SEQUENCE:2\r\n", "SUMMARY:BrainServe appointment\r\n")
                .doesNotContain("ATTENDEE", "ORGANIZER", "TZID", "DESCRIPTION", "visitor", "purpose");
        assertThat(file.replace("\r\n", "")).doesNotContain("\n", "\r");
    }
    @Test void utf8FoldingPreservesCodePointsAndSeventyFiveOctetPhysicalLines() {
        String original = "SUMMARY:" + "界🙂".repeat(45);
        StringBuilder folded = new StringBuilder();
        CalendarFile.line(folded, original);
        for (String line : folded.toString().split("\r\n")) assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        assertThat(folded.toString().replace("\r\n ", "")).isEqualTo(original + "\r\n");
        assertThat(new String(folded.toString().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(folded.toString());
    }
    @Test void textEscapingCannotInjectAPropertyAndInvalidSlotsFail() {
        assertThat(CalendarFile.escape("a,b;c\\d\r\nATTENDEE:private")).isEqualTo("a\\,b\\;c\\\\d\\nATTENDEE:private");
        Instant now = Instant.now();
        assertThatThrownBy(() -> CalendarFile.render(List.of(new CalendarFile.Event(UUID.randomUUID(), 0, now, now)), now)).isInstanceOf(IllegalArgumentException.class);
    }
}
