package com.brainserve.appointment.iam.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Current authorization for long-lived connections. Storage failures propagate so callers can fail closed with an availability response. */
public interface SessionAccess {
    boolean isActive(UUID accountId, UUID familyId, Instant mfaVerifiedAt, Set<String> authorities);
}
