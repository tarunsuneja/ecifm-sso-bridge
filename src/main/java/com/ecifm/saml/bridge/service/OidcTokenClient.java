package com.ecifm.saml.bridge.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Component
public class OidcTokenClient {

    private static final Logger log = LoggerFactory.getLogger(OidcTokenClient.class);

    @Value("${mas.oidc.token-uri:}")
    private String tokenUri;

    @Value("${bridge.issuer-url}")
    private String bridgeIssuerUrl;

    @Value("${mas.oidc.client-id:mas-facilities}")
    private String clientId;

    @Value("${mas.oidc.client-secret:}")
    private String clientSecret;

    private final RestTemplate restTemplate;

    public OidcTokenClient(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
    }

    private String resolveTokenUri() {
        if (tokenUri != null && !tokenUri.isBlank()) {
            return tokenUri;
        }
        String derived = bridgeIssuerUrl + "/oauth2/token";
        log.info("Deriving token URI from bridge issuer: {}", derived);
        return derived;
    }

    @SuppressWarnings("unchecked")
    public String getClientCredentialsToken() {
        String uri = resolveTokenUri();
        if (clientSecret == null || clientSecret.isBlank()) {
            log.warn("mas.oidc.client-secret is not configured");
            return null;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "client_credentials");
            body.add("client_id", clientId);
            body.add("client_secret", clientSecret);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(uri, request, Map.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("Token request failed: {}", response.getStatusCode());
                return null;
            }

            Map<String, Object> tokenResponse = response.getBody();
            if (tokenResponse == null) {
                log.warn("Empty token response");
                return null;
            }

            String accessToken = (String) tokenResponse.get("access_token");
            if (accessToken == null) {
                log.warn("No access_token in response: {}", tokenResponse);
                return null;
            }

            log.info("Obtained client credentials token ({} chars)", accessToken.length());
            return accessToken;

        } catch (Exception e) {
            log.warn("Failed to get client credentials token: {}", e.getMessage());
            return null;
        }
    }
}
