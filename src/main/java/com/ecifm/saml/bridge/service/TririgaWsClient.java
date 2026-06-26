package com.ecifm.saml.bridge.service;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class TririgaWsClient {

    private static final Logger log = LoggerFactory.getLogger(TririgaWsClient.class);

    private static final String SOAP_ACTION = "urn:getApplicationInfo";

    private static final String GET_APP_INFO_ENVELOPE =
            "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:ws=\"http://ws.tririga.com\">"
          + "  <soap:Header/>"
          + "  <soap:Body>"
          + "    <ws:getApplicationInfo/>"
          + "  </soap:Body>"
          + "</soap:Envelope>";

    @Value("${mas.base-url}")
    private String masBaseUrl;

    @Value("${mas.context}")
    private String masContext;

    private final RestTemplate restTemplate;

    public TririgaWsClient(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
        disableSslVerification();
    }

    public String getApplicationInfo(String bearerToken) {
        String soapUrl = masBaseUrl + masContext + "/ws/TririgaWS?wsdl";
        log.info("Calling TririgaWS SOAP getApplicationInfo at: {}", soapUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/soap+xml"));
        headers.setBearerAuth(bearerToken);

        HttpEntity<String> request = new HttpEntity<>(GET_APP_INFO_ENVELOPE, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(soapUrl, request, String.class);
            log.info("SOAP response status: {}", response.getStatusCode());
            log.info("SOAP response body: {}", response.getBody());
            return response.getBody();
        } catch (Exception e) {
            log.error("TririgaWS SOAP call failed: {}", e.getMessage(), e);
            return "SOAP call failed: " + e.getMessage();
        }
    }

    private static void disableSslVerification() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            log.warn("SSL verification disabled for TririgaWS SOAP calls");
        } catch (Exception e) {
            throw new RuntimeException("Failed to disable SSL verification", e);
        }
    }
}
