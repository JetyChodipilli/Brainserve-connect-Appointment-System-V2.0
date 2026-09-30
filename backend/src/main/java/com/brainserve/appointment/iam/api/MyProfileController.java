package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.application.MyProfileService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/profile")
@PreAuthorize("isAuthenticated()")
public class MyProfileController {
    private final MyProfileService service;
    public MyProfileController(MyProfileService service) { this.service = service; }

    @GetMapping("/me")
    MyProfileService.Profile me(@AuthenticationPrincipal Jwt jwt) {
        return service.profile(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping(value = "/me/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    MyProfileService.Profile uploadPhoto(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam("file") MultipartFile file) {
        return service.uploadPhoto(UUID.fromString(jwt.getSubject()), file);
    }
}
