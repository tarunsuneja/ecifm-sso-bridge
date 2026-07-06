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

import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfIntegrationField;
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfIntegrationRecord;
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfIntegrationSection1;
import com.ecifm.saml.bridge.tririga.generated.dto.IntegrationField;
import com.ecifm.saml.bridge.tririga.generated.dto.IntegrationRecord;
import com.ecifm.saml.bridge.tririga.generated.dto.IntegrationSection;
import com.ecifm.saml.bridge.tririga.generated.dto.QueryMultiBoResult;
import com.ecifm.saml.bridge.tririga.generated.dto.Record;
import com.ecifm.saml.bridge.tririga.generated.dto.ResponseHelperHeader;

@Service
public class MasGroupSyncService {

    private static final Logger log = LoggerFactory.getLogger(MasGroupSyncService.class);

    private final TririgaWsClient tririgaWsClient;
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

    @Value("${tririga.people.status-column-name:triStatusCL}")
    private String statusColumnName;

    @Value("${tririga.people.active-status-value:Active User}")
    private String activeStatusValue;

    @Value("${tririga.people.poll-max-retries:6}")
    private int pollMaxRetries;

    @Value("${tririga.people.poll-delay-ms:5000}")
    private long pollDelayMs;

    public MasGroupSyncService(TririgaWsClient tririgaWsClient,
                                EntraIdGroupResolver entraIdGroupResolver) {
        this.tririgaWsClient = tririgaWsClient;
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

        // Query TRIRIGA for current groups via named query
        QueryMultiBoResult queryResult = queryTririgaGroups(email);

        if (queryResult == null) {
            log.warn("Failed to query TRIRIGA groups for {}, cannot sync", email);
            return new SyncResult(false, Collections.emptyList(), resolvedGroups, false);
        }

        List<String> tririgaGroups = tririgaWsClient.extractColumnValuesFromMultiBo(queryResult, queryGroupColumnName);
        if (tririgaGroups.isEmpty()) {
            log.warn("No groups found in TRIRIGA for {}", email);
        }

        Set<String> tririgaGroupSet = new HashSet<>(tririgaGroups);

        // Compare groups
        if (entraGroupSet.equals(tririgaGroupSet)) {
            log.info("Groups match for {} ({} groups) — no sync needed",
                email, entraGroupSet.size());
            return new SyncResult(true, tririgaGroups, resolvedGroups, false);
        }

        // Groups differ — update via Business Connect saveRecord on People
        log.info("Groups differ for {}: TRIRIGA={}, Entra ID={}",
            email, tririgaGroupSet, entraGroupSet);

        boolean ok = updatePeopleGroups(email, queryResult, entraGroupSet);

        if (ok) {
            log.info("Groups updated successfully for {}", email);
        } else {
            log.error("Failed to update groups for {}", email);
        }

        return new SyncResult(ok, tririgaGroups, resolvedGroups, true);
    }

