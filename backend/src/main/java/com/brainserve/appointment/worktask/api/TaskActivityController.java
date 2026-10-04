package com.brainserve.appointment.worktask.api;

import com.brainserve.appointment.audit.api.ActivityHistory;
import com.brainserve.appointment.worktask.application.TaskActivityService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/work-tasks/{id}")
public class TaskActivityController {
    private final TaskActivityService service;
    public TaskActivityController(TaskActivityService service){this.service=service;}
    @GetMapping("/activity") ActivityHistory.Page timeline(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.timeline(actor(jwt),id,page,size);}
    @GetMapping("/comments") TaskActivityService.Discussion comments(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.comments(actor(jwt),id,page,size);}
    @PostMapping("/comments") TaskActivityService.Comment create(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody TaskActivityService.Create request){return service.create(actor(jwt),id,request);}
    @PutMapping("/comments/{commentId}") TaskActivityService.Comment edit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID commentId,@RequestBody TaskActivityService.Edit request){return service.edit(actor(jwt),id,commentId,request);}
    @DeleteMapping("/comments/{commentId}") TaskActivityService.Comment remove(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID commentId,@RequestParam long expectedVersion){return service.remove(actor(jwt),id,commentId,expectedVersion);}
    @GetMapping("/comments/{commentId}/evidence/{evidenceId}/download") ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID commentId,@PathVariable UUID evidenceId){
        var d=service.download(actor(jwt),id,commentId,evidenceId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.document().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,org.springframework.http.ContentDisposition.attachment().filename(d.document().filename(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(d.bytes());
    }
    @ModelAttribute void noCache(jakarta.servlet.http.HttpServletResponse response){response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");}
    private UUID actor(Jwt jwt){return UUID.fromString(jwt.getSubject());}
    /** Body-free hint consumed only after the comment transaction commits. */
    public record CommentNotificationRequested(UUID taskId,UUID commentId,UUID senderUserId,UUID recipientUserId) {}
}
