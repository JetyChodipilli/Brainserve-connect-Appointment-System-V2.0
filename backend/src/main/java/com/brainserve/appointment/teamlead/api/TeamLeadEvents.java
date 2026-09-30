package com.brainserve.appointment.teamlead.api;

import java.util.UUID;

public final class TeamLeadEvents {
    private TeamLeadEvents() {}

    public record ReassignedForAccountClosure(UUID actorUserId, UUID departmentId,
                                               UUID previousTeamLeadUserId,
                                               UUID replacementTeamLeadUserId) {}
}
