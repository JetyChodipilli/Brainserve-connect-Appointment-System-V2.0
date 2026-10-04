package com.brainserve.appointment.worktask.api;

import com.brainserve.appointment.worktask.application.WorkAnalyticsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/work-analytics")
public class WorkAnalyticsController {
    private final WorkAnalyticsService service;
    public WorkAnalyticsController(WorkAnalyticsService service){this.service=service;}
    private UUID actor(Jwt jwt){return UUID.fromString(jwt.getSubject());}
    private <T> ResponseEntity<T> fresh(T body){return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(body);}
    @GetMapping("/context")
    public ResponseEntity<WorkAnalyticsService.Context> context(@AuthenticationPrincipal Jwt jwt){return fresh(service.context(actor(jwt)));}
    @GetMapping("/workload")
    public ResponseEntity<WorkAnalyticsService.Workload> workload(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) UUID departmentId){return fresh(service.workload(actor(jwt),departmentId));}
    @GetMapping("/summary")
    public ResponseEntity<WorkAnalyticsService.Summary> summary(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(required=false) UUID departmentId){return fresh(service.summary(actor(jwt),from,to,departmentId));}
    @GetMapping("/{metricId}/records")
    public ResponseEntity<WorkAnalyticsService.Records> records(@AuthenticationPrincipal Jwt jwt,@PathVariable String metricId,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(required=false) UUID departmentId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String metricVersion){return fresh(service.records(actor(jwt),metricId,from,to,departmentId,page,size,metricVersion));}
    @GetMapping(value="/{metricId}/export.csv",produces="text/csv")
    public ResponseEntity<String> export(@AuthenticationPrincipal Jwt jwt,@PathVariable String metricId,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(required=false) UUID departmentId,@RequestParam(required=false) String metricVersion){
        String csv=service.exportCsv(actor(jwt),metricId,from,to,departmentId,metricVersion);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+metricId+"-"+WorkAnalyticsService.VERSION+".csv\"")
                .contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8))
                .body(csv);
    }
}
