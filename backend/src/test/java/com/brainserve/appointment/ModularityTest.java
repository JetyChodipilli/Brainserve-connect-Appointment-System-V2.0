package com.brainserve.appointment;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModularityTest {

    @Test
    void moduleDependenciesRespectPublicInterfaces() {
        Set<String> publicBoundaryViolations = ApplicationModules
                .of(BrainServeAppointmentApplication.class)
                .detectViolations()
                .getMessages()
                .stream()
                .filter(message -> !message.contains("Cycle detected: Slice "))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        assertTrue(
                publicBoundaryViolations.isEmpty(),
                () -> "Application modules use non-public types:\n"
                        + String.join("\n", publicBoundaryViolations)
        );
    }
}
