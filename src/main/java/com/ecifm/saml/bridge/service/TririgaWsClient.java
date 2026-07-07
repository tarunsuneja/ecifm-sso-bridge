package com.ecifm.saml.bridge.service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.cxf.configuration.jsse.TLSClientParameters;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.message.Message;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ecifm.saml.bridge.tririga.generated.dto.ApplicationInfo;
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfAvailableAction;
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfFilter;
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfIntegrationRecord;
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfRecord;
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfResponseHelper;
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfTriggerActions;
import com.ecifm.saml.bridge.tririga.generated.dto.TriggerActions;
import com.ecifm.saml.bridge.tririga.generated.dto.ResponseHelper;
import com.ecifm.saml.bridge.tririga.generated.dto.Filter;
import com.ecifm.saml.bridge.tririga.generated.dto.IntegrationRecord;
import com.ecifm.saml.bridge.tririga.generated.dto.QueryMultiBoResult;
import com.ecifm.saml.bridge.tririga.generated.dto.QueryResult;
import com.ecifm.saml.bridge.tririga.generated.dto.QueryResponseColumn;
import com.ecifm.saml.bridge.tririga.generated.dto.QueryResponseHelper;
import com.ecifm.saml.bridge.tririga.generated.dto.ResponseHelperHeader;
import com.ecifm.saml.bridge.tririga.generated.ws.TririgaWS;
import com.ecifm.saml.bridge.tririga.generated.ws.TririgaWSPortType;

import jakarta.xml.ws.BindingProvider;

@Component
public class TririgaWsClient {

    private static final Logger log = LoggerFactory.getLogger(TririgaWsClient.class);

    private static final String WSDL_RESOURCE = "/wsdl/TririgaWS.wsdl";

    @Value("${mas.base-url}")
    private String masBaseUrl;

    @Value("${mas.context}")
    private String masContext;

    @Value("${tririga.username}")
    private String tririgaUsername;

    @Value("${tririga.password}")
    private String tririgaPassword;

    public String getApplicationInfo() {
        return getApplicationInfo(null);
    }

    public String getApplicationInfo(String preAuthJsessionId) {
        try {
            TririgaWSPortType port = createPort(preAuthJsessionId);
            ApplicationInfo info = port.getApplicationInfo();
            log.info("getApplicationInfo succeeded: version={}", info.getApiVersion());
            return "Success:\n"
                    + "  apiVersion: " + value(info.getApiVersion()) + "\n"
                    + "  dbBuildNumber: " + value(info.getDbBuildNumber()) + "\n"
                    + "  tririgaBuildNumber: " + value(info.getTririgaBuildNumber());
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            log.warn("getApplicationInfo failed: {}\n{}", e.getMessage(), sw);
            return "Failed: " + e.getMessage();
        }
    }

