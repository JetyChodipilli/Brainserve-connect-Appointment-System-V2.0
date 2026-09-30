package com.brainserve.appointment.worktask.infrastructure;

import com.brainserve.appointment.worktask.domain.DepartmentWorkTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DepartmentWorkTaskRepository extends JpaRepository<DepartmentWorkTask, UUID> {
    List<DepartmentWorkTask> findTop200ByEmployeeIdOrderByCreatedAtDesc(UUID employeeId);
    List<DepartmentWorkTask> findTop500ByDepartmentIdOrderByCreatedAtDesc(UUID departmentId);
    List<DepartmentWorkTask> findTop1000ByOrderByCreatedAtDesc();
    List<DepartmentWorkTask> findAllByTeamLeadUserIdAndDepartmentId(UUID teamLeadUserId, UUID departmentId);

    @Query(value = """
            select team_lead_user_id as "teamLeadUserId",
                   department_id as "departmentId",
                   count(*) as "totalTasks",
                   count(*) filter (where status in ('COMPLETED', 'APPROVED', 'ACKNOWLEDGED')) as "completedTasks",
                   count(*) filter (where status in ('APPROVED', 'ACKNOWLEDGED')) as "approvedTasks",
                   count(*) filter (where status = 'IN_PROGRESS') as "inProgressTasks",
                   count(*) filter (where status = 'COMPLETED') as "pendingReviewTasks",
                   count(*) filter (where due_date < current_date and status not in ('APPROVED', 'ACKNOWLEDGED')) as "overdueTasks",
                   max(approved_at) as "lastApprovedAt"
              from department_work_task
             group by team_lead_user_id, department_id
             order by max(updated_at) desc
            """, nativeQuery = true)
    List<PerformanceProjection> performance();

    interface PerformanceProjection {
        UUID getTeamLeadUserId();
        UUID getDepartmentId();
        long getTotalTasks();
        long getCompletedTasks();
        long getApprovedTasks();
        long getInProgressTasks();
        long getPendingReviewTasks();
        long getOverdueTasks();
        Instant getLastApprovedAt();
    }
}
