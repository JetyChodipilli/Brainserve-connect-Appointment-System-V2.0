package com.brainserve.appointment.iam.application;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {
    private final TotpService service = new TotpService();
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesTheSixDigitSuffixOfRfc6238Sha1Vectors() {
        long[] timestamps = {59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000L};
        String[] expected = {"287082", "081804", "050471", "005924", "279037", "353130"};
        for (int index = 0; index < timestamps.length; index++) {
            assertThat(service.codeAt(RFC_SECRET, Instant.ofEpochSecond(timestamps[index]))).isEqualTo(expected[index]);
        }
    }

    @Test
    void permitsOnlyOneAdjacentTimeStepAndNeverAnAlreadyConsumedStep() {
        Instant now = Instant.ofEpochSecond(1234567890);
        String code = service.codeAt(RFC_SECRET, now);
        long step = now.getEpochSecond() / 30;
        assertThat(service.matchingStep(RFC_SECRET, code, now, null)).hasValue(step);
        assertThat(service.matchingStep(RFC_SECRET, code, now.plusSeconds(30), null)).hasValue(step);
        assertThat(service.matchingStep(RFC_SECRET, code, now.minusSeconds(30), null)).hasValue(step);
        assertThat(service.matchingStep(RFC_SECRET, code, now.plusSeconds(60), null)).isEmpty();
        assertThat(service.matchingStep(RFC_SECRET, code, now, step)).isEmpty();
        assertThat(service.matchingStep(RFC_SECRET, code, now, step + 1)).isEmpty();
    }

    @Test
    void createsIndependentBase32SecretsAndAnEncodedAuthenticatorUri() {
        String first = service.newSecret();
        assertThat(first).matches("[A-Z2-7]{32}").isNotEqualTo(service.newSecret());
        assertThat(service.enrollmentUri("some+name@brainserve.in", first))
                .startsWith("otpauth://totp/BrainServe%20Connect%3Asome%2Bname%40brainserve.in?")
                .contains("secret=" + first).contains("digits=6&period=30");
        assertThat(service.matchingStep(first, " 123456", Instant.now(), null)).isEmpty();
        assertThat(service.matchingStep(first, null, Instant.now(), null)).isEmpty();
    }
}
