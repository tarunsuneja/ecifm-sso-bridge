package com.ecifm.saml.bridge.controller;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/local")
public class LocalMockController {

    private static final Logger log = LoggerFactory.getLogger(LocalMockController.class);

    @Value("${mas.base-url}")
    private String masBaseUrl;

    private final OAuth2AuthorizedClientService authorizedClientService;

    public LocalMockController(OAuth2AuthorizedClientService authorizedClientService) {
        this.authorizedClientService = authorizedClientService;
    }

    @GetMapping("/mock-sso")
    @ResponseBody
    public ResponseEntity<String> mockSsoConnect(
            @RequestParam(value = "userName", required = false, defaultValue = "local.user@ecifm.com") String userName,
            @RequestParam(value = "adGroupName", required = false, defaultValue = "LOCAL_TEST_GROUP") String adGroupName,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        log.info("Local mock SSOConnect called for userName: {} adGroupName: {}", userName, adGroupName);

        if (authHeader != null) {
            log.info("Authorization header present: {}", authHeader.substring(0, Math.min(20, authHeader.length())) + "...");
        }

        return ResponseEntity.ok("Local mock SSOConnect executed successfully");
    }

    @GetMapping("/test-oslc")
    @ResponseBody
    public ResponseEntity<String> testOslc(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "jsessionid", required = false) String jsessionid,
            @RequestParam(value = "cookie", required = false) String rawCookie,
            Authentication authentication) {
        String oslcUrl = "https://main.facilities.inst1.apps.npos2.ecifmdev.net/oslc/spq/triCurrentUserQC?oslc.select=*";
        log.info("Testing OSLC call to: {}", oslcUrl);

        StringBuilder result = new StringBuilder();

        // Test 1: No auth
        result.append("=== Test 1: No auth ===\n");
        testCall(oslcUrl, null, null, result);

        // Test 2: With Bearer token from Authorization header
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            result.append("\n=== Test 2: Bearer token ===\n");
            testCall(oslcUrl, authHeader, null, result);
        } else {
            result.append("\n=== Test 2: Bearer token — SKIPPED (pass Authorization: Bearer <token>) ===\n");
        }

        // Test 3: With JSESSIONID as cookie
        if (jsessionid != null && !jsessionid.isEmpty()) {
            result.append("\n=== Test 3: JSESSIONID cookie ===\n");
            testCall(oslcUrl, null, "JSESSIONID=" + jsessionid, result);
        } else {
            result.append("\n=== Test 3: JSESSIONID — SKIPPED (pass ?jsessionid=<value>) ===\n");
        }

        // Test 4: With raw Cookie header value
        if (rawCookie != null && !rawCookie.isEmpty()) {
            result.append("\n=== Test 4: Raw Cookie ===\n");
            testCall(oslcUrl, null, rawCookie, result);
        } else {
            result.append("\n=== Test 4: Raw Cookie — SKIPPED (pass ?cookie=<raw-cookie-value>) ===\n");
        }

        // Test 5: With Entra ID access token from current session
        String entraToken = getEntraAccessToken(authentication);
        if (entraToken != null) {
            result.append("\n=== Test 5: Entra ID Bearer token (OSLC) ===\n");
            testCall(oslcUrl, "Bearer " + entraToken, null, result);

            String ssoUrl = "https://main.facilities.inst1.apps.npos2.ecifmdev.net/html/en/default/rest/SSOConnect?userName=tarun.suneja@ecifm.com&adGroupName=ECIFM_TEST_GROUP";
            result.append("\n=== Test 6: Entra ID Bearer token (SSOConnect REST) ===\n");
            testCall(ssoUrl, "Bearer " + entraToken, null, result);
        } else {
            result.append("\n=== Test 5: Entra ID Bearer token — SKIPPED (no active OAuth2 session) ===\n");
        }

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(result.toString());
    }

    private void testCall(String url, String authHeader, String cookie, StringBuilder result) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            if (authHeader != null) {
                conn.setRequestProperty("Authorization", authHeader);
            }
            if (cookie != null) {
                conn.setRequestProperty("Cookie", cookie);
            }
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            result.append("HTTP ").append(code).append("\n");
            String setCookie = conn.getHeaderField("Set-Cookie");
            if (setCookie != null) result.append("Cookie: ").append(setCookie).append("\n");
            StringBuilder body = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) body.append(line).append("\n");
            }
            result.append("Body:\n").append(body);
        } catch (Exception e) {
            result.append("Error: ").append(e.getMessage()).append("\n");
        }
    }

    private String getEntraAccessToken(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken oauthToken
                && "entra-id".equals(oauthToken.getAuthorizedClientRegistrationId())) {
            OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                    "entra-id", oauthToken.getName());
            if (client != null && client.getAccessToken() != null) {
                return client.getAccessToken().getTokenValue();
            }
        }
        if (authentication != null) {
            OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                    "entra-id", authentication.getName());
            if (client != null && client.getAccessToken() != null) {
                return client.getAccessToken().getTokenValue();
            }
        }
        return null;
    }

    @GetMapping("/mock-redirect")
    @ResponseBody
    public ResponseEntity<String> mockRedirect(
            @RequestParam(value = "userName", required = false, defaultValue = "local.user@ecifm.com") String userName,
            @RequestParam(value = "groups", required = false, defaultValue = "LOCAL_TEST_GROUP") String groups) {

        log.info("Local mock redirect for user: {} groups: {}", userName, groups);
        return ResponseEntity.ok("Mock redirect to: " + masBaseUrl + "/tririga");
    }
}
