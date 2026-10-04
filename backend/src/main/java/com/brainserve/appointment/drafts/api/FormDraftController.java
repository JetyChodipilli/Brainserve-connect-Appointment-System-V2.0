package com.brainserve.appointment.drafts.api;
import com.brainserve.appointment.drafts.application.FormDraftService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/drafts/{form}/{context}")
public class FormDraftController {
    private final FormDraftService service;
    public FormDraftController(FormDraftService service){this.service=service;}
    private UUID owner(Jwt jwt){return UUID.fromString(jwt.getSubject());}
    @GetMapping public ResponseEntity<Lookup> get(@AuthenticationPrincipal Jwt jwt,@PathVariable FormDraftService.Form form,@PathVariable String context){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Lookup(service.read(owner(jwt),form,context)));}
    @PutMapping public ResponseEntity<FormDraftService.Draft> save(@AuthenticationPrincipal Jwt jwt,@PathVariable FormDraftService.Form form,@PathVariable String context,@RequestBody FormDraftService.Save request){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(owner(jwt),form,context,request));}
    @DeleteMapping public ResponseEntity<Void> discard(@AuthenticationPrincipal Jwt jwt,@PathVariable FormDraftService.Form form,@PathVariable String context,@RequestParam Long expectedRevision){service.discard(owner(jwt),form,context,expectedRevision);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    @PostMapping("/submit") public ResponseEntity<FormDraftService.Receipt> submit(@AuthenticationPrincipal Jwt jwt,@PathVariable FormDraftService.Form form,@PathVariable String context,@RequestBody FormDraftService.Submit request){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.submit(owner(jwt),form,context,request));}
    public record Lookup(FormDraftService.Draft draft){}
}
