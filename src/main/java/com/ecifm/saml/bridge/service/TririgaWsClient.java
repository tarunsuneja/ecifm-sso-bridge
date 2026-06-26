package com.ecifm.saml.bridge.service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.cxf.configuration.jsse.TLSClientParameters;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ecifm.saml.bridge.tririga.generated.dto.ApplicationInfo;
import com.ecifm.saml.bridge.tririga.generated.ws.TririgaWS;
import com.ecifm.saml.bridge.tririga.generated.ws.TririgaWSPortType;

import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.handler.MessageContext;

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
        try {
            TririgaWSPortType port = createPort();
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

    public String getHttpSession() {
        try {
            TririgaWSPortType port = createPort();
            var session = port.getHttpSession();
            log.info("getHttpSession succeeded");
            return "Success:\n"
                    + "  Session ID: " + session.getId();
        } catch (Exception e) {
            log.warn("getHttpSession failed: {}", e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private TririgaWSPortType createPort() throws Exception {
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

        Client client = ClientProxy.getClient(port);
        HTTPConduit conduit = (HTTPConduit) client.getConduit();

        HTTPClientPolicy policy = new HTTPClientPolicy();
        policy.setConnectionTimeout(30_000);
        policy.setReceiveTimeout(120_000);
        policy.setAllowChunking(false);
        conduit.setClient(policy);
        org.apache.cxf.configuration.security.AuthorizationPolicy auth =
                new org.apache.cxf.configuration.security.AuthorizationPolicy();
        auth.setUserName(tririgaUsername.trim());
        auth.setPassword(tririgaPassword);
        conduit.setAuthorization(auth);

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

        log.info("CXF client configured with Username/Password for user '{}'", tririgaUsername);
        return port;
    }

    private static String value(jakarta.xml.bind.JAXBElement<String> element) {
        return element == null ? "" : element.getValue();
    }
}
