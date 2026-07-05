package com.ecifm.saml.bridge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LibertySessionClient {

    private static final Logger log = LoggerFactory.getLogger(LibertySessionClient.class);

    private final TririgaWsClient tririgaWsClient;

    public LibertySessionClient(TririgaWsClient tririgaWsClient) {
        this.tririgaWsClient = tririgaWsClient;
    }

    public String getSessionId(String email) {
        log.info("Getting Liberty session ID for user: {} via SOAP HTTP Basic", email);
        String jsessionId = tririgaWsClient.getAuthenticatedSessionId();
        if (jsessionId != null) {
            log.info("Obtained JSESSIONID for {}: {}", email, jsessionId);
        } else {
            log.warn("Failed to obtain JSESSIONID for {}", email);
        }
        return jsessionId;
    }
}
