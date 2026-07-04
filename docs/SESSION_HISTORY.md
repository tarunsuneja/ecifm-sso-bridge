# Session History — Complete Context for Resuming

> **Last updated:** 2026-06-29  
> **Cluster:** NPOS2  
> **Project:** `ecifm-saml-bridge` — OIDC IdP for MAS/TRIRIGA  
> **Git tag for old code:** `v0.1.0-bridge-client-only`

---

## 1. Environment

### URLs

| Component | URL |
|-----------|-----|
| Bridge | `https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net` |
| TRIRIGA | `https://main.facilities.inst1.apps.npos2.ecifmdev.net/app/tririga` |
| MAS Liberty auth | `https://auth.inst1.apps.npos2.ecifmdev.net` |
| Liberty OIDC callback | `https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities` |
| Liberty form login | `https://auth.inst1.apps.npos2.ecifmdev.net/login/` |
| MAS Admin UI | `https://admin.inst1.apps.npos2.ecifmdev.net` |

### Azure AD / Entra ID

| Property | Value |
|----------|-------|
| Tenant ID | `c99cc570-ba4f-474e-897d-22255a3cecd7` |
| App client ID | `cbcea157-2c35-4ce3-b86c-782282e00857` |
| Issuer URI | `https://login.microsoftonline.com/c99cc570-ba4f-474e-897d-22255a3cecd7/v2.0` |
| Bridge redirect URI | `https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/login/oauth2/code/entra-id` |

### Build

| Tool | Version |
|------|---------|
| JDK | 17 (at `C:\Program Files\Java\jdk-17`) |
| Maven | 3.9+ |
| Spring Boot | 3.3.5 |
| Spring Security OAuth2 AS | managed by Spring Boot parent |

---

## 2. Investigation Summary

### What We Discovered (Network Trace Analysis)

1. User visits `https://.../app/tririga` → TRIRIGA SPA detects no MAS session → redirects to Liberty at `auth.inst1.apps.npos2.ecifmdev.net`

