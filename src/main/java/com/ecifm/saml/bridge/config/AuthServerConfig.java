package com.ecifm.saml.bridge.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

@Configuration
public class AuthServerConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthServerConfig.class);

    @Bean
    public RegisteredClientRepository registeredClientRepository(
            @Value("${mas.oidc.client-id}") String clientId,
            @Value("${mas.oidc.client-secret}") String clientSecret,
            @Value("${mas.oidc.redirect-uri}") String tririgaRedirectUri,
            @Value("${mas.oauth.client-redirect-uri:}") String oauthClientRedirectUri) {

        log.info("Registering OIDC client: {} with TRIRIGA redirect: {} and OAuth client redirect: {}",
                clientId, tririgaRedirectUri, oauthClientRedirectUri);

        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientSecret("{noop}" + clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .redirectUri(tririgaRedirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
                        .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .build());

        if (oauthClientRedirectUri != null && !oauthClientRedirectUri.isBlank()) {
            builder.redirectUri(oauthClientRedirectUri);
        }

        return new InMemoryRegisteredClientRepository(builder.build());
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        log.info("Generating RSA key pair for OIDC token signing");
        KeyPair keyPair = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .algorithm(JWSAlgorithm.RS256)
                .build();

        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            if ("id_token".equals(context.getTokenType().getValue())) {
                var principal = context.getPrincipal();
                if (principal instanceof org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken authToken) {
                    Object userObj = authToken.getPrincipal();
                    if (userObj instanceof OidcUser oidcUser) {
                        String email = oidcUser.getEmail();
                        String preferredUsername = oidcUser.getPreferredUsername();
                        String name = oidcUser.getFullName();
                        String sub = email != null ? email : preferredUsername;

                        context.getClaims()
                                .subject(sub)
                                .claim("preferred_username", preferredUsername != null ? preferredUsername : email)
                                .claim("email", email != null ? email : "")
                                .claim("name", name != null ? name : preferredUsername)
                                .claim("uniqueSecurityName", sub);
                    }
                }
            }
        };
    }

    private static KeyPair generateRsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RSA key pair", e);
        }
    }
}
