package com.brainserve.appointment.notification.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/** Calendar scheduling uses recipient time; transport retries keep their existing lease. */
public final class NotificationSchedule {
    private NotificationSchedule() {}

    public static Instant due(Instant created, String cadence, ZoneId zone, boolean quiet,
                              LocalTime start, LocalTime end) {
        ZonedDateTime local = created.atZone(zone);
        Instant due = switch (cadence) {
            case "HOURLY" -> local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant();
            case "DAILY" -> {
                Instant today=local.toLocalDate().atTime(9,0).atZone(zone).toInstant();
                yield today.isAfter(created)?today:local.toLocalDate().plusDays(1).atTime(9,0).atZone(zone).toInstant();
            }
            default -> created;
        };
        return afterQuiet(due, zone, quiet, start, end);
    }

    public static Instant afterQuiet(Instant candidate, ZoneId zone, boolean enabled,
                                     LocalTime start, LocalTime end) {
        if (!enabled) return candidate;
        ZonedDateTime local = candidate.atZone(zone);
        boolean overnight = start.isAfter(end);
        for(LocalDate day:java.util.List.of(local.toLocalDate().minusDays(1),local.toLocalDate())) {
            Instant begins=day.atTime(start).atZone(zone).withEarlierOffsetAtOverlap().toInstant();
            // Compare instants so the first occurrence of 01:45 cannot escape a later 01:30 end.
            Instant ends=(overnight?day.plusDays(1):day).atTime(end).atZone(zone).withLaterOffsetAtOverlap().toInstant();
            if(!candidate.isBefore(begins)&&candidate.isBefore(ends)) return ends;
        }
        return candidate;
    }
}
