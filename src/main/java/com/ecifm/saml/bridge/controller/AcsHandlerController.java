package com.ecifm.saml.bridge.controller;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.xml.bind.JAXBElement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.ecifm.saml.bridge.service.MasGroupSyncService;
import com.ecifm.saml.bridge.service.MasSyncService;
import com.ecifm.saml.bridge.service.TririgaWsClient;
import com.ecifm.saml.bridge.tririga.generated.dto.QueryMultiBoResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Controller
public class AcsHandlerController {

    private static final Logger log = LoggerFactory.getLogger(AcsHandlerController.class);

    @Value("${mas.base-url}")
    private String masBaseUrl;

    @Value("${mas.context}")
    private String masContext;

    @Value("${mas.redirect-url}")
    private String masRedirectUrl;

    private final MasSyncService masSyncService;
    private final MasGroupSyncService masGroupSyncService;
    private final TririgaWsClient tririgaWsClient;
    private final ObjectMapper objectMapper;

    @Value("${tririga.username}")
    private String tririgaUsername;

    @Value("${tririga.password}")
    private String tririgaPassword;

    @Value("${tririga.named-query.project-name:}")
    private String queryProjectName;

    @Value("${tririga.named-query.module-name:}")
    private String queryModuleName;

    @Value("${tririga.named-query.object-type-name:}")
    private String queryObjectTypeName;

    @Value("${tririga.named-query.query-name:}")
    private String queryName;

    @Value("${tririga.named-query.filter-field:}")
    private String queryFilterField;

    @Value("${tririga.named-query.group-column-name:Group Name}")
    private String queryGroupColumnName;

    @Value("${tririga.named-query.filter-operator:10}")
    private int queryFilterOperator;

    @Value("${tririga.named-query.filter-data-type:320}")
    private int queryFilterDataType;

    @Value("${tririga.people.section-name:RecordInformation}")
    private String peopleSectionName;

    @Value("${tririga.people.group-field-name:}")
    private String peopleGroupFieldName;

    @Value("${tririga.people.group-field-action:cstValidateADGroup}")
    private String peopleGroupFieldAction;

