package com.ecifm.saml.bridge.service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.namespace.QName;

import org.apache.cxf.binding.soap.SoapHeader;
import org.apache.cxf.configuration.jsse.TLSClientParameters;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.headers.Header;
import org.apache.cxf.jaxb.JAXBDataBinding;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ecifm.saml.bridge.tririga.generated.dto.ApplicationInfo;
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

        Document doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element challenge = doc.createElementNS("http://soap-authentication.org/basic/2001/10/", "h:BasicChallenge");
        challenge.setAttributeNS("http://schemas.xmlsoap.org/soap/envelope/", "soap:mustUnderstand", "1");
        Element userName = doc.createElement("UserName");
        userName.setTextContent(tririgaUsername.trim());
        challenge.appendChild(userName);
        Element password = doc.createElement("Password");
        password.setTextContent(tririgaPassword);
        challenge.appendChild(password);
        List<Header> headerList = List.of(new SoapHeader(
                new QName("http://soap-authentication.org/basic/2001/10/", "BasicChallenge", "h"), challenge));
        bp.getRequestContext().put(Header.HEADER_LIST, headerList);
        log.info("Added SOAP BasicChallenge header for user '{}'", tririgaUsername);

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

        log.info("CXF client configured with SOAP BasicChallenge for user '{}'", tririgaUsername);
        return port;
    }

    private static String value(jakarta.xml.bind.JAXBElement<String> element) {
        return element == null ? "" : element.getValue();
    }
}
