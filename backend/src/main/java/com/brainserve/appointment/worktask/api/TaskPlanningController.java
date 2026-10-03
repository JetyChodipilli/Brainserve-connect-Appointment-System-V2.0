package com.brainserve.appointment.worktask.api;

import com.brainserve.appointment.worktask.application.TaskPlanningService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/work-tasks/{id}")
public class TaskPlanningController {
    private final TaskPlanningService service;
    public TaskPlanningController(TaskPlanningService service){this.service=service;}
    private UUID actor(Jwt jwt){return UUID.fromString(jwt.getSubject());}
    @GetMapping("/planning")
    TaskPlanningService.Planning get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return service.get(actor(jwt),id);}
    @PutMapping("/planning")
    TaskPlanningService.Planning update(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@jakarta.validation.Valid @RequestBody TaskPlanningService.Update request){return service.update(actor(jwt),id,request);}
    @PutMapping("/checklist")
    TaskPlanningService.Planning tick(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@jakarta.validation.Valid @RequestBody TaskPlanningService.Tick request){return service.tick(actor(jwt),id,request);}
    @PostMapping("/blockers")
    TaskPlanningService.Planning raise(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@jakarta.validation.Valid @RequestBody TaskPlanningService.Raise request){return service.raise(actor(jwt),id,request);}
    @PostMapping("/blockers/{blockerId}/resolve")
    TaskPlanningService.Planning resolve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID blockerId,@jakarta.validation.Valid @RequestBody TaskPlanningService.Resolve request){return service.resolve(actor(jwt),id,blockerId,request);}
    @PutMapping("/blockers/{blockerId}/contact")
    TaskPlanningService.Planning contact(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID blockerId,@jakarta.validation.Valid @RequestBody TaskPlanningService.Contact request){return service.contact(actor(jwt),id,blockerId,request);}
    @PostMapping(value="/evidence",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    TaskPlanningService.Planning upload(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam long expectedVersion,@RequestPart MultipartFile file){return service.upload(actor(jwt),id,expectedVersion,file);}
    @DeleteMapping("/evidence/{evidenceId}")
    TaskPlanningService.Planning remove(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID evidenceId,@RequestParam long expectedVersion){return service.remove(actor(jwt),id,evidenceId,expectedVersion);}
    @GetMapping("/evidence/{evidenceId}/download")
    ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID evidenceId){
        var d=service.download(actor(jwt),id,evidenceId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.document().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+d.document().filename()+"\"")
                .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(d.bytes());
    }
}
