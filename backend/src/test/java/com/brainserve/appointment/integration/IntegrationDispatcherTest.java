package com.brainserve.appointment.integration;

import com.brainserve.appointment.integration.application.IntegrationDispatcher;
import com.brainserve.appointment.integration.application.IntegrationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntegrationDispatcherTest {
    @Test void disabledSchedulerPerformsNoWork() {
        var service = mock(IntegrationService.class);
        new IntegrationDispatcher(service,false).dispatch();
        verifyNoInteractions(service);
    }
    @Test void oneFailedJobDoesNotPreventRemainingDueJobsFromCompleting() {
        var service = mock(IntegrationService.class);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var claim = new IntegrationService.Claim(second,UUID.randomUUID(),1);
        when(service.due()).thenReturn(List.of(first,second));
        when(service.claim(eq(first),any(Instant.class))).thenThrow(new IllegalStateException("sensitive exception must not be logged"));
        when(service.claim(eq(second),any(Instant.class))).thenReturn(Optional.of(claim));
        new IntegrationDispatcher(service,true).dispatch();
        verify(service).complete(eq(claim),any(Instant.class));
    }
}
