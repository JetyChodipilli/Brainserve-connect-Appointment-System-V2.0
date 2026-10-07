package com.brainserve.appointment.support.application;

import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosticSnapshotCodecTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DiagnosticSnapshotCodec codec = new DiagnosticSnapshotCodec(mapper);

    @Test void typedAllowlistRoundTripsAndIncludesOnlyGeneratedSupportReference() {
        DiagnosticSnapshot expected = snapshot();
        String encoded = new String(codec.encode(expected), StandardCharsets.UTF_8);
        assertThat(codec.decode(encoded)).isEqualTo(expected);
        assertThat(encoded).contains(expected.supportReference().toString()).doesNotContain("correlationId", "ownerId", "label", "details_json");
        assertThat(encoded.getBytes(StandardCharsets.UTF_8).length).isLessThan(DiagnosticSnapshotCodec.MAX_BYTES);
    }

    @Test void unknownFieldsAtEveryDepthFailClosedInsteadOfBeingDropped() throws Exception {
        for (String key : List.of("email", "message", "credential", "url", "logs", "details_json", "correlationId", "label")) {
            ObjectNode poisoned = tree(); poisoned.put(key, "PRIVATE_PERSON private@example.test Bearer live-secret");
            invalid(poisoned);
            poisoned = tree(); ((ObjectNode) poisoned.get("connections")).put(key, "Bearer live-secret");
            invalid(poisoned);
            poisoned = tree(); ((ObjectNode) poisoned.get("deliveries")).put(key, "Bearer live-secret");
            invalid(poisoned);
        }
    }

    @Test void poisonedAllowlistedStringsCannotCarryConfigurationSecrets() throws Exception {
        for (String key : List.of("environment", "releaseVersion", "buildRevision", "databaseStatus", "supportReference", "generatedAt", "windowStart", "windowEnd")) {
            ObjectNode poisoned = tree(); poisoned.put(key, "PRIVATE_PERSON private@example.test Bearer live-secret https://credential@example.test");
            invalid(poisoned);
        }
        assertThat(DiagnosticSnapshotCodec.vettedVersion("1.2.3" )).isEqualTo("1.2.3");
        assertThat(DiagnosticSnapshotCodec.vettedVersion("1.2.3-user@example.test")).isEqualTo("UNKNOWN");
        assertThat(DiagnosticSnapshotCodec.vettedRevision("a".repeat(40))).isEqualTo("a".repeat(40));
        assertThat(DiagnosticSnapshotCodec.vettedRevision("a".repeat(40) + " token")).isEqualTo("UNKNOWN");
    }

    @Test void countTypesBoundsWindowAndRequiredFieldsAreStrict() throws Exception {
        ObjectNode value = tree(); value.remove("schemaVersion"); invalid(value);
        value = tree(); value.put("schemaVersion", 2); invalid(value);
        value = tree(); value.put("migrationVersion", -1); invalid(value);
        value = tree(); ((ObjectNode) value.get("connections")).put("active", "1"); invalid(value);
        value = tree(); ((ObjectNode) value.get("connections")).put("active", 1.5); invalid(value);
        value = tree(); ((ObjectNode) value.get("deliveries")).put("failed", -1); invalid(value);
        value = tree(); ((ObjectNode) value.get("deliveries")).put("failed", 1_000_001); invalid(value);
        value = tree(); value.put("windowStart", snapshot().windowEnd().minus(25, ChronoUnit.HOURS).toString()); invalid(value);
        value = tree(); value.put("generatedAt", snapshot().windowEnd().plusSeconds(1).toString()); invalid(value);
        assertThatThrownBy(() -> codec.decode(" ".repeat(65_537))).isInstanceOf(BusinessException.class);
    }

    @Test void generationJsonRejectsCoercionUnknownFieldsAndMalformedUuid() throws Exception {
        String id = UUID.randomUUID().toString();
        assertThat(mapper.readValue("{\"requestId\":\"" + id + "\",\"hours\":24}", SupportDiagnosticService.Generate.class))
                .isEqualTo(new SupportDiagnosticService.Generate(UUID.fromString(id), 24));
        for (String hours : List.of("\"24\"", "24.5", "true", "null", "2147483648"))
            assertThatThrownBy(() -> mapper.readValue("{\"requestId\":\"" + id + "\",\"hours\":" + hours + "}", SupportDiagnosticService.Generate.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue("{\"requestId\":\"bad\",\"hours\":24}", SupportDiagnosticService.Generate.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue("{\"requestId\":\"" + id + "\",\"hours\":24,\"ownerId\":\"foreign\"}", SupportDiagnosticService.Generate.class)).isInstanceOf(Exception.class);
    }

    private ObjectNode tree() throws Exception { return (ObjectNode) mapper.readTree(codec.encode(snapshot())); }
    private void invalid(ObjectNode value) { assertThatThrownBy(() -> codec.decode(value.toString())).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("SUPPORT_SNAPSHOT_INVALID"); }
    private DiagnosticSnapshot snapshot() {
        Instant end = Instant.parse("2026-10-07T10:00:00Z");
        return new DiagnosticSnapshot(1, UUID.fromString("00000000-0000-4000-a000-000000000001"), end,
                end.minus(24, ChronoUnit.HOURS), end, DiagnosticSnapshot.Environment.STAGING, "1.2.3", "a".repeat(40),
                DiagnosticSnapshot.DatabaseStatus.AVAILABLE, 66, new DiagnosticSnapshot.ConnectionCounts(2, 1, 3),
                new DiagnosticSnapshot.DeliveryCounts(1, 2, 3, 4, 5, 6, 7));
    }
}
