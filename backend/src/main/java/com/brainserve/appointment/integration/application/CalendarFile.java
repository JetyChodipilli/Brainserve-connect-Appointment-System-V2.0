package com.brainserve.appointment.integration.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** RFC 5545 UTF-8 text with CRLF and lines folded at 75 octets without splitting a code point. */
public final class CalendarFile {
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private CalendarFile() {}
    public record Event(UUID id, long revision, Instant start, Instant end) {}
    public static byte[] render(List<Event> events, Instant generatedAt) {
        StringBuilder out = new StringBuilder();
        line(out, "BEGIN:VCALENDAR");
        line(out, "VERSION:2.0");
        line(out, "PRODID:-//BrainServe//Appointment Calendar//EN");
        line(out, "CALSCALE:GREGORIAN");
        line(out, "METHOD:PUBLISH");
        for (Event event : events) {
            if (!event.end().isAfter(event.start())) throw new IllegalArgumentException("Invalid calendar slot");
            line(out, "BEGIN:VEVENT");
            line(out, "UID:" + event.id() + "@brainserve.invalid");
            line(out, "DTSTAMP:" + UTC.format(generatedAt));
            line(out, "DTSTART:" + UTC.format(event.start()));
            line(out, "DTEND:" + UTC.format(event.end()));
            line(out, "SEQUENCE:" + Math.min(Integer.MAX_VALUE, Math.max(0, event.revision())));
            line(out, "SUMMARY:" + escape("BrainServe appointment"));
            line(out, "STATUS:CONFIRMED");
            line(out, "END:VEVENT");
        }
        line(out, "END:VCALENDAR");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n")
                .replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,");
    }
    static void line(StringBuilder out, String value) {
        int octets = 0;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            String character = new String(Character.toChars(cp));
            int bytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (octets + bytes > 75) { out.append("\r\n "); octets = 1; }
            out.append(character); octets += bytes; offset += Character.charCount(cp);
        }
        out.append("\r\n");
    }
}
