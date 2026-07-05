package com.ecifm.saml.bridge.service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ecifm.saml.bridge.tririga.generated.dto.QueryResult;

@Service
public class MasGroupSyncService {

    private static final Logger log = LoggerFactory.getLogger(MasGroupSyncService.class);

    private final TririgaWsClient tririgaWsClient;
    private final MasApiClient masApiClient;
    private final EntraIdGroupResolver entraIdGroupResolver;

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

    @Value("${tririga.named-query.filter-operator:0}")
    private int queryFilterOperator;

    @Value("${tririga.named-query.filter-data-type:1}")
    private int queryFilterDataType;

    public MasGroupSyncService(TririgaWsClient tririgaWsClient,
                                MasApiClient masApiClient,
                                EntraIdGroupResolver entraIdGroupResolver) {
        this.tririgaWsClient = tririgaWsClient;
        this.masApiClient = masApiClient;
        this.entraIdGroupResolver = entraIdGroupResolver;
    }

    public SyncResult syncIfGroupsDiffer(String bearerToken, String email, List<String> jwtGroups) {
        log.info("Group sync check for user: {}", email);

        List<String> resolvedGroups = jwtGroups;

        if (resolvedGroups == null || resolvedGroups.isEmpty()) {
            log.info("No groups in JWT, resolving via Microsoft Graph API");
            resolvedGroups = entraIdGroupResolver.resolveGroups(bearerToken);
        }

        if (resolvedGroups == null || resolvedGroups.isEmpty()) {
            log.warn("No groups resolved for user: {}", email);
            return new SyncResult(false, Collections.emptyList(), Collections.emptyList(), false);
        }

        Set<String> entraGroupSet = resolvedGroups.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .collect(Collectors.toSet());

        // Check if named query is configured
        if (!isNamedQueryConfigured()) {
            log.info("Named query not configured, calling SSOConnect directly");
            String groupString = String.join(",", entraGroupSet);
            boolean ok = masApiClient.syncUserGroups(email, groupString);
            return new SyncResult(ok, Collections.emptyList(), resolvedGroups, true);
        }

        // Query TRIRIGA for current groups via named query
        List<String> tririgaGroups = queryTririgaGroups(email);

        if (tririgaGroups == null) {
            log.warn("Failed to query TRIRIGA groups for {}, falling back to direct sync", email);
            String groupString = String.join(",", entraGroupSet);
            boolean ok = masApiClient.syncUserGroups(email, groupString);
            return new SyncResult(ok, Collections.emptyList(), resolvedGroups, true);
        }

        Set<String> tririgaGroupSet = new HashSet<>(tririgaGroups);

        // Compare groups
        if (entraGroupSet.equals(tririgaGroupSet)) {
            log.info("Groups match for {} ({} groups) — no sync needed",
                email, entraGroupSet.size());
            return new SyncResult(true, tririgaGroups, resolvedGroups, false);
        }

        // Groups differ — call SSOConnect
        log.info("Groups differ for {}: TRIRIGA={}, Entra ID={}",
            email, tririgaGroupSet, entraGroupSet);

        String groupString = String.join(",", entraGroupSet);
        boolean ok = masApiClient.syncUserGroups(email, groupString);

        if (ok) {
            log.info("Groups updated successfully for {}", email);
        } else {
            log.error("Failed to update groups for {}", email);
        }

        return new SyncResult(ok, tririgaGroups, resolvedGroups, true);
    }

    private List<String> queryTririgaGroups(String email) {
        try {
            QueryResult result = tririgaWsClient.runNamedQuery(
                queryProjectName, queryModuleName, queryObjectTypeName, queryName,
                queryFilterField, email, queryFilterOperator, queryFilterDataType,
                0, 1000);

            if (result == null) {
                log.warn("runNamedQuery returned null for {}", email);
                return null;
            }

            List<String> groups = tririgaWsClient.extractColumnValues(result, queryGroupColumnName);
            log.info("Queried {} groups for {} from TRIRIGA: {}", groups.size(), email, groups);
            return groups;

        } catch (Exception e) {
            log.error("Failed to query TRIRIGA groups for {}: {}", email, e.getMessage(), e);
            return null;
        }
    }

    private boolean isNamedQueryConfigured() {
        return queryName != null && !queryName.isEmpty();
    }

    public static class SyncResult {
        private final boolean success;
        private final List<String> tririgaGroups;
        private final List<String> resolvedGroups;
        private final boolean wasSynced;

        public SyncResult(boolean success, List<String> tririgaGroups,
                          List<String> resolvedGroups, boolean wasSynced) {
            this.success = success;
            this.tririgaGroups = tririgaGroups != null ? tririgaGroups : Collections.emptyList();
            this.resolvedGroups = resolvedGroups != null ? resolvedGroups : Collections.emptyList();
            this.wasSynced = wasSynced;
        }

        public boolean isSuccess() { return success; }
        public List<String> getTririgaGroups() { return tririgaGroups; }
        public List<String> getResolvedGroups() { return resolvedGroups; }
        public boolean wasSynced() { return wasSynced; }
    }
}
