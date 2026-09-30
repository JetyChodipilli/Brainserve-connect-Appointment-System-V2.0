package com.brainserve.appointment.reporting.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record HistoryRow(UUID id, Instant occurredAt, HistoryDataset dataset, UUID departmentId,
                         String primaryLabel, String secondaryLabel, String status,
                         Map<String, Object> details) {}
