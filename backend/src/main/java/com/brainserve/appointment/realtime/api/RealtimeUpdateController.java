package com.brainserve.appointment.realtime.api;

import com.brainserve.appointment.realtime.application.RealtimeUpdateHub;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/realtime")
public class RealtimeUpdateController {

    private final RealtimeUpdateHub updateHub;

    public RealtimeUpdateController(RealtimeUpdateHub updateHub) {
        this.updateHub = updateHub;
    }

    @GetMapping(
            path = "/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    @PreAuthorize("isAuthenticated()")
    public SseEmitter stream() {
        return updateHub.connect();
    }
}