package com.brainserve.appointment.integration.application;

import java.util.Set;
import java.util.UUID;

/** The provider boundary accepts only opaque identity, a generic summary and UTC appointment slots. */
public final class CalendarProjection {
    private static final Set<String> UPSERT = Set.of("APPROVED", "CHECKED_IN", "IN_MEETING");
    private static final Set<String> DELETE = Set.of("CANCELLED", "REJECTED", "NO_SHOW", "EXPIRED");
    private CalendarProjection() {}

    public static String action(String status) {
        if (UPSERT.contains(status)) return "UPSERT";
        if (DELETE.contains(status)) return "DELETE";
        return "SKIP";
    }

    /** Hex digits and the prefix fit Google's base32hex alphabet; stable across retries and process restarts. */
    public static String eventId(UUID connection, UUID resource) {
        return "bs" + connection.toString().replace("-", "") + resource.toString().replace("-", "");
    }

}
