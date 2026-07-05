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
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfIntegrationRows;
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfIntegrationSection1;
import com.ecifm.saml.bridge.tririga.generated.dto.IntegrationField;
import com.ecifm.saml.bridge.tririga.generated.dto.IntegrationRecord;
import com.ecifm.saml.bridge.tririga.generated.dto.IntegrationRows;
import com.ecifm.saml.bridge.tririga.generated.dto.IntegrationSection;
import com.ecifm.saml.bridge.tririga.generated.dto.ObjectFactory;
import com.ecifm.saml.bridge.tririga.generated.dto.QueryResult;
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

    @Value("${tririga.named-query.filter-operator:0}")
    private int queryFilterOperator;

    @Value("${tririga.named-query.filter-data-type:1}")
    private int queryFilterDataType;

    @Value("${tririga.people.project-name:}")
    private String peopleProjectName;

    @Value("${tririga.people.module-name:}")
    private String peopleModuleName;

    @Value("${tririga.people.object-type-name:}")
    private String peopleObjectTypeName;

    @Value("${tririga.people.group-section-name:triPeopleTXGroup}")
    private String peopleGroupSectionName;

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
        QueryResult queryResult = queryTririgaGroups(email);

        if (queryResult == null) {
            log.warn("Failed to query TRIRIGA groups for {}, cannot sync", email);
            return new SyncResult(false, Collections.emptyList(), resolvedGroups, false);
        }

        List<String> tririgaGroups = tririgaWsClient.extractColumnValues(queryResult, queryGroupColumnName);
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

        boolean ok = updatePeopleGroups(email, queryResult);

        if (ok) {
            log.info("Groups updated successfully for {}", email);
        } else {
            log.error("Failed to update groups for {}", email);
        }

        return new SyncResult(ok, tririgaGroups, resolvedGroups, true);
    }

    private boolean updatePeopleGroups(String email, QueryResult queryResult) {
        try {
            String recordIdStr = tririgaWsClient.extractFirstRecordId(queryResult);
            if (recordIdStr == null || recordIdStr.isEmpty()) {
                log.warn("No record ID found for {}", email);
                return false;
            }

            long recordId = Long.parseLong(recordIdStr);
            int moduleId = tririgaWsClient.getModuleId(peopleModuleName);
            long objectTypeId = tririgaWsClient.getObjectTypeId(peopleModuleName, peopleObjectTypeName);

            if (moduleId < 0 || objectTypeId < 0) {
                log.warn("Failed to resolve module/object type IDs for people record");
                return false;
            }

            ObjectFactory of = new ObjectFactory();

            IntegrationRecord integrationRecord = new IntegrationRecord();
            integrationRecord.setActionName("UPDATE");
            integrationRecord.setId(recordId);
            integrationRecord.setModuleId(moduleId);
            integrationRecord.setObjectTypeId(objectTypeId);
            integrationRecord.setObjectTypeName(peopleObjectTypeName);

            IntegrationSection section = new IntegrationSection();
            section.setName(peopleGroupSectionName);
            section.setType(of.createIntegrationSectionType("M"));

            ArrayOfIntegrationRows rows = new ArrayOfIntegrationRows();
            List<IntegrationRows> rowList = rows.getIntegrationRows();

            IntegrationRows deleteRows = new IntegrationRows();
            deleteRows.setAction("Delete");
            deleteRows.setRecordId(0L);
            rowList.add(deleteRows);

            List<String> groups = tririgaWsClient.extractColumnValues(queryResult, queryGroupColumnName);
            for (String group : new HashSet<>(groups)) {
                IntegrationRows addRow = new IntegrationRows();
                addRow.setAction("Add");
                ArrayOfIntegrationField fields = new ArrayOfIntegrationField();
                IntegrationField field = new IntegrationField();
                field.setName(peopleGroupSectionName);
                field.setValue(group);
                fields.getIntegrationField().add(field);
                addRow.setFields(of.createIntegrationRowsFields(fields));
                rowList.add(addRow);
            }

            section.setRows(of.createIntegrationSectionRows(rows));

            ArrayOfIntegrationSection1 sections = new ArrayOfIntegrationSection1();
            sections.getIntegrationSection().add(section);
            integrationRecord.setSections(sections);

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

    private QueryResult queryTririgaGroups(String email) {
        try {
            QueryResult result = tririgaWsClient.runNamedQuery(
                queryProjectName, queryModuleName, queryObjectTypeName, queryName,
                queryFilterField, email, queryFilterOperator, queryFilterDataType,
                0, 1000);

            if (result == null) {
                log.warn("runNamedQuery returned null for {}", email);
                return null;
            }

            int total = result.getTotalResults() != null ? result.getTotalResults() : 0;
            log.info("runNamedQuery returned {} total results for {}", total, email);
            return result;

        } catch (Exception e) {
            log.error("Failed to query TRIRIGA groups for {}: {}", email, e.getMessage(), e);
            return null;
        }
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
