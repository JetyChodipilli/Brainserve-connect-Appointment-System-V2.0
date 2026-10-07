package com.brainserve.appointment.integration.google;

import com.brainserve.appointment.integration.api.IntegrationModels;
import com.brainserve.appointment.shared.application.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(GoogleCalendarConfiguration.ROOT)
public class GoogleCalendarController {
    private static final String COOKIE="__Secure-brainserve-google-browser";
    private final GoogleCalendarConsentService consents;
    private final GoogleCalendarConfiguration config;
    public GoogleCalendarController(GoogleCalendarConsentService consents,GoogleCalendarConfiguration config) {this.consents=consents;this.config=config;}
    @GetMapping("/config") @PreAuthorize("hasAuthority('ROLE_SYSTEM_ADMIN')")
    public ResponseEntity<Map<String,Object>> config(@AuthenticationPrincipal Jwt jwt) {
        // Reading metadata checks the real current authority and active family too.
        consents.consents(auth(jwt));
        return noStore(Map.of("configured",config.configured(),"scope",GoogleCalendarConfiguration.SCOPE,"usesDedicatedCalendar",true));
    }
    @PostMapping("/consents") @PreAuthorize("hasAuthority('ROLE_SYSTEM_ADMIN')")
    public ResponseEntity<GoogleCalendarConsentService.Consent> start(@AuthenticationPrincipal Jwt jwt,@RequestBody GoogleCalendarConsentService.Start command) {
        return noStore(consents.start(auth(jwt),command));
    }
    @GetMapping("/consents") @PreAuthorize("hasAuthority('ROLE_SYSTEM_ADMIN')")
    public ResponseEntity<List<GoogleCalendarConsentService.Consent>> consents(@AuthenticationPrincipal Jwt jwt) {return noStore(consents.consents(auth(jwt)));}
    @PostMapping("/consents/{id}/complete") @PreAuthorize("hasAuthority('ROLE_SYSTEM_ADMIN')")
    public ResponseEntity<IntegrationModels.Connection> complete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {return noStore(consents.complete(auth(jwt),id));}
    @GetMapping("/connections/{id}") @PreAuthorize("hasAuthority('ROLE_SYSTEM_ADMIN')")
    public ResponseEntity<GoogleCalendarConsentService.Metadata> metadata(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {return noStore(consents.metadata(auth(jwt),id));}
    @PostMapping("/connections/{id}/recover") @PreAuthorize("hasAuthority('ROLE_SYSTEM_ADMIN')")
    public ResponseEntity<IntegrationModels.Connection> recover(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody GoogleCalendarConsentService.Recover command) {return noStore(consents.recover(auth(jwt),id,command));}
    @PostMapping("/connections/{id}/revocation/retry") @PreAuthorize("hasAuthority('ROLE_SYSTEM_ADMIN')")
    public ResponseEntity<GoogleCalendarConsentService.Metadata> retry(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody IntegrationModels.Version command) {return noStore(consents.retryRevocation(auth(jwt),id,command.expectedVersion()));}
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(@RequestParam String ticket) {
        var authorization=consents.authorize(ticket);
        ResponseCookie cookie=ResponseCookie.from(COOKIE,authorization.browserToken()).httpOnly(true).secure(true).sameSite("Lax")
                .path(GoogleCalendarConfiguration.ROOT).maxAge(Duration.ofMinutes(10)).build();
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION,authorization.url()).header(HttpHeaders.SET_COOKIE,cookie.toString())
                .header("Referrer-Policy","no-referrer").header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/callback")
    public ResponseEntity<String> callback(@RequestParam String state,@RequestParam(required=false) String code,@RequestParam(required=false) String error,HttpServletRequest request) {
        String browser=null;
        if(request.getCookies()!=null)for(var cookie:request.getCookies())if(COOKIE.equals(cookie.getName())) {
            if(browser!=null)throw new BusinessException("CONSENT_BROWSER_MISMATCH","Return to the original browser tab",HttpStatus.BAD_REQUEST);
            browser=cookie.getValue();
        }
        consents.callback(state,code,error,browser);
        String page="<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Google Calendar consent</title><body><main><h1>Consent received</h1><p>Return to your original BrainServe tab and choose Finish connection. You can close this tab.</p></main></body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy","no-referrer").header("X-Content-Type-Options","nosniff")
                .header("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
                .header(HttpHeaders.SET_COOKIE,ResponseCookie.from(COOKIE,"").httpOnly(true).secure(true).sameSite("Lax").path(GoogleCalendarConfiguration.ROOT).maxAge(Duration.ZERO).build().toString()).body(page);
    }
    private static GoogleAccountGuard.Auth auth(Jwt jwt) {
        try {
            Object proof=jwt.getClaim("mfaVerifiedAt");
            return new GoogleAccountGuard.Auth(UUID.fromString(jwt.getSubject()),UUID.fromString(jwt.getClaimAsString("sid")),proof instanceof Number number?Instant.ofEpochSecond(number.longValue()):null);
        }catch(RuntimeException invalid) {throw new BusinessException("SESSION_REVOKED","Sign in again to continue",HttpStatus.UNAUTHORIZED);}
    }
    private static <T> ResponseEntity<T> noStore(T body) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
