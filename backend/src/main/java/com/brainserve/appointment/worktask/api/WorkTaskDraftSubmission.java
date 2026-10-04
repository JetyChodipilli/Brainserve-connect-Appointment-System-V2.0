package com.brainserve.appointment.worktask.api;

import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.worktask.application.DepartmentWorkTaskService;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Public draft boundary; the existing worksheet writers own scope, lifecycle and notifications. */
@Service
public class WorkTaskDraftSubmission {
    private final DepartmentWorkTaskService tasks;
    private final CurrentAccountAuthority authority;
    private final Validator validator;
    public WorkTaskDraftSubmission(DepartmentWorkTaskService tasks, CurrentAccountAuthority authority, Validator validator) {
        this.tasks=tasks; this.authority=authority; this.validator=validator;
    }
    public void requireEligible(UUID actor, String context) {
        var current=authority.requireActive(actor);
        if ("new".equals(context)) { tasks.workspace(actor); return; }
        String[] parts=context.split("~",-1);
        if (parts.length!=2) throw missing();
        UUID id=parse(parts[0]);
        var task=tasks.list(actor,current.employeeId()).stream().filter(value->value.getId().equals(id)).findFirst().orElseThrow(WorkTaskDraftSubmission::missing);
        String required=switch(parts[1]) {
            case "start","complete" -> "WORK_TASK_PROGRESS";
            case "revise-rework" -> "ROLE_TEAM_LEAD".equals(current.role()) ? "WORK_TASK_REVIEW" : "WORK_TASK_PROGRESS";
            case "approve","request-changes" -> "WORK_TASK_REVIEW";
            case "insight-rework" -> "WORK_TASK_REVIEW";
            case "hr-rework" -> "WORK_INSIGHT_AUDIT";
            default -> throw missing();
        };
        if(!current.permissions().contains(required)) throw missing();
        if(Set.of("start","complete","revise-rework").contains(parts[1]) && !Set.of("ROLE_EMPLOYEE","ROLE_TEAM_LEAD").contains(current.role())) throw missing();
        if(Set.of("approve","request-changes","insight-rework").contains(parts[1]) && !"ROLE_TEAM_LEAD".equals(current.role())) throw missing();
        boolean own=current.employeeId()!=null && current.employeeId().equals(task.getEmployeeId());
        if(Set.of("start","complete","revise-rework").contains(parts[1]) && !own) throw missing();
        if(Set.of("approve","request-changes","insight-rework").contains(parts[1]) && !actor.equals(task.getTeamLeadUserId())) throw missing();
        if("hr-rework".equals(parts[1]) && !"ROLE_HR_ADMIN".equals(current.role())) throw missing();
    }
    public DepartmentWorkTaskController.TaskResponse create(UUID actor, Map<String,String> fields) {
        var request=new DepartmentWorkTaskController.CreateRequest(parse(fields.get("employeeId")),fields.get("title"),fields.get("description"),LocalDate.parse(fields.getOrDefault("dueDate","")));
        validate(request); tasks.workspace(actor);
        return DepartmentWorkTaskController.TaskResponse.from(tasks.create(actor,new DepartmentWorkTaskService.CreateCommand(request.employeeId(),request.title(),request.description(),request.dueDate())));
    }
    public DepartmentWorkTaskController.TaskResponse update(UUID actor,String context,Map<String,String> fields) {
        requireEligible(actor,context); var current=authority.requireActive(actor); String[] parts=context.split("~",-1);
        UUID id=parse(parts[0]); Long version=Long.valueOf(fields.getOrDefault("taskVersion","")); String note=fields.getOrDefault("note","");
        validate(new DepartmentWorkTaskController.UpdateRequest(note,version));
        if(Set.of("complete","request-changes","revise-rework").contains(parts[1])) validate(new DepartmentWorkTaskController.RequiredUpdateRequest(note,version));
        var task=switch(parts[1]) {
            case "start" -> tasks.start(actor,current.employeeId(),id,note,version);
            case "complete" -> tasks.complete(actor,current.employeeId(),id,note,version);
            case "approve" -> tasks.approve(actor,id,note,version);
            case "request-changes" -> tasks.requestChanges(actor,id,note,version);
            case "revise-rework" -> tasks.reviseEmployeeRework(actor,current.employeeId(),id,note,version);
            default -> throw missing();
        };
        return DepartmentWorkTaskController.TaskResponse.from(task);
    }
    private void validate(Object value) { if(!validator.validate(value).isEmpty()) throw new BusinessException("DRAFT_SUBMISSION_INVALID","Review all required worksheet fields before submitting",HttpStatus.BAD_REQUEST); }
    public boolean employeeDelivery(UUID actor,String context) { requireEligible(actor,context); return "ROLE_EMPLOYEE".equals(authority.requireActive(actor).role()); }
    private static UUID parse(String value) { try{return UUID.fromString(value);}catch(RuntimeException exception){throw missing();} }
    private static BusinessException missing(){return new BusinessException("DRAFT_NOT_FOUND","The draft is unavailable in your current scope",HttpStatus.NOT_FOUND);}
}
