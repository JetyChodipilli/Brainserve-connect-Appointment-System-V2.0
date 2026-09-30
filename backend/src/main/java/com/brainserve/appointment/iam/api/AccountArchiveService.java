package com.brainserve.appointment.iam.api;

import java.time.LocalDate;
import java.util.UUID;

public interface AccountArchiveService {
    void archiveAfterEmployeeTermination(UUID hrUserId, UUID ceoUserId, UUID employeeId,
                                         UUID terminationRequestId, String reason, LocalDate effectiveDate);
}
