package com.brainserve.appointment.integration;

import com.brainserve.appointment.integration.api.IntegrationModels;
import com.brainserve.appointment.integration.application.IntegrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationModelsTest {
    @Test void secretRequestsDoNotPrintCredentials() {
        String secret = "private-credential-never-print";
        assertThat(new IntegrationModels.Create(UUID.randomUUID(),IntegrationModels.Provider.SIMULATOR_CALENDAR,"Calendar",secret,Instant.now()).toString()).doesNotContain(secret);
        assertThat(new IntegrationModels.Reconnect(0L,secret,Instant.now()).toString()).doesNotContain(secret);
    }
    @Test void scopesAreFixedAndReadOnly() {
        assertThat(IntegrationModels.Provider.SIMULATOR_CALENDAR.scopes()).containsExactly("calendar.events.write");
        assertThat(IntegrationModels.Provider.SIMULATOR_MESSAGING.scopes()).containsExactly("messages.send");
        assertThat(IntegrationModels.Provider.values()).hasSize(2);
    }
    @Test void retryBackoffIsBoundedAndDoesNotOverflow() {
        assertThat(IntegrationService.retryDelay(1)).isEqualTo(30);
        assertThat(IntegrationService.retryDelay(2)).isEqualTo(60);
        assertThat(IntegrationService.retryDelay(5)).isEqualTo(480);
        assertThat(IntegrationService.retryDelay(Integer.MAX_VALUE)).isEqualTo(480);
    }
    @Test void publicAttemptProjectionHasNoCredentialPayloadOrLeaseToken() throws Exception {
        String serialized = new ObjectMapper().writeValueAsString(new IntegrationModels.Attempt(UUID.randomUUID(),UUID.randomUUID(),1,1,"SUCCESS",null,null));
        assertThat(serialized).doesNotContain("credentialCiphertext","payload","leaseToken");
    }
}
