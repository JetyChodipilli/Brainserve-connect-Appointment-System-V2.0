package com.brainserve.appointment.reporting.api;

import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

public record HistoryFilter(Instant from, Instant to, UUID departmentId, String status,
                            String query, String cursor, int size) {
    public static HistoryFilter normalize(Instant from, Instant to, UUID departmentId, String status,
                                          String query, String cursor, Integer requestedSize,
                                          ZoneId officeZone, boolean organizationWide) {
        LocalDate today = LocalDate.now(officeZone);
        Instant normalizedFrom = from == null ? today.minusDays(6).atStartOfDay(officeZone).toInstant() : from;
        Instant normalizedTo = to == null ? today.plusDays(1).atStartOfDay(officeZone).toInstant() : to;
        if (!normalizedFrom.isBefore(normalizedTo)) {
            throw new BusinessException("INVALID_HISTORY_RANGE", "The history start must be before the end",
                    HttpStatus.BAD_REQUEST);
        }
        long maximumDays = organizationWide ? 3650 : 366;
        if (Duration.between(normalizedFrom, normalizedTo).toDays() > maximumDays) {
            throw new BusinessException("HISTORY_RANGE_TOO_LARGE",
                    "Choose a date range of " + maximumDays + " days or less", HttpStatus.BAD_REQUEST);
        }
        int size = Math.max(1, Math.min(requestedSize == null ? 50 : requestedSize, 100));
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            normalizedStatus = normalizedStatus.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
            if (!normalizedStatus.matches("[A-Z0-9_]{2,60}")) {
                throw new BusinessException("INVALID_HISTORY_STATUS", "The status filter is invalid", HttpStatus.BAD_REQUEST);
            }
        }
        String normalizedQuery = blankToNull(query);
        if (normalizedQuery != null && normalizedQuery.length() > 180) {
            throw new BusinessException("HISTORY_QUERY_TOO_LONG", "Search text must be 180 characters or fewer",
                    HttpStatus.BAD_REQUEST);
        }
        return new HistoryFilter(normalizedFrom, normalizedTo, departmentId, normalizedStatus,
                normalizedQuery, blankToNull(cursor), size);
    }

    public HistoryFilter withCursor(String nextCursor, int nextSize) {
        return new HistoryFilter(from, to, departmentId, status, query, nextCursor, nextSize);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
