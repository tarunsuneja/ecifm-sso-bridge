package com.ecifm.examples.stage3;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/*
 * ─────────────────────────────────────────────────────────────────
 * STAGE 3: OAuth2 & OIDC
 * Exercise 1: Build an Authorization Server (AS)
 * ─────────────────────────────────────────────────────────────────
 *
 * Goal: Create a working OAuth2 Authorization Server that:
 *   - Issues JWT tokens via authorization_code flow
 *   - Has one registered client (mas-facilities equivalent)
 *   - Signs tokens with RSA key pair (RS256)
 *   - Exposes JWKS and .well-known/openid-configuration
 *
 * This is a COMPLETE example — you can run it and test.
 *
 * Key points:
 *  - OAuth2AuthorizationServerConfiguration sets up the core AS
 *  - RegisteredClient defines who can request tokens
 *  - JWKSource holds the signing key pair
 *  - TokenSettings controls token lifetimes
 *  - The user must authenticate (form login) before AS issues code
 *
 * Test the flow:
 *  1. Open in browser:
 *     http://localhost:8080/oauth2/authorize?response_type=code
 *       &client_id=mas-facilities&redirect_uri=http://localhost:8080/callback
 *       &scope=openid+profile&state=xyz
 *
 *  2. Login with user/userpass (created below)
 *
 *  3. After consent, browser redirects to /callback?code=XXXXX&state=xyz
 *
 *  4. Exchange code for token:
 *     curl -X POST http://localhost:8080/oauth2/token \
 *       -u mas-facilities:secret \
 *       -d "grant_type=authorization_code&code=XXXXX&redirect_uri=http://localhost:8080/callback"
 *
 *  5. Decode the id_token at https://jwt.io
 */
@RestController
class AsInfoController {

    @GetMapping("/")
    public String home() {
        return """
            <h2>Learning Lab OAuth2 AS</h2>
            <p>JWKS: <a href='/oauth2/jwks'>/oauth2/jwks</a></p>
            <p>OIDC Config: <a href='/.well-known/openid-configuration'>/.well-known/openid-configuration</a></p>
            <p><a href='/oauth2/authorize?response_type=code&client_id=mas-facilities&redirect_uri=http://localhost:8080/callback&scope=openid+profile&state=xyz'>
               Start authorization code flow</a></p>
            """;
    }
}

@Configuration
@EnableWebSecurity
class AuthorizationServerConfig {

    /*
     * Chain 1 (@Order 1): OAuth2 Authorization Server endpoints
     *   - /oauth2/authorize, /oauth2/token, /oauth2/jwks
     *   - .well-known/openid-configuration
     *   - Uses OAuth2AuthorizationServerConfiguration defaults
     */
    @Bean
    @Order(1)
    public SecurityFilterChain asChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        return http.build();
    }

    /*
     * Chain 2 (default): All other URLs
     *   - The /login page for user authentication
     *   - The /callback redirect receiver
     */
    @Bean
    public SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .formLogin(c -> {})
            .csrf(c -> c.disable());
        return http.build();
    }

    /*
     * The registered client — like the bridge's "mas-facilities" client.
     * - client_id + client_secret authenticate the client app
     * - redirect_uri is where the AS sends the auth code
     * - authorization_code is the user-facing flow
     * - PKCE is disabled for simplicity (enable in production)
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository() {
        var client = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId("mas-facilities")
            .clientSecret("{noop}secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .redirectUri("http://localhost:8080/callback")
            .scope("openid")
            .scope("profile")
            .scope("email")
            .tokenSettings(TokenSettings.builder()
                .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                .accessTokenTimeToLive(Duration.ofMinutes(15))
                .build())
            .clientSettings(ClientSettings.builder()
                .requireAuthorizationConsent(true)
                .build())
            .build();
        return new InMemoryRegisteredClientRepository(client);
    }

    /*
     * RSA key pair — generated at startup.
     * WARNING: In production with 2+ replicas, each pod generates
     * its own key pair, so tokens signed by pod A won't validate
     * on pod B. Fix: store JWK Set in a ConfigMap or database.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();

        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        RSAPrivateKey priv = (RSAPrivateKey) kp.getPrivate();

        RSAKey rsaKey = new RSAKey.Builder(pub)
            .privateKey(priv)
            .keyID(UUID.randomUUID().toString())
            .build();

        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    /*
     * Needed for token decoding (used internally by the AS).
     */
    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /*
     * The AS issuer URL — must match what clients expect.
     */
    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
            .issuer("http://localhost:8080")
            .build();
    }

    /*
     * Dummy user for the login form — users must authenticate
     * BEFORE the AS issues an authorization code.
     * This simulates Entra ID authentication in the real bridge.
     */
    @Bean
    public UserDetailsService users() {
        var user = User.withUsername("user")
            .password("{noop}userpass")
            .roles("USER")
            .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return NoOpPasswordEncoder.getInstance();
    }
}
