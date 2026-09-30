package com.brainserve.appointment.workinsight.api;

import com.brainserve.appointment.workinsight.application.WorkInsightService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/work-insights")
public class WorkInsightController {
    private final WorkInsightService service;
    public WorkInsightController(WorkInsightService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAuthority('WORK_INSIGHT_READ')")
    List<WorkInsightService.Insight> list(@AuthenticationPrincipal Jwt jwt,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return service.list(actor(jwt), weekStart);
    }

    @GetMapping("/pending-hr-audit")
    @PreAuthorize("hasRole('HR_ADMIN') and hasAuthority('WORK_INSIGHT_AUDIT')")
    List<WorkInsightService.Insight> pendingHrAudit(@AuthenticationPrincipal Jwt jwt) {
        return service.pendingHrAudit(actor(jwt));
    }

    @GetMapping("/task-workflow-states")
    @PreAuthorize("hasAnyRole('EMPLOYEE','TEAM_LEAD') and hasAuthority('WORK_TASK_READ')")
    List<WorkInsightService.TaskWorkflowState> taskWorkflowStates(@AuthenticationPrincipal Jwt jwt) {
        return service.taskWorkflowStates(actor(jwt));
    }

    @PostMapping("/tasks/{taskId}/audit")
    @PreAuthorize("hasRole('HR_ADMIN') and hasAuthority('WORK_INSIGHT_AUDIT')")
    WorkInsightService.Insight audit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
        return service.markAudited(actor(jwt), taskId);
    }

    @PostMapping("/tasks/{taskId}/request-rework")
    @PreAuthorize("hasRole('HR_ADMIN') and hasAuthority('WORK_INSIGHT_AUDIT')")
    WorkInsightService.Insight requestRework(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId,
                                             @Valid @RequestBody ReworkRequest request) {
        return service.requestHrRework(actor(jwt), taskId, request.reason());
    }

    @PostMapping("/tasks/{taskId}/assign-rework")
    @PreAuthorize("hasRole('TEAM_LEAD') and hasAuthority('WORK_TASK_REVIEW')")
    WorkInsightService.Insight assignRework(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId,
                                            @Valid @RequestBody GuidanceRequest request) {
        return service.assignRework(actor(jwt), taskId, request.guidance());
    }

    @PostMapping("/tasks/{taskId}/revise-rework")
    @PreAuthorize("hasRole('TEAM_LEAD') and hasAuthority('WORK_TASK_REVIEW')")
    WorkInsightService.Insight reviseRework(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId,
                                            @Valid @RequestBody SubmissionUpdateRequest request) {
        return service.reviseReworkSubmission(actor(jwt), taskId, request.update());
    }

    @PostMapping("/{recordId}/ceo-decision")
    @PreAuthorize("hasRole('CEO') and hasAuthority('WORK_INSIGHT_CEO_APPROVE')")
    WorkInsightService.Insight decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID recordId,
                                      @Valid @RequestBody DecisionRequest request) {
        return service.decideByCeo(actor(jwt), recordId, request.approved(), request.remarks());
    }

    @PostMapping("/{recordId}/manager-decision")
    @PreAuthorize("hasRole('MANAGER') and hasAuthority('WORK_INSIGHT_MANAGER_APPROVE')")
    WorkInsightService.Insight managerDecision(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable UUID recordId,
                                               @Valid @RequestBody DecisionRequest request) {
        return service.decideByManager(actor(jwt), recordId, request.approved(), request.remarks());
    }

    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record DecisionRequest(@NotNull Boolean approved, @Size(max = 1000) String remarks) {}
    public record ReworkRequest(@NotBlank @Size(max = 1000) String reason) {}
    public record GuidanceRequest(@NotBlank @Size(max = 1000) String guidance) {}
    public record SubmissionUpdateRequest(@NotBlank @Size(max = 1000) String update) {}
}
