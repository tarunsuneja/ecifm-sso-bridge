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
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfFilter;
import com.ecifm.saml.bridge.tririga.generated.dto.ArrayOfIntegrationRecord;
import com.ecifm.saml.bridge.tririga.generated.dto.Filter;
import com.ecifm.saml.bridge.tririga.generated.dto.IntegrationRecord;
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

    @Value("${tririga.people.project-name:}")
    private String peopleProjectName;

    @Value("${tririga.people.module-name:}")
    private String peopleModuleName;

    @Value("${tririga.people.object-type-name:}")
    private String peopleObjectTypeName;

    @Value("${tririga.people.group-section-name:triPeopleTXGroup}")
    private String peopleGroupSectionName;

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

    public ResponseHelperHeader saveRecord(ArrayOfIntegrationRecord records) {
        try {
            TririgaWSPortType port = createPort();
            ResponseHelperHeader result = port.saveRecord(records);
            log.info("saveRecord: anyFailed={}, total={}, successful={}, failed={}",
                result.isAnyFailed(), result.getTotal(), result.getSuccessful(), result.getFailed());
            return result;
        } catch (Exception e) {
            log.error("saveRecord failed: {}", e.getMessage(), e);
            return null;
        }
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
