package com.ecifm.saml.bridge.service;

import java.util.ArrayList;
import java.util.List;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class EntraIdGroupResolver {

    private static final Logger log = LoggerFactory.getLogger(EntraIdGroupResolver.class);

    private static final String GRAPH_API_URL = "https://graph.microsoft.com/v1.0/me/memberOf?$select=displayName";

    @Value("${entra-id.graph.api-version:v1.0}")
    private String apiVersion;

    @Value("${entra-id.graph.enabled:true}")
    private boolean enabled;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public EntraIdGroupResolver(RestTemplateBuilder restTemplateBuilder, ObjectMapper objectMapper) {
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
    }

    public List<String> resolveGroups(String bearerToken) {
        List<String> groups = new ArrayList<>();

        if (!enabled) {
            log.warn("Microsoft Graph API group resolution is disabled.");
            return groups;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(bearerToken);

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    GRAPH_API_URL,
                    HttpMethod.GET,
                    entity,
                    String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Graph API call failed with status: {}", response.getStatusCode());
                return groups;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode value = root.get("value");

            if (value != null && value.isArray()) {
                for (JsonNode node : value) {
                    JsonNode displayName = node.get("displayName");
                    if (displayName != null && !displayName.asText().isEmpty()) {
                        groups.add(displayName.asText());
                    }
                }
            }

            log.info("Resolved {} groups from Microsoft Graph API for user", groups.size());
        } catch (Exception e) {
            log.error("Failed to resolve groups from Microsoft Graph API: {}", e.getMessage(), e);
        }

        return groups;
    }
}