    public String getHttpSession(String jsessionId) {
        try {
            TririgaWSPortType port = createPort(jsessionId);
            var session = port.getHttpSession();
            log.info("getHttpSession succeeded");
            return "Success:\n"
                    + "  Session ID: " + session.getId();
        } catch (Exception e) {
            log.warn("getHttpSession failed: {}", e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }

    public String getApplicationInfoWithBearer(String token) {
        try {
            TririgaWSPortType port = createPortWithBearer(token);
            ApplicationInfo info = port.getApplicationInfo();
            log.info("getApplicationInfoWithBearer succeeded: version={}", info.getApiVersion());
            return "Success:\n"
                    + "  apiVersion: " + value(info.getApiVersion()) + "\n"
                    + "  dbBuildNumber: " + value(info.getDbBuildNumber()) + "\n"
                    + "  tririgaBuildNumber: " + value(info.getTririgaBuildNumber());
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            log.warn("getApplicationInfoWithBearer failed: {}\n{}", e.getMessage(), sw);
            return "Failed: " + e.getMessage();
        }
    }

    public QueryResult runNamedQuery(String projectName, String moduleName,
                                       String objectTypeName, String queryName,
                                       String filterField, String filterValue,
                                       int filterOperator, int filterDataType,
                                       int start, int maxResults) {
        try {
            TririgaWSPortType port = createPort();

            ArrayOfFilter arrayOfFilter = null;
            if (filterField != null && !filterField.isEmpty() && filterValue != null && !filterValue.isEmpty()) {
                Filter filter = new Filter();
                filter.setFieldName(filterField);
                filter.setValue(filterValue);
                filter.setOperator(filterOperator);
                filter.setDataType(filterDataType);
                filter.setSectionName("");

                arrayOfFilter = new ArrayOfFilter();
                arrayOfFilter.getFilter().add(filter);
            }

            QueryResult result = port.runNamedQuery(
                projectName, moduleName, objectTypeName, queryName,
                arrayOfFilter, start, maxResults);

            Integer total = result.getTotalResults();
            log.info("runNamedQuery '{}' returned {} total results", queryName,
                total != null ? total : 0);
            return result;

        } catch (Exception e) {
            log.error("runNamedQuery '{}' failed: {}", queryName, e.getMessage(), e);
            return null;
        }
    }

    public String extractFirstRecordId(QueryResult result) {
        if (result == null) return null;

        var helpers = result.getQueryResponseHelpers();
        if (helpers == null || helpers.getValue() == null) return null;

        var list = helpers.getValue().getQueryResponseHelper();
        if (list == null || list.isEmpty()) return null;

        String recordId = value(list.get(0).getRecordId());
        log.debug("extractFirstRecordId: {}", recordId);
        return recordId;
    }

    public List<String> extractColumnValues(QueryResult result, String columnName) {
        List<String> values = new java.util.ArrayList<>();

        if (result == null) return values;

        var helpers = result.getQueryResponseHelpers();
        if (helpers == null || helpers.getValue() == null) return values;

        for (QueryResponseHelper helper : helpers.getValue().getQueryResponseHelper()) {
            var columns = helper.getQueryResponseColumns();
            if (columns == null || columns.getValue() == null) continue;

            for (QueryResponseColumn col : columns.getValue().getQueryResponseColumn()) {
                String name = value(col.getName());
                if (name.equals(columnName)) {
                    String val = value(col.getValue());
                    if (val != null && !val.isEmpty()) {
                        values.add(val);
                    }
                }
            }
        }

        return values;
    }

    public String getAuthenticatedSessionId() {
        try {
            TririgaWSPortType port = createPort();
            port.getApplicationInfo();
            Client client = ClientProxy.getClient(port);
            Map<String, List<String>> responseHeaders = (Map<String, List<String>>)
                client.getResponseContext().get(Message.PROTOCOL_HEADERS);
            if (responseHeaders != null) {
                List<String> setCookie = responseHeaders.get("Set-Cookie");
                if (setCookie != null) {
                    for (String c : setCookie) {
                        Matcher m = Pattern.compile("JSESSIONID=([^;]+)").matcher(c);
                        if (m.find()) {
                            String jsessionId = m.group(1);
                            log.info("Extracted JSESSIONID from SOAP response: {}", jsessionId);
                            return jsessionId;
                        }
                    }
                }
            }
            log.warn("No JSESSIONID found in SOAP response headers");
            return null;
        } catch (Exception e) {
            log.warn("getAuthenticatedSessionId failed: {}", e.getMessage());
            return null;
        }
    }

    public int getModuleId(String moduleName) {
        try {
            TririgaWSPortType port = createPort();
            int id = port.getModuleId(moduleName);
            log.info("getModuleId('{}') = {}", moduleName, id);
            return id;
        } catch (Exception e) {
            log.error("getModuleId('{}') failed: {}", moduleName, e.getMessage(), e);
            return -1;
        }
    }

    public long getObjectTypeId(String moduleName, String objectTypeName) {
        try {
            TririgaWSPortType port = createPort();
            long id = port.getObjectTypeId(moduleName, objectTypeName);
            log.info("getObjectTypeId('{}', '{}') = {}", moduleName, objectTypeName, id);
            return id;
        } catch (Exception e) {
            log.error("getObjectTypeId('{}', '{}') failed: {}", moduleName, objectTypeName, e.getMessage(), e);
            return -1;
        }
    }

    public com.ecifm.saml.bridge.tririga.generated.dto.Record getRecordDataHeader(long recordId) {
        try {
            TririgaWSPortType port = createPort();
            com.ecifm.saml.bridge.tririga.generated.ws.ArrayOfLong ids = new com.ecifm.saml.bridge.tririga.generated.ws.ArrayOfLong();
            ids.getLong().add(recordId);

            ArrayOfRecord records = port.getRecordDataHeaders(ids);
            if (records != null && records.getRecord() != null && !records.getRecord().isEmpty()) {
                com.ecifm.saml.bridge.tririga.generated.dto.Record rec = records.getRecord().get(0);
                log.info("getRecordDataHeader: id={}, name={}, moduleId={}, objectTypeName={}",
                    rec.getId(), rec.getName(), rec.getModuleId(), rec.getObjectTypeName());
                return rec;
            }
            log.warn("getRecordDataHeader returned empty for recordId={}", recordId);
            return null;
        } catch (Exception e) {
            log.error("getRecordDataHeader failed for recordId={}: {}", recordId, e.getMessage(), e);
            return null;
        }
    }

    public ArrayOfAvailableAction getAvailableActions(long recordId) {
        try {
            TririgaWSPortType port = createPort();
            com.ecifm.saml.bridge.tririga.generated.ws.ArrayOfLong ids = new com.ecifm.saml.bridge.tririga.generated.ws.ArrayOfLong();
            ids.getLong().add(recordId);
            ArrayOfAvailableAction result = port.getAvailableActions(ids);
            log.info("getAvailableActions for recordId={}: {} available actions returned",
                recordId, result != null && result.getAvailableAction() != null ? result.getAvailableAction().size() : 0);
            return result;
        } catch (Exception e) {
            log.error("getAvailableActions failed for recordId={}: {}", recordId, e.getMessage(), e);
            return null;
        }
    }

    public ResponseHelperHeader saveRecord(ArrayOfIntegrationRecord records) {
        try {
            TririgaWSPortType port = createPort();
            ResponseHelperHeader result = port.saveRecord(records);
            log.info("saveRecord: anyFailed={}, total={}, successful={}, failed={}",
                result.isAnyFailed(), result.getTotal(), result.getSuccessful(), result.getFailed());
            if (result.getResponseHelpers() != null) {
                ArrayOfResponseHelper arr = result.getResponseHelpers().getValue();
                if (arr != null && arr.getResponseHelper() != null) {
                    for (ResponseHelper rh : arr.getResponseHelper()) {
                        String status = rh.getStatus() != null ? rh.getStatus().getValue() : null;
                        String val = rh.getValue() != null ? rh.getValue().getValue() : null;
                        log.info("saveRecord responseHelper: key={}, name={}, recordId={}, status={}, value={}",
                            rh.getKey() != null ? rh.getKey().getValue() : null,
                            rh.getName() != null ? rh.getName().getValue() : null,
                            rh.getRecordId(), status, val);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("saveRecord failed: {}", e.getMessage(), e);
            return null;
        }
    }

    public ResponseHelperHeader triggerActions(String actionName, long recordId) {
        try {
            TririgaWSPortType port = createPort();

            TriggerActions action = new TriggerActions();
            action.setActionName(actionName);
            action.setRecordId(recordId);

            ArrayOfTriggerActions arrayOfTriggerActions = new ArrayOfTriggerActions();
            arrayOfTriggerActions.getTriggerActions().add(action);

            ResponseHelperHeader result = port.triggerActions(arrayOfTriggerActions);
            log.info("triggerActions('{}' on recordId={}): anyFailed={}, total={}, successful={}, failed={}",
                actionName, recordId, result.isAnyFailed(), result.getTotal(), result.getSuccessful(), result.getFailed());
            if (result.getResponseHelpers() != null) {
                ArrayOfResponseHelper arr = result.getResponseHelpers().getValue();
                if (arr != null && arr.getResponseHelper() != null) {
                    for (ResponseHelper rh : arr.getResponseHelper()) {
                        String status = rh.getStatus() != null ? rh.getStatus().getValue() : null;
                        String val = rh.getValue() != null ? rh.getValue().getValue() : null;
                        log.info("triggerActions responseHelper: key={}, name={}, recordId={}, status={}, value={}",
                            rh.getKey() != null ? rh.getKey().getValue() : null,
                            rh.getName() != null ? rh.getName().getValue() : null,
                            rh.getRecordId(), status, val);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("triggerActions failed for actionName='{}' recordId={}: {}", actionName, recordId, e.getMessage(), e);
            return null;
        }
    }

    public QueryMultiBoResult runNamedQueryMultiBo(String projectName, String moduleName,
                                                     String objectTypeName, String queryName,
                                                     String filterField, String filterValue,
                                                     int filterOperator, int filterDataType,
                                                     int start, int maxResults) {
        try {
            TririgaWSPortType port = createPort();

            ArrayOfFilter arrayOfFilter = null;
            if (filterField != null && !filterField.isEmpty() && filterValue != null && !filterValue.isEmpty()) {
                Filter filter = new Filter();
                filter.setFieldName(filterField);
                filter.setValue(filterValue);
                filter.setOperator(filterOperator);
                filter.setDataType(filterDataType);
                filter.setSectionName("");

                arrayOfFilter = new ArrayOfFilter();
                arrayOfFilter.getFilter().add(filter);
            }

            QueryMultiBoResult result = port.runNamedQueryMultiBo(
                projectName, moduleName, objectTypeName, queryName,
                arrayOfFilter, start, maxResults);

            Integer total = result.getTotalResults();
            log.info("runNamedQueryMultiBo '{}' returned {} total results", queryName,
                total != null ? total : 0);
            return result;

        } catch (Exception e) {
            log.error("runNamedQueryMultiBo '{}' failed: {}", queryName, e.getMessage(), e);
            return null;
        }
    }

    public String extractFirstRecordIdFromMultiBo(QueryMultiBoResult result) {
        if (result == null) return null;

        var helpers = result.getQueryMultiBoResponseHelpers();
        if (helpers == null || helpers.getValue() == null) return null;

        var list = helpers.getValue().getQueryMultiBoResponseHelper();
        if (list == null || list.isEmpty()) return null;

        String recordId = value(list.get(0).getRecordId());
        log.debug("extractFirstRecordIdFromMultiBo: {}", recordId);
        return recordId;
    }

    public List<String> extractColumnValuesFromMultiBo(QueryMultiBoResult result, String columnName) {
        List<String> values = new java.util.ArrayList<>();

        if (result == null) return values;

        var helpers = result.getQueryMultiBoResponseHelpers();
        if (helpers == null || helpers.getValue() == null) return values;

        for (var helper : helpers.getValue().getQueryMultiBoResponseHelper()) {
            var columns = helper.getQueryMultiBoResponseColumns();
            if (columns == null || columns.getValue() == null) continue;

            for (var col : columns.getValue().getQueryMultiBoResponseColumn()) {
                String name = value(col.getName());
                if (name.equals(columnName)) {
                    String val = value(col.getValue());
                    if (val != null && !val.isEmpty()) {
                        values.add(val);
                    }
                }
            }
        }

        return values;
    }

    private TririgaWSPortType createPortWithBearer(String bearerToken) throws Exception {
        String ctx = masContext == null ? "" : masContext.trim().replaceAll("^/+|/+$", "");
        String rawUrl = ctx.isEmpty() ? masBaseUrl + "/ws/TririgaWS" : masBaseUrl + "/" + ctx + "/ws/TririgaWS";
        String endpoint = rawUrl.trim();
        log.info("Creating CXF client (Bearer) for endpoint: {}", endpoint);

        URL wsdlUrl = getClass().getResource(WSDL_RESOURCE);
        TririgaWS service = new TririgaWS(wsdlUrl);
        TririgaWSPortType port = service.getTririgaWSPort();

        BindingProvider bp = (BindingProvider) port;
        bp.getRequestContext().put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, endpoint);
        bp.getRequestContext().put(BindingProvider.SESSION_MAINTAIN_PROPERTY, Boolean.TRUE);

        Map<String, List<String>> headers = new HashMap<>();
        headers.put("Authorization", List.of("Bearer " + bearerToken));
        bp.getRequestContext().put(Message.PROTOCOL_HEADERS, headers);
        log.info("Added Bearer token Authorization header");

        Client client = ClientProxy.getClient(port);
        HTTPConduit conduit = (HTTPConduit) client.getConduit();

        HTTPClientPolicy policy = new HTTPClientPolicy();
        policy.setConnectionTimeout(30_000);
        policy.setReceiveTimeout(120_000);
        policy.setAllowChunking(false);
        conduit.setClient(policy);

        TLSClientParameters tls = new TLSClientParameters();
        tls.setDisableCNCheck(true);
        tls.setTrustManagers(new TrustManager[]{
            new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }
        });
        conduit.setTlsClientParameters(tls);

        log.info("CXF client (Bearer) configured");
        return port;
    }

    private TririgaWSPortType createPort() throws Exception {
        return createPort(null);
    }

    private TririgaWSPortType createPort(String jsessionId) throws Exception {
        String ctx = masContext == null ? "" : masContext.trim().replaceAll("^/+|/+$", "");
        String rawUrl = ctx.isEmpty() ? masBaseUrl + "/ws/TririgaWS" : masBaseUrl + "/" + ctx + "/ws/TririgaWS";
        String endpoint = rawUrl.trim();
        log.info("Creating CXF client for endpoint: {}", endpoint);

        URL wsdlUrl = getClass().getResource(WSDL_RESOURCE);
        TririgaWS service = new TririgaWS(wsdlUrl);
        TririgaWSPortType port = service.getTririgaWSPort();

        BindingProvider bp = (BindingProvider) port;
        bp.getRequestContext().put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, endpoint);
        bp.getRequestContext().put(BindingProvider.SESSION_MAINTAIN_PROPERTY, Boolean.TRUE);

        String basicValue = tririgaUsername + ":" + tririgaPassword;
        String basicHeader = "Basic " + Base64.getEncoder().encodeToString(basicValue.getBytes());
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("Authorization", List.of(basicHeader));
        if (jsessionId != null && !jsessionId.isEmpty()) {
            headers.put("Cookie", List.of("JSESSIONID=" + jsessionId));
        }
        bp.getRequestContext().put(Message.PROTOCOL_HEADERS, headers);
        log.info("Added HTTP Basic Authorization header for user '{}'", tririgaUsername);

        Client client = ClientProxy.getClient(port);
        HTTPConduit conduit = (HTTPConduit) client.getConduit();

        HTTPClientPolicy policy = new HTTPClientPolicy();
        policy.setConnectionTimeout(30_000);
        policy.setReceiveTimeout(120_000);
        policy.setAllowChunking(false);
        conduit.setClient(policy);

        TLSClientParameters tls = new TLSClientParameters();
        tls.setDisableCNCheck(true);
        tls.setTrustManagers(new TrustManager[]{
            new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }
        });
        conduit.setTlsClientParameters(tls);

        log.info("CXF client configured with HTTP Basic auth for user '{}'", tririgaUsername);
        return port;
    }

    private static String value(jakarta.xml.bind.JAXBElement<String> element) {
        return element == null ? "" : element.getValue();
    }
}
