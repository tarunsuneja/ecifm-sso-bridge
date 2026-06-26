package com.ecifm.saml.bridge.service;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class TririgaWsClient {

    private static final Logger log = LoggerFactory.getLogger(TririgaWsClient.class);

    private static final String SOAP12_ENVELOPE =
            "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:ws=\"http://ws.tririga.com\">"
          + "  <soap:Header/>"
          + "  <soap:Body>"
          + "    <ws:getApplicationInfo/>"
          + "  </soap:Body>"
          + "</soap:Envelope>";

    private static final String SOAP11_ENVELOPE =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ws=\"http://ws.tririga.com\">"
          + "  <soap:Header/>"
          + "  <soap:Body>"
          + "    <ws:getApplicationInfo/>"
          + "  </soap:Body>"
          + "</soap:Envelope>";

    @Value("${mas.base-url}")
    private String masBaseUrl;

    @Value("${mas.context}")
    private String masContext;

    @Value("${mas-core.api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate;

    public TririgaWsClient(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
        disableSslVerification();
    }

    public String getApplicationInfo(String bearerToken) {
        String soapUrl = masBaseUrl + masContext + "/ws/TririgaWS";
        log.info("TririgaWS SOAP URL: {}", soapUrl);

        StringBuilder report = new StringBuilder();

        // Attempt 1: No auth + SOAP 1.1
        report.append("=== Attempt 1: No Auth + SOAP 1.1 ===\n");
        report.append(tryCall(soapUrl, null, null, SOAP11_ENVELOPE, "text/xml", "urn:getApplicationInfo"));
        report.append("\n\n");

        // Attempt 2: Bearer token + SOAP 1.2 (application/soap+xml)
        report.append("=== Attempt 2: Bearer + SOAP 1.2 ===\n");
        report.append(tryCall(soapUrl, bearerToken, null, null, "application/soap+xml", null));
        report.append("\n\n");

        // Attempt 3: Bearer token + SOAP 1.1 (text/xml + SOAPAction)
        report.append("=== Attempt 3: Bearer + SOAP 1.1 ===\n");
        report.append(tryCall(soapUrl, bearerToken, null, SOAP11_ENVELOPE, "text/xml", "urn:getApplicationInfo"));
        report.append("\n\n");

        // Attempt 4: API key + SOAP 1.1
        if (apiKey != null && !apiKey.isEmpty()) {
            report.append("=== Attempt 4: API Key + SOAP 1.1 ===\n");
            report.append(tryCall(soapUrl, null, apiKey, SOAP11_ENVELOPE, "text/xml", "urn:getApplicationInfo"));
            report.append("\n\n");
        }

        return report.toString();
    }

    private String tryCall(String url, String bearerToken, String apiKeyValue,
                           String envelope, String contentType, String soapAction) {
        if (envelope == null) {
            envelope = SOAP12_ENVELOPE;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            if (bearerToken != null) {
                headers.setBearerAuth(bearerToken);
            }
            if (apiKeyValue != null && !apiKeyValue.isEmpty()) {
                headers.set("x-api-key", apiKeyValue);
            }
            if (soapAction != null) {
                headers.set("SOAPAction", soapAction);
            }
            HttpEntity<String> request = new HttpEntity<>(envelope, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            log.info("SOAP response: {}", response.getStatusCode());
            return "Status: " + response.getStatusCode() + "\nBody: " + response.getBody();
        } catch (Exception e) {
            log.warn("SOAP failed: {}", e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }

    private static void disableSslVerification() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            log.warn("SSL verification disabled for TririgaWS SOAP calls");
        } catch (Exception e) {
            throw new RuntimeException("Failed to disable SSL verification", e);
        }
    }
}
