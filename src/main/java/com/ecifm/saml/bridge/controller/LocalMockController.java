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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.ecifm.saml.bridge.service.TririgaWsClient;

@RestController
@RequestMapping("/local")
public class LocalMockController {

    private static final Logger log = LoggerFactory.getLogger(LocalMockController.class);

    @Value("${mas.base-url}")
    private String masBaseUrl;

    @Value("${tririga.username}")
    private String tririgaUsername;

    @Value("${tririga.password}")
    private String tririgaPassword;

    private final TririgaWsClient tririgaWsClient;

    public LocalMockController(TririgaWsClient tririgaWsClient) {
        this.tririgaWsClient = tririgaWsClient;
    }

    @GetMapping("/mock-sso")
    @ResponseBody
    public ResponseEntity<String> mockSsoConnect(
            @RequestParam(value = "userName", required = false, defaultValue = "local.user@ecifm.com") String userName,
            @RequestParam(value = "adGroupName", required = false, defaultValue = "LOCAL_TEST_GROUP") String adGroupName,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        log.info("Local mock SSOConnect called for userName: {} adGroupName: {}", userName, adGroupName);

        if (authHeader != null) {
            log.info("Authorization header present: {}...", authHeader.substring(0, Math.min(20, authHeader.length())));
        }

        return ResponseEntity.ok("Local mock SSOConnect executed successfully");
    }

    @GetMapping("/test-oslc")
    @ResponseBody
    public ResponseEntity<String> testOslc() {
        String oslcUrl = "https://main.facilities.inst1.apps.npos2.ecifmdev.net/oslc/spq/triCurrentUserQC?oslc.select=*";
        log.info("Testing OSLC call to: {}", oslcUrl);

        StringBuilder result = new StringBuilder();

        String basicValue = tririgaUsername + ":" + tririgaPassword;
        String basicHeader = "Basic " + java.util.Base64.getEncoder().encodeToString(basicValue.getBytes());
        result.append("--- Credentials: user='").append(tririgaUsername).append("' pass_len=").append(tririgaPassword.length()).append(" basic64_len=").append(basicHeader.length()).append("\n");

        result.append("\n=== Auth via SOAP (HTTP Basic) ===\n");
        result.append(tririgaWsClient.getApplicationInfo()).append("\n");

        result.append("\n=== Authenticated session from SOAP ===\n");
        String sessionId = tririgaWsClient.getAuthenticatedSessionId();
        result.append("JSESSIONID: ").append(sessionId != null ? sessionId : "null").append("\n");

        result.append("\n=== OSLC with authenticated session ===\n");
        if (sessionId != null) {
            testCall(oslcUrl, null, "JSESSIONID=" + sessionId, result);
        } else {
            result.append("No session available\n");
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

    @GetMapping("/mock-redirect")
    @ResponseBody
    public ResponseEntity<String> mockRedirect(
            @RequestParam(value = "userName", required = false, defaultValue = "local.user@ecifm.com") String userName,
            @RequestParam(value = "groups", required = false, defaultValue = "LOCAL_TEST_GROUP") String groups) {

        log.info("Local mock redirect for user: {} groups: {}", userName, groups);
        return ResponseEntity.ok("Mock redirect to: " + masBaseUrl + "/tririga");
    }
}
