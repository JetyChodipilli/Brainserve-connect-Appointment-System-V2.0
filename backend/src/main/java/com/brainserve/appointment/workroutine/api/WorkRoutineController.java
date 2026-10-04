package com.brainserve.appointment.workroutine.api;

import com.brainserve.appointment.workroutine.application.WorkRoutineService;
import com.brainserve.appointment.workroutine.application.WorkRoutineService.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/work-routines")
@PreAuthorize("hasAnyRole('HR_ADMIN','TEAM_LEAD') and hasAuthority('WORK_TASK_CREATE')")
public class WorkRoutineController {
    private final WorkRoutineService service;
    public WorkRoutineController(WorkRoutineService service) { this.service=service; }
    @GetMapping("/context") public Context context(@AuthenticationPrincipal Jwt jwt) { return service.context(actor(jwt)); }
    @GetMapping("/templates") public Page<Template> templates(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.templates(actor(jwt),page,size); }
    @GetMapping("/templates/{id}") public Template template(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return service.template(actor(jwt),id); }
    @PostMapping("/templates") public Template createTemplate(@AuthenticationPrincipal Jwt jwt,@RequestBody TemplateWrite request) { return service.createTemplate(actor(jwt),request); }
    @PutMapping("/templates/{id}") public Template updateTemplate(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody TemplateUpdate request) { return service.updateTemplate(actor(jwt),id,request); }
    @GetMapping("/schedules") public Page<Schedule> schedules(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.schedules(actor(jwt),page,size); }
    @PostMapping("/schedules") public Schedule createSchedule(@AuthenticationPrincipal Jwt jwt,@RequestBody ScheduleWrite request) { return service.createSchedule(actor(jwt),request); }
    @PostMapping("/preview") public Preview preview(@AuthenticationPrincipal Jwt jwt,@RequestBody ScheduleDefinition request) { return service.preview(actor(jwt),request); }
    @PostMapping("/schedules/{id}/state") public Schedule state(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody State request) { return service.setState(actor(jwt),id,request); }
    @GetMapping("/schedules/{id}/occurrences") public Page<Occurrence> occurrences(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.occurrences(actor(jwt),id,page,size); }
    @PostMapping("/schedules/{id}/occurrences/{date}/retry") public Occurrence retry(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable LocalDate date,@RequestBody Retry request) { return service.retry(actor(jwt),id,date,request); }
    @ModelAttribute void noCache(jakarta.servlet.http.HttpServletResponse response) { response.setHeader(org.springframework.http.HttpHeaders.CACHE_CONTROL,"no-store"); }
    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
