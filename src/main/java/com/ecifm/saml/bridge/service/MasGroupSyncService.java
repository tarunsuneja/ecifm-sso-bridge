package com.ecifm.saml.bridge.service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.xml.bind.JAXBElement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfAssociation;
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfIntegrationField;
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfIntegrationRecord;
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfIntegrationRows;
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfIntegrationSection1;
import com.ecifm.saml.bridge.tririga.generated.dto.Association;
import com.ecifm.saml.bridge.tririga.generated.dto.IntegrationField;
import com.ecifm.saml.bridge.tririga.generated.dto.IntegrationRecord;
import com.ecifm.saml.bridge.tririga.generated.dto.IntegrationRows;
import com.ecifm.saml.bridge.tririga.generated.dto.IntegrationSection;
import com.ecifm.saml.bridge.tririga.generated.dto.ObjectFactory;
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

    @Value("${tririga.people.current-group-field-name:cstCurrentADGroupTX}")
    private String currentGroupFieldName;

    @Value("${tririga.people.previous-group-field-name:cstPreviousADGroupTX}")
    private String previousGroupFieldName;

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

    @Value("${tririga.template.module-name:triPeople}")
    private String templateModuleName;

    @Value("${tririga.template.object-type-name:triPeople}")
    private String templateObjectTypeName;

    @Value("${tririga.template.query-name:cstPeople - Query - Get All the People Templates}")
    private String templateQueryName;

    @Value("${tririga.template.filter-field:cstADGroupTX}")
    private String templateFilterField;

    @Value("${tririga.template.group-details-section:triGroupsDetails}")
    private String groupDetailsSection;

    @Value("${tririga.template.licence-details-section:triLicenceDetails}")
    private String licenceDetailsSection;

    @Value("${tririga.template.home-page-field:triHomePageLI}")
    private String homePageField;

    @Value("${tririga.template.menu-field:triMenuLI}")
    private String menuField;

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

            Record guiRecord = tririgaWsClient.getRecordDataHeader(recordId);
            if (guiRecord == null) {
                log.warn("Could not retrieve record header for {}", email);
                return false;
            }

            String groupString = String.join(",", entraGroupSet);
            ObjectFactory of = new ObjectFactory();

            // Get current group value from query for old-group tracking
            List<String> currentGroups = tririgaWsClient.extractColumnValuesFromMultiBo(queryResult, queryGroupColumnName);
            String currentGroup = currentGroups.isEmpty() ? "" : currentGroups.get(0);

            // Query People Template matching the new group
            long templateId = findPeopleTemplate(groupString, email);
            if (templateId == -1) {
                log.warn("No People Template found for group '{}' — writing field only; activation may be incomplete", groupString);
            }

            // Phase 1: Write fields via saveRecord
            // Write cstNewADGroupTX, shift current->previous, new->current
            List<IntegrationField> fieldUpdates = new ArrayList<>();
            fieldUpdates.add(buildField(peopleGroupFieldName, groupString));
            if (!currentGroup.isEmpty()) {
                fieldUpdates.add(buildField(previousGroupFieldName, currentGroup));
            }
            fieldUpdates.add(buildField(currentGroupFieldName, groupString));

            IntegrationSection fieldSection = new IntegrationSection();
            fieldSection.setName(peopleSectionName);
            ArrayOfIntegrationField fields = new ArrayOfIntegrationField();
            fields.getIntegrationField().addAll(fieldUpdates);
            fieldSection.setFields(of.createIntegrationSectionFields(fields));

            ArrayOfIntegrationSection1 sections = new ArrayOfIntegrationSection1();
            sections.getIntegrationSection().add(fieldSection);

            IntegrationRecord integrationRecord = new IntegrationRecord();
            integrationRecord.setId(recordId);
            integrationRecord.setModuleId(guiRecord.getModuleId());
            if (guiRecord.getObjectTypeName() != null) {
                integrationRecord.setObjectTypeName(guiRecord.getObjectTypeName().getValue());
            }
            if (guiRecord.getGuiId() != null) {
                integrationRecord.setGuiId(guiRecord.getGuiId());
            }
            integrationRecord.setSections(sections);

            ArrayOfIntegrationRecord records = new ArrayOfIntegrationRecord();
            records.getIntegrationRecord().add(integrationRecord);

            ResponseHelperHeader saveResponse = tririgaWsClient.saveRecord(records);
            if (saveResponse == null || saveResponse.isAnyFailed()) {
                log.warn("saveRecord failed for {}: anyFailed={}, total={}, successful={}, failed={}",
                    email, saveResponse != null ? saveResponse.isAnyFailed() : "null",
                    saveResponse != null ? saveResponse.getTotal() : -1,
                    saveResponse != null ? saveResponse.getSuccessful() : -1,
                    saveResponse != null ? saveResponse.getFailed() : -1);
                return false;
            }
            log.info("saveRecord field write succeeded for {}: total={}, successful={}",
                email, saveResponse.getTotal(), saveResponse.getSuccessful());

            // Phase 2: If template found, deassociate old group/license details and associate new ones
            if (templateId != -1) {
                // Remove existing child records (Group Details, Licence Details) associated via "Associated To"
                ArrayOfAssociation existingDetails = tririgaWsClient.getAssociatedRecords(recordId, "Associated To", null);
                if (existingDetails != null && existingDetails.getAssociation() != null) {
                    List<Long> childIds = new ArrayList<>();
                    for (Association a : existingDetails.getAssociation()) {
                        Long childId = extractAssociatedRecordId(a);
                        if (childId != null) {
                            childIds.add(childId);
                        }
                    }
                    if (!childIds.isEmpty()) {
                        log.info("Removing {} existing associated detail records for recordId={}", childIds.size(), recordId);
                        for (Long childId : childIds) {
                            // Remove each via section row delete in triGroupsDetails section
                            IntegrationRows deleteRow = new IntegrationRows();
                            deleteRow.setAction("delete");
                            deleteRow.setRecordId(childId);
                            IntegrationSection rowSection = new IntegrationSection();
                            rowSection.setName(groupDetailsSection);
                            ArrayOfIntegrationRows rows = new ArrayOfIntegrationRows();
                            rows.getIntegrationRows().add(deleteRow);
                            rowSection.setRows(of.createIntegrationSectionRows(rows));
                            ArrayOfIntegrationSection1 delSections = new ArrayOfIntegrationSection1();
                            delSections.getIntegrationSection().add(rowSection);
                            IntegrationRecord delRecord = new IntegrationRecord();
                            delRecord.setId(recordId);
                            delRecord.setModuleId(guiRecord.getModuleId());
                            if (guiRecord.getObjectTypeName() != null) {
                                delRecord.setObjectTypeName(guiRecord.getObjectTypeName().getValue());
                            }
                            if (guiRecord.getGuiId() != null) {
                                delRecord.setGuiId(guiRecord.getGuiId());
                            }
                            delRecord.setSections(delSections);
                            ArrayOfIntegrationRecord delRecords = new ArrayOfIntegrationRecord();
                            delRecords.getIntegrationRecord().add(delRecord);
                            ResponseHelperHeader delResponse = tririgaWsClient.saveRecord(delRecords);
                            if (delResponse == null || delResponse.isAnyFailed()) {
                                log.warn("Failed to delete child recordId={} from section", childId);
                            } else {
                                log.info("Deleted child recordId={} from section", childId);
                            }
                        }
                    }
                }

                // Copy template's child records (Group Details) to user
                ArrayOfAssociation templateDetails = tririgaWsClient.getAssociatedRecords(templateId, "Associated To", null);
                if (templateDetails != null && templateDetails.getAssociation() != null) {
                    for (Association a : templateDetails.getAssociation()) {
                        Long templateChildId = extractAssociatedRecordId(a);
                        if (templateChildId != null) {
                            IntegrationRows addRow = new IntegrationRows();
                            addRow.setAction("add");
                            addRow.setRecordId(templateChildId);
                            IntegrationSection rowSection = new IntegrationSection();
                            rowSection.setName(groupDetailsSection);
                            ArrayOfIntegrationRows rows = new ArrayOfIntegrationRows();
                            rows.getIntegrationRows().add(addRow);
                            rowSection.setRows(of.createIntegrationSectionRows(rows));
                            ArrayOfIntegrationSection1 addSections = new ArrayOfIntegrationSection1();
                            addSections.getIntegrationSection().add(rowSection);
                            IntegrationRecord addRecord = new IntegrationRecord();
                            addRecord.setId(recordId);
                            addRecord.setModuleId(guiRecord.getModuleId());
                            if (guiRecord.getObjectTypeName() != null) {
                                addRecord.setObjectTypeName(guiRecord.getObjectTypeName().getValue());
                            }
                            if (guiRecord.getGuiId() != null) {
                                addRecord.setGuiId(guiRecord.getGuiId());
                            }
                            addRecord.setSections(addSections);
                            ArrayOfIntegrationRecord addRecords = new ArrayOfIntegrationRecord();
                            addRecords.getIntegrationRecord().add(addRecord);
                            ResponseHelperHeader addResponse = tririgaWsClient.saveRecord(addRecords);
                            if (addResponse == null || addResponse.isAnyFailed()) {
                                log.warn("Failed to add child recordId={} to section for user", templateChildId);
                            } else {
                                log.info("Added child recordId={} to section for user", templateChildId);
                            }
                        }
                    }
                }

                // Try triActivate
                ResponseHelperHeader activateResponse = tririgaWsClient.triggerActions("triActivate", recordId);
                if (activateResponse != null && !activateResponse.isAnyFailed()) {
                    log.info("triActivate succeeded for recordId={}", recordId);
                } else {
                    log.warn("triActivate failed or returned anyFailed for recordId={}", recordId);
                }
            }

            // Phase 3: Poll for active status
            boolean active = pollForActiveStatus(email);
            log.info("pollForActiveStatus for {}: {}", email, active);

            return true;

        } catch (Exception e) {
            log.error("Failed to update groups for {} via Business Connect: {}", email, e.getMessage(), e);
            return false;
        }
    }

    private static Long extractAssociatedRecordId(Association a) {
        if (a == null || a.getRest() == null) return null;
        for (JAXBElement<? extends Serializable> elem : a.getRest()) {
            if (elem != null && "associatedRecordId".equals(elem.getName().getLocalPart())) {
                Object val = elem.getValue();
                if (val instanceof Number) {
                    return ((Number) val).longValue();
                }
            }
        }
        return null;
    }

    private IntegrationField buildField(String name, String value) {
        IntegrationField f = new IntegrationField();
        f.setName(name);
        f.setValue(value);
        return f;
    }

    private long findPeopleTemplate(String groupName, String email) {
        try {
            log.info("Looking up People Template for group '{}'", groupName);
            QueryMultiBoResult result = tririgaWsClient.runNamedQueryMultiBo(
                queryProjectName, templateModuleName, templateObjectTypeName, templateQueryName,
                templateFilterField, groupName, queryFilterOperator, queryFilterDataType, 0, 1000);
            if (result == null) {
                log.warn("People Template query returned null for group '{}'", groupName);
                return -1;
            }
            int total = result.getTotalResults() != null ? result.getTotalResults() : 0;
            log.info("People Template query returned {} results for group '{}'", total, groupName);
            String templateIdStr = tririgaWsClient.extractFirstRecordIdFromMultiBo(result);
            if (templateIdStr == null || templateIdStr.isEmpty()) {
                log.warn("No People Template found for group '{}'", groupName);
                return -1;
            }
            long templateId = Long.parseLong(templateIdStr);
            log.info("Found People Template: recordId={} for group '{}'", templateId, groupName);
            return templateId;
        } catch (Exception e) {
            log.error("Error querying People Template for group '{}': {}", groupName, e.getMessage(), e);
            return -1;
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
