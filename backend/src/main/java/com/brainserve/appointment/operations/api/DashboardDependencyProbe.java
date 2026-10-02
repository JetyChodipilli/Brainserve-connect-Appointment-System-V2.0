package com.brainserve.appointment.operations.api;

import com.brainserve.appointment.operations.application.IntegrationHealthService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** The dashboard receives probe observations, never connection details or exception messages. */
@Service
public class DashboardDependencyProbe {
    private static final Set<String> NAMES = Set.of("PostgreSQL", "Redis", "Kafka", "SMTP", "Object storage", "ClamAV");
    private final IntegrationHealthService health;

    public DashboardDependencyProbe(IntegrationHealthService health) { this.health = health; }

    public Snapshot inspect() {
        var result = health.inspect();
        List<Observation> observations = result.services().stream()
                .filter(service -> NAMES.contains(service.name()))
                .map(service -> new Observation(service.name(), service.ready())).toList();
        return new Snapshot(result.checkedAt(), observations);
    }

    public record Observation(String name, boolean ready) {}
    public record Snapshot(Instant checkedAt, List<Observation> observations) {}
}
