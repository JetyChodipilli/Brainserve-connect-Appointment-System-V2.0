package com.brainserve.appointment.worktask.application;

import java.time.*;
import java.util.*;

/** Metric arithmetic shared by workload summaries and their downloadable observations. */
public final class WorkMetricCalculator {
    private WorkMetricCalculator() {}
    public static Double percentile(List<Double> values, double percentile) {
        if (!Double.isFinite(percentile) || percentile < 0 || percentile > 1) throw new IllegalArgumentException("Percentile outside [0,1]");
        List<Double> valid = values.stream().filter(Objects::nonNull).filter(Double::isFinite).filter(v -> v >= 0).sorted().toList();
        if (valid.isEmpty()) return null;
        double index = (valid.size() - 1) * percentile;
        int lower = (int) Math.floor(index), upper = (int) Math.ceil(index);
        return valid.get(lower) + (valid.get(upper) - valid.get(lower)) * (index - lower);
    }
    public static Double rate(long numerator, long denominator) {
        if (numerator < 0 || denominator < 0 || numerator > denominator) throw new IllegalArgumentException("Invalid cohort");
        return denominator == 0 ? null : 100d * numerator / denominator;
    }
    public static boolean acceptedOnTime(Instant acceptance, LocalDate originalDue, ZoneId officeZone, Instant asOf) {
        return acceptance != null && originalDue != null && !acceptance.isAfter(asOf)
                && acceptance.isBefore(originalDue.plusDays(1).atStartOfDay(officeZone).toInstant());
    }
    /** Quoted CSV does not itself prevent spreadsheet execution; neutralize leading control/formula tokens. */
    public static String csv(Object value) {
        String text = value == null ? "" : value.toString();
        String leading = text.stripLeading();
        if ((!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0)
                || (!text.isEmpty() && (text.charAt(0) == '\t' || text.charAt(0) == '\r' || text.charAt(0) == '\n'))) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
