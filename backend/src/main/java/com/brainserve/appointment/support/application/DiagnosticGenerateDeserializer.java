package com.brainserve.appointment.support.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Whole-number hours and UUID request IDs must be supplied as their JSON types without coercion. */
public class DiagnosticGenerateDeserializer extends StdDeserializer<SupportDiagnosticService.Generate> {
    public DiagnosticGenerateDeserializer() { super(SupportDiagnosticService.Generate.class); }
    @Override
    public SupportDiagnosticService.Generate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode tree = parser.getCodec().readTree(parser);
        Set<String> fields = new HashSet<>();
        tree.fieldNames().forEachRemaining(fields::add);
        JsonNode hours = tree.get("hours"), request = tree.get("requestId");
        if (!tree.isObject() || !fields.equals(Set.of("hours", "requestId")) || hours == null
                || !hours.isIntegralNumber() || !hours.canConvertToInt() || request == null || !request.isTextual())
            return (SupportDiagnosticService.Generate) context.handleUnexpectedToken(SupportDiagnosticService.Generate.class, parser);
        UUID id;
        try {
            id = UUID.fromString(request.textValue());
            if (!id.toString().equals(request.textValue())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            return (SupportDiagnosticService.Generate) context.handleUnexpectedToken(SupportDiagnosticService.Generate.class, parser);
        }
        return new SupportDiagnosticService.Generate(id, hours.intValue());
    }
}
