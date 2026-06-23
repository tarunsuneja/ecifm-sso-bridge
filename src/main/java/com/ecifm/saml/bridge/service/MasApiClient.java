package com.ecifm.saml.bridge.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;

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

    public MasApiClient(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
    }

    public boolean syncUserGroups(String bearerToken, String userName, String groupName) {
        log.info("Syncing user groups to MAS: userName={}, groupName={}", userName, groupName);

        try {
            String url = buildUrl(userName, groupName);
            log.info("MAS SSOConnect URL: {}", url);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(bearerToken);

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class);

            log.info("MAS response status: {}", response.getStatusCode());

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("MAS SSOConnect failed with status: {}", response.getStatusCode());
                return false;
            }

            log.info("MAS response body: {}", response.getBody());
            log.info("User groups synced successfully to MAS");
            return true;

        } catch (Exception e) {
            log.error("Failed to sync user groups to MAS: {}", e.getMessage(), e);
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
