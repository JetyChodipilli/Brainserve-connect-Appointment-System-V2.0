package com.brainserve.appointment.appointment.api;

import com.brainserve.appointment.appointment.application.AppointmentTimelineService;
import com.brainserve.appointment.audit.api.ActivityHistory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/appointments/{id}/activity")
public class AppointmentTimelineController {
    private final AppointmentTimelineService service;
    public AppointmentTimelineController(AppointmentTimelineService service){this.service=service;}
    @ModelAttribute void noCache(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    @GetMapping ActivityHistory.Page timeline(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.timeline(UUID.fromString(jwt.getSubject()),id,page,size);}
}
