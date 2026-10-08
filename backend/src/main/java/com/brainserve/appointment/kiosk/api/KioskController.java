package com.brainserve.appointment.kiosk.api;
import com.brainserve.appointment.kiosk.application.KioskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/kiosk")
public class KioskController {
    private final KioskService service;
    public KioskController(KioskService service) {this.service=service;}
    @PostMapping("/session") public ResponseEntity<?> session(@RequestHeader("X-Kiosk-Token") String token) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.session(token));
    }
    @PostMapping("/intake") public ResponseEntity<?> intake(@RequestHeader("X-Kiosk-Token") String token,@Valid @RequestBody Pass request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.intake(token,request.token()));
    }
    public record Pass(@NotBlank @Size(max=500) String token) {}
}
