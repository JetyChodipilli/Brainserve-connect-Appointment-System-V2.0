package com.brainserve.appointment.worktask.api;

import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.worktask.application.DepartmentWorkTaskService;
import com.brainserve.appointment.worktask.domain.DepartmentWorkTask;
import com.brainserve.appointment.worktask.domain.WorkTaskStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/work-tasks")
public class DepartmentWorkTaskController {
    private final DepartmentWorkTaskService service;
    public DepartmentWorkTaskController(DepartmentWorkTaskService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN','TEAM_LEAD') and hasAuthority('WORK_TASK_CREATE')")
    TaskResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest request) {
        return TaskResponse.from(service.create(userId(jwt), new DepartmentWorkTaskService.CreateCommand(
                request.employeeId(), request.title(), request.description(), request.dueDate())));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('WORK_TASK_READ')")
    List<TaskResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(userId(jwt), employeeId(jwt, false)).stream().map(TaskResponse::from).toList();
    }

    @GetMapping("/workspace")
    @PreAuthorize("hasAnyRole('HR_ADMIN','TEAM_LEAD') and hasAuthority('WORK_TASK_CREATE')")
    DepartmentWorkTaskService.Workspace workspace(@AuthenticationPrincipal Jwt jwt) {
        return service.workspace(userId(jwt));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAuthority('WORK_TASK_PROGRESS')")
    TaskResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                       @Valid @RequestBody UpdateRequest request) {
        return TaskResponse.from(service.start(userId(jwt), employeeId(jwt, false), id, request.note()));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('WORK_TASK_PROGRESS')")
    TaskResponse complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                          @Valid @RequestBody UpdateRequest request) {
        return TaskResponse.from(service.complete(userId(jwt), employeeId(jwt, false), id, request.note()));
    }

    @PostMapping("/{id}/revise-rework")
    @PreAuthorize("hasRole('EMPLOYEE') and hasAuthority('WORK_TASK_PROGRESS')")
    TaskResponse reviseRework(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                              @Valid @RequestBody RequiredUpdateRequest request) {
        return TaskResponse.from(service.reviseEmployeeRework(
                userId(jwt), employeeId(jwt, true), id, request.note()));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('TEAM_LEAD') and hasAuthority('WORK_TASK_REVIEW')")
    TaskResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                         @Valid @RequestBody UpdateRequest request) {
        return TaskResponse.from(service.approve(userId(jwt), id, request.note()));
    }

    @PostMapping("/{id}/request-changes")
    @PreAuthorize("hasRole('TEAM_LEAD') and hasAuthority('WORK_TASK_REVIEW')")
    TaskResponse requestChanges(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                @Valid @RequestBody RequiredUpdateRequest request) {
        return TaskResponse.from(service.requestChanges(userId(jwt), id, request.note()));
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasRole('EMPLOYEE') and hasAuthority('WORK_TASK_PROGRESS')")
    TaskResponse acknowledge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TaskResponse.from(service.acknowledge(userId(jwt), employeeId(jwt, true), id));
    }

    @GetMapping("/performance")
    @PreAuthorize("hasRole('HR_ADMIN') and hasAuthority('WORK_TASK_PERFORMANCE_READ')")
    List<PerformanceResponse> performance(@AuthenticationPrincipal Jwt jwt) {
        return service.performance(userId(jwt)).stream().map(PerformanceResponse::from).toList();
    }

    private UUID userId(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private UUID employeeId(Jwt jwt, boolean required) {
        String value = jwt.getClaimAsString("employeeId");
        if (value == null && required) throw new BusinessException("EMPLOYEE_PROFILE_NOT_LINKED",
                "This login is not linked to an employee profile", HttpStatus.UNPROCESSABLE_ENTITY);
        return value == null ? null : UUID.fromString(value);
    }

    public record CreateRequest(@NotNull UUID employeeId,
                                @NotBlank @Size(max = 160) String title,
                                @NotBlank @Size(min = 5, max = 1000) String description,
                                @NotNull @FutureOrPresent LocalDate dueDate) {}
    public record UpdateRequest(@Size(max = 1000) String note) {}
    public record RequiredUpdateRequest(@NotBlank @Size(max = 1000) String note) {}
    public record TaskResponse(UUID id, UUID departmentId, UUID employeeId, UUID teamLeadUserId,
                               UUID assignedByUserId, String assignedByRole, String assigneeRole,
                               String title, String description, String departmentBranch, LocalDate dueDate,
                               WorkTaskStatus status, String employeeUpdate, String teamLeadReview,
                               String insightReviewSource, String insightReviewReason,
                               Instant insightReviewRequestedAt, int reworkCycle,
                               Instant startedAt, Instant completedAt, Instant approvedAt,
                               Instant acknowledgedAt, Instant createdAt, long version) {
        static TaskResponse from(DepartmentWorkTask value) { return new TaskResponse(value.getId(),
                value.getDepartmentId(), value.getEmployeeId(), value.getTeamLeadUserId(),
                value.getAssignedByUserId(), value.getAssignedByRole(), value.getAssigneeRole(), value.getTitle(),
                value.getDescription(), value.getDepartmentBranch(), value.getDueDate(), value.getStatus(),
                value.getEmployeeUpdate(), value.getTeamLeadReview(), value.getInsightReviewSource(),
                value.getInsightReviewReason(), value.getInsightReviewRequestedAt(), value.getReworkCycle(), value.getStartedAt(),
                value.getCompletedAt(), value.getApprovedAt(), value.getAcknowledgedAt(),
                value.getCreatedAt(), value.getVersion()); }
    }
    public record PerformanceResponse(UUID teamLeadUserId, UUID departmentId, long totalTasks,
                                      long completedTasks, long approvedTasks, long inProgressTasks,
                                      long pendingReviewTasks, long overdueTasks, long completionRate,
                                      Instant lastApprovedAt) {
        static PerformanceResponse from(DepartmentWorkTaskService.Performance value) {
            return new PerformanceResponse(value.teamLeadUserId(), value.departmentId(), value.totalTasks(),
                    value.completedTasks(), value.approvedTasks(), value.inProgressTasks(),
                    value.pendingReviewTasks(), value.overdueTasks(), value.completionRate(),
                    value.lastApprovedAt());
        }
    }
}
