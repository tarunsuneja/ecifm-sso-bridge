package com.ecifm.saml.bridge.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;

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
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class MasApiClient {

    private static final Logger log = LoggerFactory.getLogger(MasApiClient.class);

    @Value("${mas.base-url}")
    private String masBaseUrl;

    @Value("${mas.context}")
    private String masContext;

    @Value("${mas.rest-api}")
    private String masRestApi;

    private final RestTemplate restTemplate;
    private final TririgaWsClient tririgaWsClient;

    public MasApiClient(RestTemplateBuilder restTemplateBuilder, TririgaWsClient tririgaWsClient) {
        this.restTemplate = restTemplateBuilder.build();
        this.tririgaWsClient = tririgaWsClient;
        disableSslVerification();
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
            log.warn("SSL verification disabled for outbound HTTPS connections");
        } catch (Exception e) {
            throw new RuntimeException("Failed to disable SSL verification", e);
        }
    }

    public boolean syncUserGroups(String userName, String groupName) {
        log.info("Syncing user groups: userName={}, groupName={}", userName, groupName);

        try {
            String jsessionId = tririgaWsClient.getAuthenticatedSessionId();
            if (jsessionId == null) {
                log.error("Failed to obtain authenticated session from TRIRIGA");
                return false;
            }

            String url = buildUrl(userName, groupName);
            log.info("SSOConnect URL: {}", url);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Cookie", "JSESSIONID=" + jsessionId);

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class);

            log.info("SSOConnect response status: {}", response.getStatusCode());

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("SSOConnect failed with status: {}", response.getStatusCode());
                return false;
            }

            log.info("SSOConnect response body: {}", response.getBody());
            log.info("User groups synced successfully");
            return true;

        } catch (Exception e) {
            log.error("Failed to sync user groups: {}", e.getMessage(), e);
            return false;
        }
    }

    private String buildUrl(String userName, String groupName) {
        String urlTemplate = masBaseUrl + masContext + masRestApi;
        return MessageFormat.format(
                urlTemplate,
                encodeParam(userName),
                encodeParam(groupName));
    }

    private String encodeParam(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
