package com.brainserve.appointment.workroutine.application;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Office wall-clock calendar; no persistence, server clock, or framework dependency. */
public final class RecurrenceCalendar {
    private RecurrenceCalendar() {}

    public record Definition(String frequency, int interval, LocalDate startDate, LocalDate endDate,
            LocalTime localTime, List<Integer> weekdays, Integer monthDay, String weekendPolicy,
            String holidayPolicy, List<LocalDate> holidays, ZoneId officeZone) {
        public Definition {
            weekdays = weekdays == null ? List.of() : new ArrayList<>(weekdays);
            holidays = holidays == null ? List.of() : new ArrayList<>(holidays);
            validate(frequency, interval, startDate, endDate, localTime, weekdays, monthDay,
                    weekendPolicy, holidayPolicy, holidays, officeZone);
            weekdays = List.copyOf(weekdays); holidays = List.copyOf(holidays);
        }
    }
    public record Occurrence(LocalDate occurrenceDate, Instant scheduledAt, LocalDate dueDate) {}

    private static void validate(String frequency, int interval, LocalDate start, LocalDate end,
            LocalTime time, List<Integer> weekdays, Integer monthDay, String weekend, String holiday,
            List<LocalDate> holidays, ZoneId zone) {
        if (!Set.of("DAILY", "WEEKLY", "MONTHLY").contains(frequency == null ? "" : frequency)
                || interval < 1 || interval > 12 || start == null || time == null || zone == null
                || time.getSecond() != 0 || time.getNano() != 0
                || !Set.of("INCLUDE", "SKIP").contains(weekend == null ? "" : weekend)
                || !Set.of("INCLUDE", "SKIP").contains(holiday == null ? "" : holiday)
                || (end != null && (end.isBefore(start) || end.isAfter(start.plusYears(5))))
                || holidays.size() > 366 || new HashSet<>(holidays).size() != holidays.size()
                || holidays.stream().anyMatch(Objects::isNull) || weekdays.stream().anyMatch(Objects::isNull)
                || new HashSet<>(weekdays).size() != weekdays.size()
                || weekdays.stream().anyMatch(d -> d < 1 || d > 7)
                || ("WEEKLY".equals(frequency) && weekdays.isEmpty())
                || ("MONTHLY".equals(frequency) && (monthDay == null || monthDay < 1 || monthDay > 31))) {
            throw new IllegalArgumentException("Invalid recurrence calendar definition");
        }
    }

    public static boolean accepts(Definition d, LocalDate date) {
        if (date.isBefore(d.startDate()) || (d.endDate() != null && date.isAfter(d.endDate()))) return false;
        if ("SKIP".equals(d.weekendPolicy()) && date.getDayOfWeek().getValue() >= 6) return false;
        if ("SKIP".equals(d.holidayPolicy()) && d.holidays().contains(date)) return false;
        return switch (d.frequency()) {
            case "DAILY" -> ChronoUnit.DAYS.between(d.startDate(), date) % d.interval() == 0;
            case "WEEKLY" -> d.weekdays().contains(date.getDayOfWeek().getValue())
                    && ChronoUnit.WEEKS.between(d.startDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                            date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) % d.interval() == 0;
            case "MONTHLY" -> ChronoUnit.MONTHS.between(YearMonth.from(d.startDate()), YearMonth.from(date)) % d.interval() == 0
                    && date.getDayOfMonth() == Math.min(d.monthDay(), date.lengthOfMonth());
            default -> false;
        };
    }

    public static LocalDate horizon(Definition d) {
        return d.endDate() == null ? d.startDate().plusYears(5) : d.endDate();
    }

    /** Inclusive next accepted date. A bounded scan also supports schedules with all dates excluded. */
    public static Optional<LocalDate> nextDate(Definition d, LocalDate from) {
        LocalDate date = from.isAfter(d.startDate()) ? from : d.startDate();
        // Open-ended routines remain active beyond the bounded preview horizon.
        LocalDate end = d.endDate() == null ? date.plusYears(5) : d.endDate();
        while (!date.isAfter(end)) {
            if (accepts(d, date)) return Optional.of(date);
            date = date.plusDays(1);
        }
        return Optional.empty();
    }

    /** Java's zone resolver advances gaps by their duration and chooses the earlier overlap offset. */
    public static Instant scheduledAt(Definition d, LocalDate date) {
        return date.atTime(d.localTime()).atZone(d.officeZone()).withEarlierOffsetAtOverlap().toInstant();
    }

    public static List<Occurrence> preview(Definition d, LocalDate officeToday, int dueOffsetDays, int count) {
        if (dueOffsetDays < 0 || dueOffsetDays > 365 || count < 1 || count > 100)
            throw new IllegalArgumentException("Invalid preview bounds");
        List<Occurrence> result = new ArrayList<>();
        LocalDate cursor = officeToday.isAfter(d.startDate()) ? officeToday : d.startDate();
        while (result.size() < count) {
            Optional<LocalDate> next = nextDate(d, cursor);
            if (next.isEmpty()) break;
            LocalDate date = next.get();
            LocalDate previewEnd=d.endDate()==null?officeToday.isAfter(d.startDate())?officeToday.plusYears(5):d.startDate().plusYears(5):d.endDate();
            if (date.isAfter(previewEnd)) break;
            result.add(new Occurrence(date, scheduledAt(d, date), date.plusDays(dueOffsetDays)));
            cursor = date.plusDays(1);
        }
        return List.copyOf(result);
    }
}
