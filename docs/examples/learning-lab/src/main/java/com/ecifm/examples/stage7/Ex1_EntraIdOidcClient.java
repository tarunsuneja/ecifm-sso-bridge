package com.ecifm.examples.stage7;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * ─────────────────────────────────────────────────────────────────
 * STAGE 7: Entra ID & Microsoft Graph API
 * Exercise 1: OAuth2 Login with Entra ID
 * ─────────────────────────────────────────────────────────────────
 *
 * Goal: Configure Spring Security to use Entra ID as the OIDC
 *       provider (same as the bridge does).
 *
 * Prerequisites:
 *   1. Azure AD App Registration with:
 *      - Redirect URI: http://localhost:8080/login/oauth2/code/entra-id
 *      - ID tokens enabled
 *      - groupMembershipClaims: SecurityGroup
 *   2. Fill in your-tenant-id, your-client-id, your-client-secret
 *      in application.yml
 *
 * Key points:
 *  - spring-security-oauth2-client handles the OIDC dance
 *  - .oauth2Login() redirects unauthenticated users to Entra ID
 *  - After login, the id_token claims are available in the
 *    OidcUser object
 *  - Group claims (>200 groups) might need Graph API fallback
 *
 * Try it:
 *   mvn spring-boot:run
 *   Open http://localhost:8080/entra/profile
 *   → Redirects to Microsoft login
 *   → After login, shows your claims
 */
@RestController
class EntraProfileController {

    @GetMapping("/entra/profile")
    public java.util.Map<String, Object> profile(
            org.springframework.security.core.annotation.AuthenticationPrincipal
                org.springframework.security.oauth2.core.oidc.user.OidcUser user) {

        // The OidcUser contains ALL claims from the id_token
        return java.util.Map.of(
            "sub", user.getSubject(),
            "preferred_username", user.getPreferredUsername(),
            "email", user.getEmail(),
            "name", user.getFullName(),
            "issuer", user.getIssuer().toString(),
            "claims", user.getClaims()
        );
    }

    @GetMapping("/entra/groups")
    public String groups(
            org.springframework.security.core.annotation.AuthenticationPrincipal
                org.springframework.security.oauth2.core.oidc.user.OidcUser user) {

        @SuppressWarnings("unchecked")
        var groups = (java.util.List<String>) user.getClaims().get("groups");

        if (groups == null) return "No groups claim found in token";

        // Check for group overage (>200 groups)
        if (user.getClaims().containsKey("_claim_names")) {
            return String.format("""
                You have MORE than 200 groups (overage indicator detected).
                First %d groups shown from token. Use Graph API to resolve all.
                """, groups.size());
        }

        return String.format("You have %d group(s):%n%s",
            groups.size(), String.join(", ", groups));
    }
}

@Configuration
@EnableWebSecurity
class EntraSecurityConfig {

    @Bean
    public SecurityFilterChain entraChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/entra/**").authenticated()
                .anyRequest().permitAll()
            )
            .oauth2Login(c -> c
                .loginPage("/oauth2/authorization/entra-id")
            )
            .csrf(c -> c.disable());
        return http.build();
    }

    /*
     * Registers Entra ID as an OIDC provider.
     *
     * The issuer-uri tells Spring to auto-discover the OIDC
     * endpoints from: https://login.microsoftonline.com/{tenant}/v2.0/.well-known/openid-configuration
     *
     * When this runs:
     *   GET /.well-known/openid-configuration?appid={clientId}
     *   → Returns authorization_endpoint, token_endpoint, jwks_uri, etc.
     *
     * This is how Spring knows where to redirect users and
     * where to exchange codes for tokens.
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        // In production, read from application.yml:
        // @Value("${azure.activedirectory.tenant-id}")
        // @Value("${azure.activedirectory.client-id}")
        // @Value("${azure.activedirectory.client-secret}")
        String tenantId = "${azure.activedirectory.tenant-id}";
        String clientId = "${azure.activedirectory.client-id}";
        String clientSecret = "${azure.activedirectory.client-secret}";

        var registration = ClientRegistration.withRegistrationId("entra-id")
            .clientId(clientId)
            .clientSecret(clientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "profile", "email", "GroupMember.Read.All")
            .authorizationUri("https://login.microsoftonline.com/%s/oauth2/v2.0/authorize".formatted(tenantId))
            .tokenUri("https://login.microsoftonline.com/%s/oauth2/v2.0/token".formatted(tenantId))
            .jwkSetUri("https://login.microsoftonline.com/%s/discovery/v2.0/keys".formatted(tenantId))
            .userNameAttributeName(IdTokenClaimNames.SUB)
            .clientName("Entra ID")
            .build();

        return new InMemoryClientRegistrationRepository(registration);
    }
}
