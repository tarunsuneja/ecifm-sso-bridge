package com.ecifm.saml.bridge.controller;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

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

import com.ecifm.saml.bridge.service.MasSyncService;
import com.ecifm.saml.bridge.service.TririgaWsClient;
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
    private final TririgaWsClient tririgaWsClient;
    private final ObjectMapper objectMapper;

    @Value("${tririga.username}")
    private String tririgaUsername;

    @Value("${tririga.password}")
    private String tririgaPassword;

    public AcsHandlerController(MasSyncService masSyncService, TririgaWsClient tririgaWsClient, ObjectMapper objectMapper) {
        this.masSyncService = masSyncService;
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

    @GetMapping("/local/test-raw")
    public ResponseEntity<String> localTestRaw() {
        try {
            String endpoint = masBaseUrl.trim() + "/ws/TririgaWS";
            String soapRequest = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"\n" +
"    xmlns:h=\"http://soap-authentication.org/basic/2001/10/\">\n" +
"  <soap:Header>\n" +
"    <h:BasicChallenge soap:mustUnderstand=\"1\">\n" +
"      <UserName>" + tririgaUsername.trim() + "</UserName>\n" +
"      <Password>" + tririgaPassword + "</Password>\n" +
"    </h:BasicChallenge>\n" +
"  </soap:Header>\n" +
"  <soap:Body>\n" +
"    <getApplicationInfo xmlns=\"http://ws.tririga.com\"/>\n" +
"  </soap:Body>\n" +
"</soap:Envelope>";

            HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
            conn.setRequestProperty("SOAPAction", "");
            conn.setRequestProperty("Username", tririgaUsername.trim());
            conn.setRequestProperty("Password", tririgaPassword);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(soapRequest.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            StringBuilder responseBody = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    responseBody.append(line).append("\n");
                }
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("HTTP " + responseCode + "\n" + responseBody);
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Error: " + e.getMessage() + "\n" + sw);
        }
    }

    @GetMapping("/redirect")
    public ResponseEntity<String> ssoRedirect(
            @AuthenticationPrincipal OidcUser oidcUser,
            @RegisteredOAuth2AuthorizedClient("entra-id") OAuth2AuthorizedClient authorizedClient) {

        String tririgaUrl = masBaseUrl + masContext;

        if (oidcUser == null || authorizedClient == null) {
            return buildPage("Not Logged In", "",
                    List.of(), null, tririgaUrl,
                    "warning", "You are not authenticated. Please log in first.");
        }

        String email = extractEmail(oidcUser);
        String accessToken = authorizedClient.getAccessToken().getTokenValue();
        List<String> groups = extractGroupsFromAccessToken(accessToken);

        if (groups.isEmpty()) {
            log.info("No groups in access token, will use Graph API fallback in sync");
        }

        log.info("SSO redirect for user: {}, groups: {}", email, groups);

        boolean syncSucceeded = masSyncService.syncUser(accessToken, email, groups);

        String status;
        String message;
        if (syncSucceeded) {
            status = "success";
            message = "Groups synced successfully to TRIRIGA.";
        } else {
            status = "error";
            message = "SSOConnect API returned an error. TRIRIGA SSO app may not be configured yet.";
        }

        return buildPage(email, accessToken, groups, syncSucceeded, tririgaUrl, status, message);
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
