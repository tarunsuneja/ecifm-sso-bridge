package com.ecifm.saml.bridge.service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MasSyncService {

    private static final Logger log = LoggerFactory.getLogger(MasSyncService.class);

    private final MasApiClient masApiClient;
    private final EntraIdGroupResolver entraIdGroupResolver;

    public MasSyncService(MasApiClient masApiClient, EntraIdGroupResolver entraIdGroupResolver) {
        this.masApiClient = masApiClient;
        this.entraIdGroupResolver = entraIdGroupResolver;
    }

    public boolean syncUser(String bearerToken, String email, List<String> jwtGroups) {
        log.info("Syncing user: {}", email);

        List<String> groups = jwtGroups;

        if (groups == null || groups.isEmpty()) {
            log.info("No groups in JWT, falling back to Microsoft Graph API");
            groups = entraIdGroupResolver.resolveGroups(bearerToken);
        }

        String groupName = groups.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.joining(","));

        log.info("Resolved groups for {}: {}", email, groupName);

        return masApiClient.syncUserGroups(bearerToken, email, groupName);
    }
}