    private boolean updatePeopleGroups(String email, QueryMultiBoResult queryResult, Set<String> entraGroupSet) {
        try {
            String recordIdStr = tririgaWsClient.extractFirstRecordIdFromMultiBo(queryResult);
            if (recordIdStr == null || recordIdStr.isEmpty()) {
                log.warn("No record ID found for {}", email);
                return false;
            }

            long recordId = Long.parseLong(recordIdStr);

            // Get record metadata from TRIRIGA (moduleId, guiId, objectTypeName)
            Record guiRecord = tririgaWsClient.getRecordDataHeader(recordId);
            if (guiRecord == null) {
                log.warn("Could not retrieve record header for {}", email);
                return false;
            }

            // Build the group string (comma-separated list from Entra ID groups)
            String groupString = String.join(",", entraGroupSet);

            // Build IntegrationRecord following Business Connect pattern
            IntegrationRecord integrationRecord = new IntegrationRecord();
            integrationRecord.setId(recordId);
            integrationRecord.setModuleId(guiRecord.getModuleId());
            if (guiRecord.getObjectTypeName() != null) {
                integrationRecord.setObjectTypeName(guiRecord.getObjectTypeName().getValue());
            }
            if (guiRecord.getGuiId() != null) {
                integrationRecord.setGuiId(guiRecord.getGuiId());
            }

            // Set the group field
            IntegrationField groupField = new IntegrationField();
            groupField.setName(peopleGroupFieldName);
            groupField.setValue(groupString);

            IntegrationSection section = new IntegrationSection();
            section.setName(peopleSectionName);
            ArrayOfIntegrationField fields = new ArrayOfIntegrationField();
            fields.getIntegrationField().add(groupField);
            com.ecifm.saml.bridge.tririga.generated.dto.ObjectFactory of =
                new com.ecifm.saml.bridge.tririga.generated.dto.ObjectFactory();
            section.setFields(of.createIntegrationSectionFields(fields));

            ArrayOfIntegrationSection1 sections = new ArrayOfIntegrationSection1();
            sections.getIntegrationSection().add(section);
            integrationRecord.setSections(sections);

            // NOTE: Not setting actionName — MAS Core denies transitions on managed records.
            // The field value is saved directly; the reference project's workflow (cstValidateADGroup)
            // is not compatible with MAS Core managed triPeople records.
            //if (peopleGroupFieldAction != null && !peopleGroupFieldAction.isEmpty()) {
            //    integrationRecord.setActionName(peopleGroupFieldAction);
            //}

            // Save via Business Connect
            ArrayOfIntegrationRecord records = new ArrayOfIntegrationRecord();
            records.getIntegrationRecord().add(integrationRecord);

            ResponseHelperHeader response = tririgaWsClient.saveRecord(records);
            if (response != null && !response.isAnyFailed()) {
                log.info("saveRecord succeeded for {}: total={}, successful={}",
                    email, response.getTotal(), response.getSuccessful());
                return true;
            }

            log.warn("saveRecord reported failure for {}: anyFailed={}, total={}, successful={}, failed={}",
                email, response != null ? response.isAnyFailed() : "null",
                response != null ? response.getTotal() : -1,
                response != null ? response.getSuccessful() : -1,
                response != null ? response.getFailed() : -1);
            return false;

        } catch (Exception e) {
            log.error("Failed to update groups for {} via Business Connect: {}", email, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Poll for workflow completion by re-running the named query
     * and checking the status column for the active value.
     */
    private boolean pollForActiveStatus(String email) {
        for (int attempt = 0; attempt < pollMaxRetries; attempt++) {
            try {
                Thread.sleep(pollDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while polling status for {}", email);
                return false;
            }

            QueryMultiBoResult result = queryTririgaGroups(email);
            if (result == null) {
                log.warn("Poll attempt {}: query returned null for {}", attempt + 1, email);
                continue;
            }

            List<String> statuses = tririgaWsClient.extractColumnValuesFromMultiBo(result, statusColumnName);
            String status = statuses.isEmpty() ? "" : statuses.get(0);
            log.info("Poll attempt {} for {}: status='{}'", attempt + 1, email, status);

            if (activeStatusValue.equalsIgnoreCase(status.trim())) {
                return true;
            }
        }
        log.warn("Max retries ({}) reached polling status for {}", pollMaxRetries, email);
        return false;
    }

    private QueryMultiBoResult queryTririgaGroups(String email) {
        try {
            QueryMultiBoResult result = tririgaWsClient.runNamedQueryMultiBo(
                queryProjectName, queryModuleName, queryObjectTypeName, queryName,
                queryFilterField, email, queryFilterOperator, queryFilterDataType,
                0, 1000);

            if (result == null) {
                log.warn("runNamedQueryMultiBo returned null for {}", email);
                return null;
            }

            int total = result.getTotalResults() != null ? result.getTotalResults() : 0;
            log.info("runNamedQueryMultiBo returned {} total results for {}", total, email);
            return result;

        } catch (Exception e) {
            log.error("Failed to query TRIRIGA groups for {}: {}", email, e.getMessage(), e);
            return null;
        }
    }

    public SyncResult testSyncGroups(String email, List<String> testGroups) {
        log.info("Test group sync for {} with groups: {}", email, testGroups);

        if (testGroups == null || testGroups.isEmpty()) {
            log.warn("No test groups provided for {}", email);
            return new SyncResult(false, Collections.emptyList(), Collections.emptyList(), false);
        }

        Set<String> groupSet = testGroups.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .collect(Collectors.toSet());

        QueryMultiBoResult queryResult = queryTririgaGroups(email);
        if (queryResult == null) {
            return new SyncResult(false, Collections.emptyList(), testGroups, false);
        }

        List<String> tririgaGroups = tririgaWsClient.extractColumnValuesFromMultiBo(queryResult, queryGroupColumnName);
        Set<String> tririgaGroupSet = new HashSet<>(tririgaGroups);

        if (groupSet.equals(tririgaGroupSet)) {
            log.info("Test groups match current TRIRIGA groups for {} — no sync needed", email);
            return new SyncResult(true, tririgaGroups, testGroups, false);
        }

        log.info("Test groups differ for {}: TRIRIGA={}, test={}", email, tririgaGroupSet, groupSet);
        boolean ok = updatePeopleGroups(email, queryResult, groupSet);

        return new SyncResult(ok, tririgaGroups, testGroups, ok);
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
