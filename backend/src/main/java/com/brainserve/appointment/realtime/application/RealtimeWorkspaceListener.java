package com.brainserve.appointment.realtime.application;

import com.brainserve.appointment.realtime.api.WorkspaceChangeEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RealtimeWorkspaceListener {
    private final RealtimeUpdateHub updateHub;

    public RealtimeWorkspaceListener(RealtimeUpdateHub updateHub) {
        this.updateHub = updateHub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkspaceChange(WorkspaceChangeEvent ignored) {
        updateHub.broadcastRefresh();
    }
}
