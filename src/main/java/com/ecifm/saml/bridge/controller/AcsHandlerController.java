package com.ecifm.saml.bridge.controller;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.ecifm.saml.bridge.service.MasSyncService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Controller
public class AcsHandlerController {

    private static final Logger log = LoggerFactory.getLogger(AcsHandlerController.class);

    @Value("${mas.base-url}")
    private String masBaseUrl;

    @Value("${mas.context}")
    private String masContext;

    private final MasSyncService masSyncService;
    private final ObjectMapper objectMapper;

    public AcsHandlerController(MasSyncService masSyncService, ObjectMapper objectMapper) {
        this.masSyncService = masSyncService;
        this.objectMapper = objectMapper;
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
            @AuthenticationPrincipal OidcUser oidcUser,
            @RegisteredOAuth2AuthorizedClient("entra-id") OAuth2AuthorizedClient authorizedClient,
            HttpServletResponse response) {

        if (oidcUser == null || authorizedClient == null) {
            log.warn("No authenticated user found - redirecting to MAS without sync");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, masBaseUrl + masContext)
                    .build();
        }

        String email = extractEmail(oidcUser);
        String accessToken = authorizedClient.getAccessToken().getTokenValue();
        List<String> groups = extractGroupsFromAccessToken(accessToken);

        log.info("SSO redirect for user: {}, groups: {}", email, groups);

        boolean syncSucceeded = masSyncService.syncUser(accessToken, email, groups);

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

    private String extractEmail(OidcUser oidcUser) {
        String email = oidcUser.getEmail();
        if (email == null || email.isBlank()) {
            email = oidcUser.getPreferredUsername();
        }
        if (email == null || email.isBlank()) {
            email = oidcUser.getSubject();
        }
        return email;
    }

    private List<String> extractGroupsFromAccessToken(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                return List.of();
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode claims = objectMapper.readTree(payload);
            JsonNode groupsNode = claims.get("groups");
            if (groupsNode != null && groupsNode.isArray()) {
                return objectMapper.convertValue(groupsNode, List.class);
            }
        } catch (Exception e) {
            log.warn("Failed to extract groups from access token: {}", e.getMessage());
        }
        return List.of();
    }
}