    public AcsHandlerController(MasSyncService masSyncService, MasGroupSyncService masGroupSyncService, TririgaWsClient tririgaWsClient, ObjectMapper objectMapper) {
        this.masSyncService = masSyncService;
        this.masGroupSyncService = masGroupSyncService;
        this.tririgaWsClient = tririgaWsClient;
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

    @GetMapping("/test-soap")
    public ResponseEntity<String> testSoap(
            @AuthenticationPrincipal OidcUser oidcUser,
            @RegisteredOAuth2AuthorizedClient("entra-id") OAuth2AuthorizedClient authorizedClient) {
        if (oidcUser == null || authorizedClient == null) {
            return ResponseEntity.ok("Not authenticated. Visit /redirect first.");
        }
        String email = extractEmail(oidcUser);
        String result = tririgaWsClient.getApplicationInfo();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("User: " + email + "\n\nSOAP Response:\n" + result);
    }

    @GetMapping("/local/test-soap")
    public ResponseEntity<String> localTestSoap() {
        String result = tririgaWsClient.getApplicationInfo();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("SOAP Response:\n" + result);
    }

    @GetMapping("/local/test-internal")
    public ResponseEntity<String> localTestInternal() {
        try {
            String endpoint = "https://inst1-main-appserver-0.mas-inst1-facilities.svc:443/ws/TririgaWS";
            String soapBody = "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">\n" +
"  <SOAP-ENV:Body>\n" +
"    <getApplicationInfo xmlns=\"http://ws.tririga.com\"/>\n" +
"  </SOAP-ENV:Body>\n" +
"</SOAP-ENV:Envelope>";
            StringBuilder result = new StringBuilder();

            // Test 1: No auth
            result.append("=== Test 1: No auth ===\n");
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
                conn.setRequestProperty("SOAPAction", "");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(soapBody.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                int code = conn.getResponseCode();
                result.append("HTTP ").append(code).append("\n");
                String cookie = conn.getHeaderField("Set-Cookie");
                if (cookie != null) result.append("Cookie: ").append(cookie).append("\n");
                StringBuilder body = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                                StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) body.append(line).append("\n");
                }
                result.append(body).append("\n");
            } catch (Exception e) {
                result.append("Error: ").append(e.getMessage()).append("\n");
            }

            // Test 2: Custom Username/Password headers
            result.append("=== Test 2: Custom Username/Password headers ===\n");
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
                conn.setRequestProperty("SOAPAction", "");
                conn.setRequestProperty("Username", tririgaUsername.trim());
                conn.setRequestProperty("Password", tririgaPassword);
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(soapBody.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                int code = conn.getResponseCode();
                result.append("HTTP ").append(code).append("\n");
                String cookie = conn.getHeaderField("Set-Cookie");
                if (cookie != null) result.append("Cookie: ").append(cookie).append("\n");
                StringBuilder body = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                                StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) body.append(line).append("\n");
                }
                result.append(body).append("\n");
            } catch (Exception e) {
                result.append("Error: ").append(e.getMessage()).append("\n");
            }

            // Test 3: HTTP Basic Auth
            result.append("=== Test 3: HTTP Basic Auth ===\n");
            try {
                String auth = tririgaUsername.trim() + ":" + tririgaPassword;
                String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
                HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
                conn.setRequestProperty("SOAPAction", "");
                conn.setRequestProperty("Authorization", "Basic " + encoded);
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setUseCaches(false);
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(soapBody.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                int code = conn.getResponseCode();
                result.append("HTTP ").append(code).append("\n");
                String cookie = conn.getHeaderField("Set-Cookie");
                if (cookie != null) result.append("Cookie: ").append(cookie).append("\n");
                StringBuilder body = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                                StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) body.append(line).append("\n");
                }
                result.append(body).append("\n");
            } catch (Exception e) {
                result.append("Error: ").append(e.getMessage()).append("\n");
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(result.toString());
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Error: " + e.getMessage() + "\n" + sw);
        }
    }

    @GetMapping("/local/test-raw")
    public ResponseEntity<String> localTestRaw() {
        try {
            String endpoint = masBaseUrl.trim() + "/ws/TririgaWS";
            String soapBody = "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">\n" +
"  <SOAP-ENV:Body>\n" +
"    <getApplicationInfo xmlns=\"http://ws.tririga.com\"/>\n" +
"  </SOAP-ENV:Body>\n" +
"</SOAP-ENV:Envelope>";

            // Step 1: get session cookie
            HttpURLConnection conn1 = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            conn1.setRequestMethod("POST");
            conn1.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
            conn1.setRequestProperty("SOAPAction", "");
            conn1.setDoOutput(true);
            conn1.setDoInput(true);
            conn1.setUseCaches(false);
            conn1.setInstanceFollowRedirects(false);
            conn1.setConnectTimeout(30000);
            conn1.setReadTimeout(120000);
            try (OutputStream os = conn1.getOutputStream()) {
                os.write(soapBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            String cookies = conn1.getHeaderField("Set-Cookie");
            int code1 = conn1.getResponseCode();
            conn1.disconnect();

            // Step 2: send with HTTP Basic Auth + cookie
            String auth = tririgaUsername.trim() + ":" + tririgaPassword;
            String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            HttpURLConnection conn2 = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            conn2.setRequestMethod("POST");
            conn2.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
            conn2.setRequestProperty("SOAPAction", "");
            conn2.setRequestProperty("Authorization", "Basic " + encoded);
            if (cookies != null) {
                conn2.setRequestProperty("Cookie", cookies);
            }
            conn2.setDoOutput(true);
            conn2.setDoInput(true);
            conn2.setUseCaches(false);
            conn2.setInstanceFollowRedirects(false);
            conn2.setConnectTimeout(30000);
            conn2.setReadTimeout(120000);
            try (OutputStream os = conn2.getOutputStream()) {
                os.write(soapBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn2.getResponseCode();
            Map<String, List<String>> respHeaders = conn2.getHeaderFields();
            StringBuilder responseBody = new StringBuilder();
            responseBody.append("Step1: HTTP ").append(code1).append(", Cookie: ").append(cookies).append("\n\n");
            responseBody.append("Step2: HTTP ").append(responseCode).append("\n");
            responseBody.append("Response Headers:\n");
            for (Map.Entry<String, List<String>> h : respHeaders.entrySet()) {
                if (h.getKey() != null) {
                    responseBody.append("  ").append(h.getKey()).append(": ").append(String.join(", ", h.getValue())).append("\n");
                }
            }
            responseBody.append("\nBody:\n");
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(responseCode >= 400 ? conn2.getErrorStream() : conn2.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    responseBody.append(line).append("\n");
                }
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(responseBody.toString());
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Error: " + e.getMessage() + "\n" + sw);
        }
    }

    @GetMapping("/local/test-jwt")
    public ResponseEntity<String> localTestJwt(
            @AuthenticationPrincipal OidcUser oidcUser,
            @RegisteredOAuth2AuthorizedClient("entra-id") OAuth2AuthorizedClient authorizedClient) {
        if (oidcUser == null || authorizedClient == null) {
            return ResponseEntity.ok("Not authenticated. Visit /redirect first.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== OidcUser Claims ===\n");
        oidcUser.getClaims().forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
        sb.append("\n=== Access Token Claims ===\n");
        String accessToken = authorizedClient.getAccessToken().getTokenValue();
        try {
            String[] parts = accessToken.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode claims = objectMapper.readTree(payload);
            sb.append(claims.toPrettyString()).append("\n");
        } catch (Exception e) {
            sb.append("Failed to decode: ").append(e.getMessage()).append("\n");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(sb.toString());
    }

    @GetMapping("/local/test-tririga-query")
    public ResponseEntity<String> localTestTririgaQuery(@AuthenticationPrincipal OidcUser oidcUser,
            @RequestParam(defaultValue = "") String email) {
        if ((email == null || email.isEmpty()) && oidcUser != null) {
            email = extractEmail(oidcUser);
        }
        if (email == null || email.isEmpty()) {
            return ResponseEntity.badRequest().body("Provide ?email= param or authenticate first");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== TRIRIGA Named Query Test ===\n");
        sb.append("Email: ").append(email).append("\n");
        sb.append("Query Config:\n");
        sb.append("  project: ").append(nvl(queryProjectName)).append("\n");
        sb.append("  module: ").append(nvl(queryModuleName)).append("\n");
        sb.append("  objectType: ").append(nvl(queryObjectTypeName)).append("\n");
        sb.append("  query: ").append(nvl(queryName)).append("\n");
        sb.append("  filterField: ").append(nvl(queryFilterField)).append("\n");
        sb.append("  groupColumn: ").append(nvl(queryGroupColumnName)).append("\n\n");

        // Step 1: Run the named query (multi-bo)
        sb.append("--- Step 1: runNamedQueryMultiBo ---\n");
        QueryMultiBoResult result = tririgaWsClient.runNamedQueryMultiBo(
            queryProjectName, queryModuleName, queryObjectTypeName, queryName,
            queryFilterField, email, queryFilterOperator, queryFilterDataType, 0, 1000);
        if (result == null) {
            sb.append("FAILED: runNamedQueryMultiBo returned null\n");
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(sb.toString());
        }
        Integer total = result.getTotalResults();
        sb.append("Total results: ").append(total != null ? total : 0).append("\n\n");

        // Step 2: Dump each row
        sb.append("--- Step 2: Row details ---\n");
        var helpers = result.getQueryMultiBoResponseHelpers();
        int rowNum = 0;
        if (helpers != null && helpers.getValue() != null) {
            for (var helper : helpers.getValue().getQueryMultiBoResponseHelper()) {
                rowNum++;
                        sb.append("Row ").append(rowNum).append(": recordId=")
                            .append(val(helper.getRecordId())).append("\n");
                        var columns = helper.getQueryMultiBoResponseColumns();
                        if (columns != null && columns.getValue() != null) {
                            for (var col : columns.getValue().getQueryMultiBoResponseColumn()) {
                                sb.append("  ").append(val(col.getName())).append(" = ")
                                    .append(val(col.getValue())).append("\n");
                            }
                        }
            }
        }
        sb.append("\n");

        // Step 3: Test extractFirstRecordIdFromMultiBo
        sb.append("--- Step 3: extractFirstRecordIdFromMultiBo ---\n");
        String firstId = tririgaWsClient.extractFirstRecordIdFromMultiBo(result);
        sb.append("First recordId: ").append(firstId != null ? firstId : "null").append("\n\n");

        // Step 4: Test extractColumnValuesFromMultiBo (groups)
        sb.append("--- Step 4: extractColumnValuesFromMultiBo (groupColumn='")
            .append(nvl(queryGroupColumnName)).append("') ---\n");
        List<String> groupValues = tririgaWsClient.extractColumnValuesFromMultiBo(result, queryGroupColumnName);
        sb.append("Groups found: ").append(groupValues.size()).append("\n");
        for (String g : groupValues) {
            sb.append("  ").append(g).append("\n");
        }
        sb.append("\n");

        // Step 5: Test getRecordDataHeader
        sb.append("--- Step 5: getRecordDataHeader ---\n");
        if (firstId != null) {
            try {
                long id = Long.parseLong(firstId);
                var rec = tririgaWsClient.getRecordDataHeader(id);
                if (rec != null) {
                    sb.append("  id: ").append(rec.getId()).append("\n");
                    sb.append("  name: ").append(val(rec.getName())).append("\n");
                    sb.append("  moduleId: ").append(rec.getModuleId()).append("\n");
                    sb.append("  objectTypeName: ").append(val(rec.getObjectTypeName())).append("\n");
                    if (rec.getGuiId() != null) {
                        sb.append("  guiId: ").append(rec.getGuiId()).append("\n");
                    }
                } else {
                    sb.append("  FAILED: null response\n");
                }
            } catch (Exception e) {
                sb.append("  FAILED: ").append(e.getMessage()).append("\n");
            }
        } else {
            sb.append("  Skipped (no recordId)\n");
        }
        sb.append("\n");

        // Step 6: Build what would be sent to saveRecord
        sb.append("--- Step 6: Preview saveRecord payload ---\n");
        sb.append("  sectionName: ").append(nvl(peopleSectionName)).append("\n");
        sb.append("  groupFieldName: ").append(nvl(peopleGroupFieldName)).append("\n");
        sb.append("  groupFieldAction: ").append(nvl(peopleGroupFieldAction)).append("\n");
        sb.append("  groups: ").append(String.join(", ", groupValues)).append("\n");

        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(sb.toString());
    }

    @GetMapping("/redirect")
    public ResponseEntity<String> ssoRedirect(
            @AuthenticationPrincipal OidcUser oidcUser,
            @RegisteredOAuth2AuthorizedClient("entra-id") OAuth2AuthorizedClient authorizedClient) {

        if (oidcUser == null || authorizedClient == null) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, "/oauth2/authorization/entra-id")
                    .build();
        }

        String email = extractEmail(oidcUser);
        String accessToken = authorizedClient.getAccessToken().getTokenValue();

        // Extract groups from JWT access token
        List<String> jwtGroups = extractGroupsFromAccessToken(accessToken);
        log.info("Groups from JWT for {}: {}", email, jwtGroups);

        // Query TRIRIGA groups via named query, compare, and sync if different
        MasGroupSyncService.SyncResult syncResult = masGroupSyncService.syncIfGroupsDiffer(
                accessToken, email, jwtGroups);

        if (syncResult.isSuccess()) {
            if (syncResult.wasSynced()) {
                log.info("Groups synced for {}: TRIRIGA groups updated to match Entra ID", email);
            } else {
                log.info("Groups match for {} — no sync needed", email);
            }
        } else {
            log.warn("Group sync had issues for {}, proceeding with redirect", email);
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, masRedirectUrl)
                .build();
    }

    private ResponseEntity<String> buildPage(String email, String accessToken,
                                              List<String> groups, Boolean syncSucceeded,
                                              String tririgaUrl, String status, String message) {
        String groupListHtml = groups.isEmpty()
                ? "<li><em>No groups found in token</em></li>"
                : groups.stream().map(g -> "<li>" + escapeHtml(g) + "</li>")
                        .collect(Collectors.joining("\n            "));

        String sanitizedEmail = escapeHtml(email);
        String sanitizedUrl = escapeHtml(tririgaUrl);

        String html = "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>eCIFM SSO Bridge - Login Status</title>\n" +
                "    <style>\n" +
                "        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n" +
                "               background: #f5f7fa; color: #333; min-height: 100vh; display: flex;\n" +
                "               justify-content: center; align-items: center; padding: 20px; }\n" +
                "        .card { background: white; border-radius: 12px; box-shadow: 0 4px 24px rgba(0,0,0,0.1);\n" +
                "                max-width: 640px; width: 100%; overflow: hidden; }\n" +
                "        .header { padding: 24px 28px; border-bottom: 1px solid #eee; }\n" +
                "        .header h1 { font-size: 20px; font-weight: 600; }\n" +
                "        .header .subtitle { font-size: 13px; color: #888; margin-top: 4px; }\n" +
                "        .body { padding: 24px 28px; }\n" +
                "        .badge { display: inline-block; padding: 6px 14px; border-radius: 20px;\n" +
                "                 font-size: 13px; font-weight: 500; margin-bottom: 20px; }\n" +
                "        .badge.success { background: #d4edda; color: #155724; }\n" +
                "        .badge.error { background: #f8d7da; color: #721c24; }\n" +
                "        .badge.warning { background: #fff3cd; color: #856404; }\n" +
                "        .info-row { display: flex; padding: 10px 0; border-bottom: 1px solid #f0f0f0; }\n" +
                "        .info-row .label { width: 120px; font-weight: 500; font-size: 13px; color: #666; }\n" +
                "        .info-row .value { flex: 1; font-size: 14px; word-break: break-all; }\n" +
                "        .groups { margin-top: 16px; }\n" +
                "        .groups h3 { font-size: 14px; font-weight: 600; margin-bottom: 8px; }\n" +
                "        .groups ul { list-style: none; padding: 0; display: flex; flex-wrap: wrap; gap: 6px; }\n" +
                "        .groups ul li { background: #e9ecef; padding: 4px 12px; border-radius: 14px;\n" +
                "                        font-size: 12px; color: #495057; }\n" +
                "        .actions { margin-top: 24px; display: flex; gap: 12px; flex-wrap: wrap; }\n" +
                "        .btn { display: inline-block; padding: 10px 24px; border-radius: 8px;\n" +
                "               text-decoration: none; font-size: 14px; font-weight: 500;\n" +
                "               transition: opacity 0.2s; }\n" +
                "        .btn:hover { opacity: 0.85; }\n" +
                "        .btn-primary { background: #0066cc; color: white; }\n" +
                "        .btn-secondary { background: #6c757d; color: white; }\n" +
                "        .footer { padding: 16px 28px; background: #fafbfc; border-top: 1px solid #eee;\n" +
                "                  font-size: 12px; color: #999; text-align: center; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"header\">\n" +
                "            <h1>eCIFM SSO Bridge</h1>\n" +
                "            <div class=\"subtitle\">Microsoft Entra ID Authentication Status</div>\n" +
                "        </div>\n" +
                "        <div class=\"body\">\n" +
                "            <div class=\"badge " + status + "\">" + message + "</div>\n" +
                "            <div class=\"info-row\">\n" +
                "                <div class=\"label\">Email</div>\n" +
                "                <div class=\"value\">" + sanitizedEmail + "</div>\n" +
                "            </div>\n" +
                "            <div class=\"info-row\">\n" +
                "                <div class=\"label\">Provider</div>\n" +
                "                <div class=\"value\">Microsoft Entra ID</div>\n" +
                "            </div>\n" +
                "            <div class=\"info-row\">\n" +
                "                <div class=\"label\">TRIRIGA URL</div>\n" +
                "                <div class=\"value\"><a href=\"" + sanitizedUrl + "\">" + sanitizedUrl + "</a></div>\n" +
                "            </div>\n" +
                "            <div class=\"groups\">\n" +
                "                <h3>Groups (" + groups.size() + ")</h3>\n" +
                "                <ul>\n" +
                "                    " + groupListHtml + "\n" +
                "                </ul>\n" +
                "            </div>\n" +
                "            <div class=\"actions\">\n" +
                "                <a href=\"" + sanitizedUrl + "\" class=\"btn btn-primary\">Go to TRIRIGA</a>\n" +
                "                <a href=\"/\" class=\"btn btn-secondary\">Refresh</a>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "        <div class=\"footer\">\n" +
                "            eCIFM SSO Bridge &bull; Cluster: NPOS2\n" +
                "        </div>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";

        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
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

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String val(JAXBElement<String> e) {
        return e == null ? "" : e.getValue();
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
