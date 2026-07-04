package com.ecifm.saml.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class EcifmSamlBridgeApplication {

    private static final Logger log = LoggerFactory.getLogger(EcifmSamlBridgeApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(EcifmSamlBridgeApplication.class, args);
    }

    @Bean
    ApplicationRunner logConfig(
            @Value("${spring.security.oauth2.client.registration.entra-id.client-id}") String clientId,
            @Value("${spring.security.oauth2.client.registration.entra-id.client-secret}") String clientSecret,
            @Value("${spring.security.oauth2.client.provider.entra-id.issuer-uri}") String issuerUri,
            @Value("${mas.redirect-url}") String masRedirectUrl,
            @Value("${mas.base-url}") String masBaseUrl,
            @Value("${spring.security.oauth2.authorizationserver.issuer}") String bridgeIssuer,
            @Value("${mas.oidc.client-id}") String masOidcClientId,
            @Value("${mas.oidc.redirect-uri}") String masOidcRedirectUri) {
        return args -> {
            log.info("=== OAuth2 Configuration ===");
            log.info("AZURE_CLIENT_ID: {}", clientId);
            log.info("AZURE_CLIENT_SECRET length: {}", clientSecret != null ? clientSecret.length() : 0);
            log.info("JWT_ISSUER_URI: {}", issuerUri);
            log.info("BRIDGE_ISSUER_URL: {}", bridgeIssuer);
            log.info("MAS_OIDC_CLIENT_ID: {}", masOidcClientId);
            log.info("MAS_OIDC_REDIRECT_URI: {}", masOidcRedirectUri);
            log.info("MAS_REDIRECT_URL: {}", masRedirectUrl);
            log.info("MAS_BASE_URL: {}", masBaseUrl);
            log.info("=============================");
        };
    }
}
