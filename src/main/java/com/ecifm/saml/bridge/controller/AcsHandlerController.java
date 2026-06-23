package com.ecifm.saml.bridge.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.ecifm.saml.bridge.service.MasSyncService;

@Controller
public class AcsHandlerController {

    private static final Logger log = LoggerFactory.getLogger(AcsHandlerController.class);

    @Value("${mas.base-url}")
    private String masBaseUrl;

    @Value("${mas.context}")
    private String masContext;

    private final MasSyncService masSyncService;

    public AcsHandlerController(MasSyncService masSyncService) {
        this.masSyncService = masSyncService;
    }

    @GetMapping("/")
    public ResponseEntity<String> defaultLanding() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "/redirect")
                .build();
    }

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("ecifm-saml-bridge is running");
    }

    @GetMapping("/redirect")
    public ResponseEntity<Void> ssoRedirect(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request,
            HttpServletResponse response) {

        if (jwt == null) {
            log.warn("No JWT found in request - redirecting to MAS without sync");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, masBaseUrl + masContext)
                    .build();
        }

        String email = extractEmail(jwt);
        List<String> groups = extractGroups(jwt);
        String bearerToken = jwt.getTokenValue();

        log.info("SSO redirect for user: {}, groups: {}", email, groups);

        boolean syncSucceeded = masSyncService.syncUser(bearerToken, email, groups);

        if (!syncSucceeded) {
            log.warn("SSOConnect did not complete successfully for user: {}", email);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate, private, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);

        String redirectUrl = masBaseUrl + masContext;
        log.info("Redirecting user to: {}", redirectUrl);

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, redirectUrl)
                .build();
    }

    private String extractEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            email = jwt.getClaimAsString("preferred_username");
        }
        if (email == null || email.isBlank()) {
            email = jwt.getSubject();
        }
        return email;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractGroups(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList("groups");
        if (groups != null && !groups.isEmpty()) {
            return groups;
        }

        Object groupsClaim = jwt.getClaims().get("groups");
        if (groupsClaim instanceof List) {
            return (List<String>) groupsClaim;
        }

        return List.of();
    }
}
