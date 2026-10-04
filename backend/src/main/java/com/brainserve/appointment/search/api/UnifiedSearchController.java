package com.brainserve.appointment.search.api;

import com.brainserve.appointment.search.application.UnifiedSearchService;
import com.brainserve.appointment.shared.api.SearchContract;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/search")
@PreAuthorize("isAuthenticated()")
public class UnifiedSearchController {
    private final UnifiedSearchService service;
    public UnifiedSearchController(UnifiedSearchService service){this.service=service;}
    @GetMapping
    public ResponseEntity<SearchContract.Response> search(@AuthenticationPrincipal Jwt jwt,@RequestParam String q,
            @RequestParam(defaultValue="0") int appointmentsPage,@RequestParam(defaultValue="0") int visitorsPage,
            @RequestParam(defaultValue="0") int employeesPage,@RequestParam(defaultValue="0") int worksheetsPage,
            @RequestParam(defaultValue="5") int size){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.search(UUID.fromString(jwt.getSubject()),q,appointmentsPage,visitorsPage,employeesPage,worksheetsPage,size));
    }
    @GetMapping("/{type}/{id}/open")
    public ResponseEntity<SearchContract.OpenRecord> open(@AuthenticationPrincipal Jwt jwt,@PathVariable String type,@PathVariable UUID id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.open(UUID.fromString(jwt.getSubject()),type,id));
    }
}
