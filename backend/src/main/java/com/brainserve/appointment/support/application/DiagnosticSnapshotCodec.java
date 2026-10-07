package com.brainserve.appointment.support.application;

import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Validate both generated and retained exports; never serialize a raw database JSON document. */
@Component
public class DiagnosticSnapshotCodec {
    public static final int MAX_BYTES = 65_536;
    public static final int MAX_COUNT = 1_000_000;
    private static final Set<String> ROOT = Set.of("schemaVersion", "supportReference", "generatedAt",
            "windowStart", "windowEnd", "environment", "releaseVersion", "buildRevision", "databaseStatus",
            "migrationVersion", "connections", "deliveries");
    private static final Set<String> CONNECTIONS = Set.of("active", "needsReconnect", "revoked");
    private static final Set<String> DELIVERIES = Set.of("pending", "running", "delivered", "failed",
            "needsReconnect", "cancelled", "superseded");
    private final ObjectMapper mapper;

    public DiagnosticSnapshotCodec(ObjectMapper mapper) { this.mapper = mapper; }

    public byte[] encode(DiagnosticSnapshot snapshot) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(snapshot);
            decode(new String(bytes, StandardCharsets.UTF_8));
            return bytes;
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw invalid(); }
    }

    public DiagnosticSnapshot decode(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid();
        try {
            JsonNode root = mapper.readTree(json);
            fields(root, ROOT);
            if (integer(root.get("schemaVersion"), 1) != 1) throw invalid();
            UUID reference = UUID.fromString(text(root.get("supportReference")));
            if (!reference.toString().equals(text(root.get("supportReference"))) || reference.version() != 4 || reference.variant() != 2) throw invalid();
            Instant generated = Instant.parse(text(root.get("generatedAt")));
            Instant start = Instant.parse(text(root.get("windowStart")));
            Instant end = Instant.parse(text(root.get("windowEnd")));
            Duration duration = Duration.between(start, end);
            if (duration.compareTo(Duration.ofHours(1)) < 0 || duration.compareTo(Duration.ofHours(24)) > 0
                    || !generated.equals(end)) throw invalid();
            DiagnosticSnapshot.Environment.valueOf(text(root.get("environment")));
            if (!safeVersion(text(root.get("releaseVersion"))) || !safeRevision(text(root.get("buildRevision")))
                    || !"AVAILABLE".equals(text(root.get("databaseStatus")))) throw invalid();
            integer(root.get("migrationVersion"), 9_999);
            counts(root.get("connections"), CONNECTIONS);
            counts(root.get("deliveries"), DELIVERIES);
            return mapper.readerFor(DiagnosticSnapshot.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(root);
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw invalid(); }
    }

    public static String vettedVersion(String value) { return safeVersion(value) ? value : "UNKNOWN"; }
    public static String vettedRevision(String value) { return safeRevision(value) ? value : "UNKNOWN"; }
    private static boolean safeVersion(String value) {
        return value != null && ("UNKNOWN".equals(value)
                || value.matches("(?:0|[1-9][0-9]{0,3})\\.(?:0|[1-9][0-9]{0,3})\\.(?:0|[1-9][0-9]{0,3})"));
    }
    private static boolean safeRevision(String value) {
        return value != null && ("UNKNOWN".equals(value) || value.matches("[a-f0-9]{40}|[a-f0-9]{64}"));
    }
    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw invalid();
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw invalid();
    }
    private static void counts(JsonNode node, Set<String> keys) {
        fields(node, keys);
        for (String key : keys) integer(node.get(key), MAX_COUNT);
    }
    private static int integer(JsonNode node, int maximum) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()
                || node.intValue() < 0 || node.intValue() > maximum) throw invalid();
        return node.intValue();
    }
    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }
    private static BusinessException invalid() {
        return new BusinessException("SUPPORT_SNAPSHOT_INVALID", "The diagnostic snapshot failed export validation", HttpStatus.CONFLICT);
    }
}