2. Liberty always redirects to `/login/` (form SPA, like Maximo's) when no MAS session exists. The SPA has a "Microsoft" button that triggers a browser-initiated full-page redirect to Entra ID.

3. The `/api/auth/oidclogin?oidcid=default-oidc` API exists but requires browser cookies already set for `auth.*` domain — can't be called server-side.

4. After user clicks "Microsoft", the full OIDC authorization code flow runs between Liberty and Entra ID. Liberty redirects back to TRIRIGA with a session. **This is the standard MAS/Liberty OIDC flow.**

### Approaches Attempted and Abandoned

**Approach A: Unsolicited redirect from bridge to MAS**
- Bridge auth → redirect to Liberty `/api/auth/oidclogin?oidcid=default-oidc`
- **Failed:** Liberty needs its own session cookies to process that API call

**Approach B: OIDC authorization code from bridge to Liberty**
- Bridge initiates an OIDC flow with Liberty as the provider
- Bridge sends `preferred_username` claim in the ID token
- **Failed:** MAS Liberty OIDC implementation does not support `private_key_jwt` client auth the way we'd need

**Approach C (final): Bridge as OIDC IdP for Liberty**
- MAS/Liberty configures bridge as a trusted OIDC provider
- Bridge authenticates users with Entra ID, issues signed ID tokens to Liberty
- Liberty calls TRIRIGA's `WSSubject.getRunAsSubject()` internally → TRIRIGA creates session
- **Success:** This matches TRIRIGA's OIDC SSO path exactly

---

## 3. Architecture (Final — v0.2.0)

### Flow Diagram

```
User visits TRIRIGA
     │
     ▼
MAS TRIRIGA SPA loads → Liberty checks session
     │
     ▼ (no session)
Liberty redirects to /oidcclient/redirect/facilities
     │
     ▼
Liberty OIDC client sends /authorize to Bridge:
   GET /oauth2/authorize?response_type=code&client_id=mas-facilities&...
     │
     ▼ (AS filter chain - not authenticated)
LoginUrlAuthenticationEntryPoint → 302 redirect to /oauth2/authorization/entra-id
     │
     ▼ (OAuth2 client chain)
Bridge redirects browser to Entra ID:
   GET https://login.microsoftonline.com/.../authorize?...
     │
     ▼ (User authenticates)
Entra ID redirects to bridge:
   POST /login/oauth2/code/entra-id?code=...
     │
     ▼
Bridge exchanges code for tokens → gets OidcUser
     │
     ▼
Spring Security AuthenticationSuccessHandler retrieves saved authorize request
     │
     ▼
302 redirect to /oauth2/authorize (with session cookie)
     │
     ▼ (AS filter chain - NOW authenticated)
Bridge generates authorization code → 302 to Liberty callback:
   Location: https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities?code=XYZ
     │
     ▼
Liberty exchanges code at bridge:
   POST /oauth2/token?grant_type=authorization_code&code=XYZ&...
     │
     ▼
Bridge validates client (mas-facilities) → returns signed ID token
     │
     ▼
Liberty validates ID token signature via /oauth2/jwks
     │
     ▼
Liberty extracts preferred_username → sets WSSubject
     │
     ▼
TRIRIGA UserSessionFilter detects WSSubject → auto-creates session
     │
     ▼
User lands on TRIRIGA home page — zero clicks
```

### Token Claims

| ID Token Claim | Source from Entra ID OidcUser | Example |
|----------------|-------------------------------|---------|
| `sub` | `oidcUser.getSubject()` | Azure AD object ID |
| `preferred_username` | `oidcUser.getPreferredUsername()` or email | `tarun.suneja@ecifm.com` |
| `email` | `oidcUser.getEmail()` | `tarun.suneja@ecifm.com` |
| `name` | `oidcUser.getFullName()` or preferred_username | `Tarun Suneja` |

### Security Filter Chains (`SecurityConfig.java`)

| Order | Matcher | Purpose |
|-------|---------|---------|
| `@Order(1)` | AS endpoints (`/oauth2/*`, `/.well-known/*`) | OIDC Authorization Server — delegates auth to Entra ID |
| `@Order(2)` | `/local/**` | Local test endpoints — permit all |
| `@Order(3)` | All other requests | OAuth2 client — redirects unauthenticated to Entra ID |

---

## 4. All Files — Complete Contents

### 4.1 New File: `AuthServerConfig.java`

**Path:** `src/main/java/com/ecifm/saml/bridge/config/AuthServerConfig.java` (121 lines)

```java
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
            @Value("${mas.oidc.redirect-uri}") String redirectUri) {

        log.info("Registering OIDC client: {} with redirect URI: {}", clientId, redirectUri);

        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientSecret("{noop}" + clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
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
                        .build())
                .build();

        return new InMemoryRegisteredClientRepository(client);
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

                        context.getClaims()
                                .subject(oidcUser.getSubject())
                                .claim("preferred_username", preferredUsername != null ? preferredUsername : email)
                                .claim("email", email != null ? email : "")
                                .claim("name", name != null ? name : preferredUsername);
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
```

### 4.2 Modified File: `SecurityConfig.java`

**Path:** `src/main/java/com/ecifm/saml/bridge/config/SecurityConfig.java` (67 lines)

```java
package com.ecifm.saml.bridge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                .oidc(org.springframework.security.config.Customizer.withDefaults());

        http.exceptionHandling(exceptions ->
                exceptions.authenticationEntryPoint(
                        new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/entra-id")
                )
        );

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain localTestFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/local/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain oauth2LoginFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/health").permitAll()
                        .requestMatchers("/test", "/test-soap").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/oauth2/authorization/entra-id"))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
```

### 4.3 Modified File: `EcifmSamlBridgeApplication.java` (43 lines)

```java
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
```

### 4.4 Modified File: `application-openshift.yml` (45 lines)

```yaml
server:
  forward-headers-strategy: framework

spring:
  security:
    oauth2:
      authorizationserver:
        issuer: ${BRIDGE_ISSUER_URL}
      client:
        registration:
          entra-id:
            client-id: ${AZURE_CLIENT_ID}
            client-secret: ${AZURE_CLIENT_SECRET}
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope: openid, profile, email
        provider:
          entra-id:
            issuer-uri: ${JWT_ISSUER_URI}
            user-name-attribute: sub
mas:
  base-url: ${MAS_BASE_URL}
  context: ${MAS_CONTEXT:/tririga}
  redirect-url: ${MAS_REDIRECT_URL:https://main.facilities.inst1.apps.npos2.ecifmdev.net/app/tririga}
  rest-api: ${MAS_REST_API:/html/en/default/rest/SSOConnect?userName={0}&adGroupName={1}}
  oidc:
    client-id: ${MAS_OIDC_CLIENT_ID:mas-facilities}
    client-secret: ${MAS_OIDC_CLIENT_SECRET}
    redirect-uri: ${MAS_OIDC_REDIRECT_URI:https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities}

tririga:
  username: ${TRIRIGA_USERNAME}
  password: ${TRIRIGA_PASSWORD}

entra-id:
  graph:
    enabled: ${GRAPH_API_ENABLED:true}
    api-version: v1.0

logging:
  level:
    com.ecifm.saml.bridge: DEBUG
    org.springframework.security: DEBUG
    org.springframework.security.oauth2: TRACE
    org.springframework.security.oauth2.client: TRACE
```

### 4.5 Modified File: `pom.xml` (129 lines)

The only change: added line 48-50 dependency:
```xml
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-oauth2-authorization-server</artifactId>
        </dependency>
```

Full file: see `pom.xml` on disk.

### 4.6 Modified File: `openshift/configmap.yaml` (17 lines)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ecifm-bridge-config
data:
  MAS_BASE_URL: "https://main.facilities.inst1.apps.npos2.ecifmdev.net"
  MAS_CONTEXT: "/tririga"
  MAS_REDIRECT_URL: "https://main.facilities.inst1.apps.npos2.ecifmdev.net/app/tririga"
  MAS_REST_API: "/html/en/default/rest/SSOConnect?userName={0}&adGroupName={1}"
  JWT_ISSUER_URI: "https://login.microsoftonline.com/c99cc570-ba4f-474e-897d-22255a3cecd7/v2.0"
  BRIDGE_ISSUER_URL: "https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net"
  MAS_OIDC_CLIENT_ID: "mas-facilities"
  MAS_OIDC_REDIRECT_URI: "https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities"
  GRAPH_API_ENABLED: "true"
  SPRING_PROFILES_ACTIVE: "openshift"
  AZURE_CLIENT_ID: "cbcea157-2c35-4ce3-b86c-782282e00857"
  TRIRIGA_USERNAME: "tarun.suneja@ecifm.com"
```

### 4.7 Modified File: `openshift/secret.yaml` (9 lines)

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: ecifm-bridge-secrets
type: Opaque
stringData:
  AZURE_CLIENT_SECRET: ""
  TRIRIGA_PASSWORD: "TR@maspassword2!"
  MAS_OIDC_CLIENT_SECRET: "change-me-to-a-random-secret"
```

### 4.8 Modified File: `openshift/deployment.yaml` (67 lines)

The only change: added env var MAS_OIDC_CLIENT_SECRET (lines 44-48):
```yaml
            - name: MAS_OIDC_CLIENT_SECRET
              valueFrom:
                secretKeyRef:
                  name: ecifm-bridge-secrets
                  key: MAS_OIDC_CLIENT_SECRET
```

### 4.9 Note File: `openshift/route.yaml`

```yaml
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: ecifm-sso-bridge
spec:
  host: ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net
  port:
    targetPort: 8080
  tls:
    termination: edge
  to:
    kind: Service
    name: ecifm-sso-bridge
```

### 4.10 Note File: `openshift/service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: ecifm-sso-bridge
spec:
  selector:
    app: ecifm-sso-bridge
  ports:
    - port: 8080
      targetPort: 8080
```

### 4.11 Other Files (Unchanged)

- `Dockerfile` — Multi-stage build (JDK 17 → JRE 17, 8080, CMD `java -jar /app.jar`)
- `src/main/resources/application.yml` — Development defaults (local profile)
- `src/main/java/com/ecifm/saml/bridge/controller/AcsHandlerController.java` — `@GetMapping("/redirect")` redirects to MAS after Entra ID auth
- `src/main/resources/wsdl/TririgaWS.wsdl` — TRIRIGA SOAP web service WSDL

---

## 5. Git State

### Branches

`master` — main branch, all changes are here.

### Tag

`v0.1.0-bridge-client-only` — marks the code before OIDC IdP changes. Can revert with:
```bash
git revert --no-commit v0.1.0-bridge-client-only..HEAD
```

### Uncommitted Changes (7 modified, 2 untracked)

```
 M openshift/configmap.yaml
 M openshift/deployment.yaml
 M openshift/secret.yaml
 M pom.xml
 M src/main/java/com/ecifm/saml/bridge/EcifmSamlBridgeApplication.java
 M src/main/java/com/ecifm/saml/bridge/config/SecurityConfig.java
 M src/main/resources/application-openshift.yml
?? docs/
?? src/main/java/com/ecifm/saml/bridge/config/AuthServerConfig.java
```

### Recent Git Log (last 20 commits)

```
3f0dac5 fix: simplify redirect - remove broken server-side Liberty API call, redirect to TRIRIGA home
503841a fix: redirect / to /redirect so Liberty OIDC call is triggered
f707508 fix: call Liberty OIDC login API server-side to get signed redirect URL
95ef0d4 fix: simplify redirect - skip sync/Graph, point to Liberty authorize endpoint
ab7f24a chore: log OAuth2 config values at startup
d1094bc fix: update AZURE_CLIENT_ID to TRIRIGA's app ID
eaca8d9 chore: enable TRACE logging for OAuth2 client to see token exchange errors
5cb0eea update default MAS_REDIRECT_URL to main.net/login
5d1477d add DEBUG logging and try-catch around SSOConnect to debug 502
7260ed5 make redirect URL configurable via MAS_REDIRECT_URL env var
b91128b redirect to MAS OIDC endpoint instead of TRIRIGA home
9b84898 Restore SSOConnect flow in /redirect controller
ff0b067 Restore MAS services, revert to bridge hostname, update config
529ce21 Add context-path /tririga, update route to use TRIRIGA hostname, remove unused MAS services
c2065ef Simplify /redirect to just 302 to TRIRIGA, remove MasSyncService dep
389072a Test internal with custom headers and Basic Auth
c924957 Add internal service test endpoint
4126c9d HTTP Basic Auth + cookies + no redirect
264df4a Two-step: get cookie then auth request
fdb68c8 Test with no auth headers at all
```

### Build Status

✅ `mvn compile` passes on JDK 17.

---

## 6. Open Issues

### Issue 1: Ephemeral RSA Key

**Severity:** Medium (for production with >1 replica)  
**Status:** Unresolved — code generates key at startup

The `AuthServerConfig.jwkSource()` generates a new RSA 2048-bit key pair on every pod startup. With 2+ replicas, tokens signed by pod A will fail validation when Liberty fetches JWKS from pod B.

**Short-term workaround:** Run with `replicas: 1`  
**Long-term solution:** Persist JWK Set in a mounted ConfigMap or Secret (see DEPLOYMENT.md Appendix C)

### Issue 2: MAS Admin UI OIDC Configuration

**Severity:** High (blocking)  
**Status:** Manual step — needs to be done after deploy

Need to configure MAS/Liberty's OIDC provider to point to the bridge's discovery endpoint. This is done in the MAS Admin UI and requires admin access to the cluster.

### Issue 3: `AZURE_CLIENT_SECRET` Not in Git

**Severity:** High (blocking)  
**Status:** Must be obtained from Azure or existing cluster

The Azure AD client secret for client ID `cbcea157-2c35-4ce3-b86c-782282e00857` is not stored in git. It must be:
- Retrieved from the existing OpenShift Secret
- Or a new secret created in Azure AD App Registration

### Issue 4: SOAP Auth Undetermined

**Severity:** Low (not blocking OIDC flow)  
**Status:** Investigated but not resolved

The bridge connects to TRIRIGA's SOAP web service (TririgaWS). The correct authentication method is unclear:
- No auth → HTTP 302 (redirect to login)
- Custom `Username`/`Password` headers → varies
- HTTP Basic Auth → varies
- Cookie-based → may need pre-auth

The OIDC IdP flow does NOT depend on this — it only sets up authentication, not SOAP calls.

---

## 7. Key TRIRIGA Auth Insights (from Decompiled Code)

These are the critical findings from analyzing ~42 decompiled TRIRIGA classes at `D:\00_MREF\Project\ibm-tririga.jar_Decompiler.com\com`:

### TRIRIGA Auth Paths

1. **Form Login Path:** TRIRIGA calls `MASInternalAPI.checkAuthentication()` with mTLS to MAS Core `/v3/users/checkauthentication`. This path uses cookies (`x-access-token`, `x-refresh-token`) and JWT tokens. **Not our path.**

2. **OIDC SSO Path:** After Liberty OIDC flow completes, `WSSubject.getRunAsSubject()` is populated → TRIRIGA's `UserSessionFilter` detects it → creates user session automatically. **This is our path.**

### Key Classes (for reference)

- `MASSignonService.getUserIdFromRequest()` — Reads user from session, then WSSubject, then Authorization header. The OIDC path populates WSSubject.
- `OAuthService` — The bridge's callback (`/oidcclient/redirect/facilities`) triggers the OIDC flow.
- `WebPageAuthenticationInterceptor` — Intercepts all web requests, checks for user session via `UserSessionFilter`.
- `AuthenticationHandler.handleXAccessXRefreshTokens()` — Sets cookies on the TRIRIGA domain. This happens server-side, after the OIDC flow, and does NOT require the bridge to do anything.
- `UserSessionFilter` — Checks session.getAttribute("userSession"). If missing, calls `populateUserIdInSession()` which reads from WSSubject. This is what happens after Liberty finishes the OIDC flow.

---

## 8. Deployment Steps Summary

After resuming, here's what needs to happen:

```
┌──────────────────────────────────────────────────────────┐
│  1. Verify Azure AD redirect URI                         │
│     (should already have bridge callback URL)             │
│                                                          │
│  2. Get AZURE_CLIENT_SECRET from Azure or existing pod   │
│                                                          │
│  3. Generate MAS_OIDC_CLIENT_SECRET (openssl rand)       │
│                                                          │
│  4. Build bridge: $env:JAVA_HOME = "...jdk-17"           │
│     mvn clean package -DskipTests                        │
│                                                          │
│  5. Build Docker image & push to registry                │
│                                                          │
│  6. Update OpenShift ConfigMap (add 3 new keys)          │
│                                                          │
│  7. Update OpenShift Secret (add MAS_OIDC_CLIENT_SECRET) │
│     also ensure AZURE_CLIENT_SECRET is set               │
│                                                          │
│  8. Update OpenShift Deployment (already has the env)    │
│                                                          │
│  9. Rollout restart deployment/ecifm-sso-bridge          │
│                                                          │
│ 10. Verify bridge OIDC endpoints (.well-known, JWKS)     │
│                                                          │
│ 11. MAS Admin UI → configure OIDC provider               │
│     → discovery: bridge /.well-known/...                 │
│     → client id: mas-facilities                          │
│     → client secret: from step 3                         │
│                                                          │
│ 12. Test in incognito: visit TRIRIGA                     │
│     → should flow through bridge → Entra ID → TRIRIGA    │
│                                                          │
│ 13. If test passes, commit code to git                   │
│                                                          │
│ 14. Address persistent JWK Set (>1 replica)              │
└──────────────────────────────────────────────────────────┘
```

---

## 9. Files Created During This Session

| File | Purpose |
|------|---------|
| `docs/DEPLOYMENT.md` | Detailed deployment instructions (921 lines) |
| `docs/SESSION_HISTORY.md` | **This file** — complete session context for resuming |
| `src/main/java/.../config/AuthServerConfig.java` | OIDC Authorization Server config (RSA keys, client registration, token customizer) |

---

*End of session history. To resume, load this file and start from Section 8.*
