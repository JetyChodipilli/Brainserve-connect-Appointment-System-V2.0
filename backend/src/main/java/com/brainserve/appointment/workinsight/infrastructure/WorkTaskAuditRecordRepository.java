package com.brainserve.appointment.workinsight.infrastructure;

import com.brainserve.appointment.workinsight.domain.WorkTaskAuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkTaskAuditRecordRepository extends JpaRepository<WorkTaskAuditRecord, UUID> {
    Optional<WorkTaskAuditRecord> findByWorkTaskId(UUID workTaskId);
    List<WorkTaskAuditRecord> findTop1000ByWeekStartOrderByHrAuditedAtDesc(LocalDate weekStart);
    List<WorkTaskAuditRecord> findTop1000ByWeekStartAndDepartmentIdOrderByHrAuditedAtDesc(
            LocalDate weekStart, UUID departmentId);
    List<WorkTaskAuditRecord> findTop1000ByDepartmentIdOrderByHrAuditedAtDesc(UUID departmentId);
    List<WorkTaskAuditRecord> findTop500ByTeamLeadUserIdOrderByHrAuditedAtDesc(UUID teamLeadUserId);
    List<WorkTaskAuditRecord> findTop500ByEmployeeIdOrderByHrAuditedAtDesc(UUID employeeId);
}
