# ecifm-saml-bridge — Java Code Reference

> **Version:** 0.2.0 — OIDC Identity Provider (IdP) architecture  
> **Date:** 2026-07-04  
> **Language:** Java 17  
> **Framework:** Spring Boot 3.3.5 + Spring Security 6.x

---

## Table of Contents

1. [Project Structure](#1-project-structure)
2. [Package: `com.ecifm.saml.bridge`](#2-package-com-ecifmsamlbridge)
3. [Package: `com.ecifm.saml.bridge.config`](#3-package-com-ecifmsamlbridgeconfig)
4. [Package: `com.ecifm.saml.bridge.controller`](#4-package-com-ecifmsamlbridgecontroller)
5. [Package: `com.ecifm.saml.bridge.service`](#5-package-com-ecifmsamlbridgeservice)
6. [Package: `com.ecifm.saml.bridge.model`](#6-package-com-ecifmsamlbridgemodel)
7. [Dependency Summary](#7-dependency-summary)
8. [Environment Variable Wiring](#8-environment-variable-wiring)
9. [Complete Authentication Flows](#9-complete-authentication-flows)

---

## 1. Project Structure

```
src/main/java/com/ecifm/saml/bridge/
├── EcifmSamlBridgeApplication.java     # Spring Boot entry point
├── config/
│   ├── AuthServerConfig.java           # OIDC Authorization Server (IdP core)
│   ├── SecurityConfig.java             # 3 security filter chains
│   └── CxfConfig.java                  # Apache CXF HTTP transport
├── controller/
│   ├── AcsHandlerController.java       # Main SSO handler + SOAP tests
│   └── LocalMockController.java        # Local mock endpoints
├── model/
│   └── UserInfo.java                   # Simple user POJO
└── service/
    ├── MasSyncService.java             # Group sync orchestrator
    ├── MasApiClient.java               # MAS SSOConnect REST client
    ├── EntraIdGroupResolver.java       # Microsoft Graph API group resolver
    └── TririgaWsClient.java            # TRIRIGA SOAP client (CXF)
```

**Build-time generated** (by `cxf-codegen-plugin`):
```
target/generated-sources/cxf/com/ecifm/saml/bridge/tririga/generated/
    ws/TririgaWS.java              # JAX-WS service class
    ws/TririgaWSPortType.java      # JAX-WS port interface
    dto/ApplicationInfo.java       # SOAP DTO
    ...
```

---

## 2. Package: `com.ecifm.saml.bridge`

### `EcifmSamlBridgeApplication.java`

**Role:** Spring Boot entry point — the `main()` method that starts the application.

```java
@SpringBootApplication
public class EcifmSamlBridgeApplication {

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

**Key behavior:**
- `@SpringBootApplication` enables auto-configuration, component scanning, and property binding
- The `ApplicationRunner` runs after the application context is fully initialized
- All config values are logged at startup so you can verify environment variable injection at a glance
- The client secret length is logged (not the value) to avoid leaking secrets in logs

---

## 3. Package: `com.ecifm.saml.bridge.config`

### `AuthServerConfig.java`

**Role:** Configures the OIDC Authorization Server — the core of the v0.2.0 re-architecture. This class turns the bridge into an OIDC Identity Provider that Liberty/MAS trusts.

**File:** `src/main/java/com/ecifm/saml/bridge/config/AuthServerConfig.java` (126 lines)

#### 3.1 `registeredClientRepository()` — Client Registration

```java
@Bean
public RegisteredClientRepository registeredClientRepository(
        @Value("${mas.oidc.client-id}") String clientId,
        @Value("${mas.oidc.client-secret}") String clientSecret,
        @Value("${mas.oidc.redirect-uri}") String tririgaRedirectUri,
        @Value("${mas.oauth.client-redirect-uri:}") String oauthClientRedirectUri) {

    log.info("Registering OIDC client: {} with TRIRIGA redirect: {} and OAuth client redirect: {}",
            clientId, tririgaRedirectUri, oauthClientRedirectUri);

    RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(clientId)                          // "mas-facilities"
            .clientSecret("{noop}" + clientSecret)       // plain-text prefix
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(tririgaRedirectUri)              // TRIRIGA callback
            .scope(OidcScopes.OPENID)
            .scope(OidcScopes.PROFILE)
            .scope(OidcScopes.EMAIL)
            .clientSettings(ClientSettings.builder()
                    .requireAuthorizationConsent(false)   // No consent screen
                    .requireProofKey(false)               // No PKCE
                    .build())
            .tokenSettings(TokenSettings.builder()
                    .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
                    .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                    .accessTokenTimeToLive(Duration.ofMinutes(15))
                    .build());

    if (oauthClientRedirectUri != null && !oauthClientRedirectUri.isBlank()) {
        builder.redirectUri(oauthClientRedirectUri);      // Liberty callback (second URI)
    }

    return new InMemoryRegisteredClientRepository(builder.build());
}
```

**Key design decisions:**

| Decision | Why |
|----------|-----|
| `{noop}` prefix | Spring's `DelegatingPasswordEncoder` checks this prefix and uses `NoOpPasswordEncoder`. Without it, Spring hashes the incoming secret with BCrypt and comparison fails. |
| `CLIENT_SECRET_BASIC` + `CLIENT_SECRET_POST` | Liberty sends the secret via HTTP Basic Auth header (`client_secret_basic`). Both methods are registered for flexibility. |
| `requireAuthorizationConsent(false)` | No consent screen — users don't see "App X wants to access your data" page. |
| `requireProofKey(false)` | Liberty uses the standard authorization code flow, not PKCE. |
| Dual `redirectUri()` | Liberty sends its OWN callback URL (`auth.inst1.../oidcclient/redirect/default-oidc`) as the `redirect_uri` parameter in the `/oauth2/authorize` request. Both TRIRIGA's and Liberty's callbacks must be registered. |
| `InMemoryRegisteredClientRepository` | Client registrations are lost on pod restart. Acceptable for dev; use JDBC-backed repository for production. |

#### 3.2 `jwkSource()` — RSA Key Generation

```java
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

private static KeyPair generateRsaKey() {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
}
```

**Key behavior:**
- Generates a new **2048-bit RSA key pair** at each pod startup
- The public key is exposed at `/oauth2/jwks` as a JWK Set
- Liberty fetches the JWK Set to validate ID token signatures
- The private key stays in memory and signs ID tokens (`RS256`)

**Important limitation — ephemeral keys:**
- Each pod restart = new key pair
- Tokens signed by pod A cannot be validated by pod B (they have different keys)
- **Workaround:** Run 1 replica
- **Production fix:** Store JWK Set in a PVC or ConfigMap (see Appendix C in DEPLOYMENT.md)

#### 3.3 `tokenCustomizer()` — ID Token Claims Mapping

```java
@Bean
public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
    return context -> {
        if ("id_token".equals(context.getTokenType().getValue())) {
            var principal = context.getPrincipal();
            if (principal instanceof OAuth2AuthenticationToken authToken) {
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
```

**Claim mapping:**

| ID Token Claim | Source (Entra ID OidcUser) | Example |
|----------------|---------------------------|---------|
| `sub` | `oidcUser.getSubject()` | `w5N8Lp8cvxakXLYg9Sl7BnyPKh2j98MQxCDNHfPZRYA` |
| `preferred_username` | `oidcUser.getPreferredUsername()` fallback to `email` | `tarun@ecifm.com` |
| `email` | `oidcUser.getEmail()` or empty string | `tarun.suneja@ecifm.com` |
| `name` | `oidcUser.getFullName()` fallback to `preferredUsername` | `Tarun Suneja` |

**Critical:** The `preferred_username` claim is what Liberty maps to MAS's `UserPrincipal`. If this claim is missing or has the wrong value, TRIRIGA won't recognize the user.

**Flow:** The `OAuth2AuthenticationToken` and its `OidcUser` principal come from Spring Security's OAuth2 login flow — they represent the user as authenticated by Entra ID. The token customizer runs during ID token generation (step 10 in the flow).

---

### `SecurityConfig.java`

**Role:** Three security filter chains that define the request processing pipeline.

**File:** `src/main/java/com/ecifm/saml/bridge/config/SecurityConfig.java` (67 lines)

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                .oidc(Customizer.withDefaults());

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

#### Chain 1: Authorization Server (`@Order(1)`)

| Aspect | Details |
|--------|---------|
| **Matcher** | All OIDC protocol endpoints (configured internally by `applyDefaultSecurity`) |
| **Endpoints** | `GET /.well-known/openid-configuration`, `GET /oauth2/authorize`, `POST /oauth2/token`, `GET /oauth2/jwks`, `POST /oauth2/revoke`, `POST /oauth2/introspect` |
| **Auth** | Default Spring Security AS rules. Unauthenticated requests get 401 unless overridden. |
| **Override** | `LoginUrlAuthenticationEntryPoint("/oauth2/authorization/entra-id")` — changes 401 to a **302 redirect** to `/oauth2/authorization/entra-id` |

**The unauthenticated request flow:**
1. User hits `GET /oauth2/authorize?client_id=mas-facilities&...`
2. Chain 1 matches (OIDC AS endpoints)
3. User is not authenticated → `AuthenticationEntryPoint` is called
4. `LoginUrlAuthenticationEntryPoint` returns `302 Location: /oauth2/authorization/entra-id`
5. Browser follows redirect → Chain 3 handles it

#### Chain 2: Local Test Endpoints (`@Order(2)`)

| Aspect | Details |
|--------|---------|
| **Matcher** | `/local/**` |
| **Endpoints** | `/local/test-soap`, `/local/test-internal`, `/local/test-raw`, `/local/mock-sso`, `/local/mock-redirect` |
| **Auth** | Permit all — no authentication required |
| **Session** | Stateless — no HTTP session created |
| **CSRF** | Disabled — these are diagnostic endpoints |

#### Chain 3: OAuth2 Login + App Endpoints (`@Order(3)`)

| Aspect | Details |
|--------|---------|
| **Matcher** | All remaining requests not matched by Chain 1 or 2 |
| **Public endpoints** | `/actuator/health/**`, `/test`, `/test-soap` |
| **Auth required** | Everything else (`/`, `/redirect`, etc.) |
| **Login mechanism** | `oauth2Login()` — Spring Security's built-in OAuth2 client login |
| **Login page** | `/oauth2/authorization/entra-id` — triggers Entra ID authorization flow |

**The authenticated request flow (after Entra ID login):**
1. User is redirected back to `/login/oauth2/code/entra-id?code=xxx`
2. Chain 3 matches (no more specific matcher applies)
3. `OAuth2LoginAuthenticationFilter` processes the callback:
   - Exchanges the authorization code for tokens at Entra ID's token endpoint
   - Creates an `OAuth2AuthenticationToken` containing the `OidcUser`
   - Saves the authentication in the HTTP session
4. `RequestCache` retrieves the original saved request (`/oauth2/authorize?continue`)
5. Browser is redirected back to `/oauth2/authorize` — this time with an authenticated session
6. Chain 1 matches again — user IS authenticated → authorization code is generated

#### `passwordEncoder()`

```java
return PasswordEncoderFactories.createDelegatingPasswordEncoder();
```

Creates a `DelegatingPasswordEncoder` that supports multiple encoding schemes:
- `{bcrypt}` — BCrypt hashed passwords
- `{noop}` — Plain text (used for the OIDC client secret)
- `{pbkdf2}` — PBKDF2 hashed passwords
- `{scrypt}` — SCrypt hashed passwords
- `{sha256}` — SHA-256 hashed passwords

**Why this is necessary:** The OIDC client secret is stored with the `{noop}` prefix (see `AuthServerConfig`). Without this bean, Spring Security's default password encoder is `{bcrypt}` — it would try to BCrypt-hash the incoming secret and compare it to the stored `{noop}...` value, which would always fail. The `DelegatingPasswordEncoder` checks the prefix of the stored value and delegates to the appropriate encoder.

---

### `CxfConfig.java`

**Role:** Registers Apache CXF's HTTP transport for SOAP calls to TRIRIGA.

```java
@Configuration
public class CxfConfig {

    @PostConstruct
    public void init() {
        Bus bus = BusFactory.getDefaultBus(true);
        if (bus instanceof ExtensionManagerBus extBus) {
            ConduitInitiatorManager conduitReg = extBus.getExtension(ConduitInitiatorManager.class);
            if (conduitReg != null) {
                try {
                    HTTPTransportFactory factory = new HTTPTransportFactory();
                    conduitReg.registerConduitInitiator("http://schemas.xmlsoap.org/soap/http", factory);
                    conduitReg.registerConduitInitiator("http://www.w3.org/2003/05/soap/bindings/HTTP/", factory);
                    conduitReg.registerConduitInitiator("http://schemas.xmlsoap.org/wsdl/http/", factory);
                    log.info("Registered HTTPTransportFactory for all SOAP transports");
                } catch (Exception e) {
                    log.warn("Failed to register HTTPTransportFactory: {}", e.getMessage());
                }
            }
        }
    }
}
```

**Why this is needed:** When CXF runs inside a Spring Boot executable JAR, the default transport factory is not always auto-registered. Without this configuration, SOAP calls fail with:

```
No conduit initiator registered for namespace http://schemas.xmlsoap.org/soap/http
```

This class manually registers `HTTPTransportFactory` for all three SOAP transport namespaces.

---

## 4. Package: `com.ecifm.saml.bridge.controller`

### `AcsHandlerController.java`

**Role:** The main controller handling SSO redirects, user-facing pages, and SOAP diagnostic endpoints.

**File:** `src/main/java/com/ecifm/saml/bridge/controller/AcsHandlerController.java` (455 lines)

#### Endpoint Reference

| Endpoint | Method | Auth Required | Purpose |
|----------|--------|---------------|---------|
| `GET /` | `defaultLanding()` | No | Redirects to `/redirect` |
| `GET /test` | `test()` | No | Returns `"ecifm-saml-bridge is running"` |
| `GET /test-soap` | `testSoap()` | Yes (Entra ID) | Tests SOAP connectivity to TRIRIGA |
| `GET /redirect` | `ssoRedirect()` | No (redirects to Entra ID if not) | Main SSO handler — redirects to TRIRIGA post-auth |
| `GET /local/test-soap` | `localTestSoap()` | No | SOAP test without auth |
| `GET /local/test-internal` | `localTestInternal()` | No | Raw HTTP SOAP with 3 auth method tests |
| `GET /local/test-raw` | `localTestRaw()` | No | Two-step SOAP with session cookie + Basic Auth |

#### `ssoRedirect()` — Main SSO Entry Point

```java
@GetMapping("/redirect")
public ResponseEntity<String> ssoRedirect(
        @AuthenticationPrincipal OidcUser oidcUser,
        @RegisteredOAuth2AuthorizedClient("entra-id") OAuth2AuthorizedClient authorizedClient) {

    if (oidcUser == null || authorizedClient == null) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "/oauth2/authorization/entra-id")
                .build();
    }

    String email = extractEmail(oidcUser);
    log.info("Authenticated user: {}, redirecting to MAS: {}", email, masRedirectUrl);

    return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, masRedirectUrl)
            .build();
}
```

**Flow:**
1. User visits `/redirect`
2. If not authenticated → redirect to `/oauth2/authorization/entra-id` (Entra ID login)
3. If authenticated → redirect to `MAS_REDIRECT_URL` (TRIRIGA app)

**Note:** In the v0.2.0 OIDC IdP architecture, this endpoint is less critical because the AS chain (`/oauth2/authorize`) handles the flow. It's preserved as a fallback and for backward compatibility.

#### `testSoap()` — Authenticated SOAP Test

```java
@GetMapping("/test-soap")
public ResponseEntity<String> testSoap(
        @AuthenticationPrincipal OidcUser oidcUser,
        @RegisteredOAuth2AuthorizedClient("entra-id") OAuth2AuthorizedClient authorizedClient) {
    if (oidcUser == null || authorizedClient == null) {
        return ResponseEntity.ok("Not authenticated. Visit /redirect first.");
    }
    String email = extractEmail(oidcUser);
    String result = tririgaWsClient.getApplicationInfo();
    return ResponseEntity.ok()
            .contentType(MediaType.TEXT_PLAIN)
            .body("User: " + email + "\n\nSOAP Response:\n" + result);
}
```

Tests SOAP connectivity after successful Entra ID authentication. Returns user email and TRIRIGA application info.

#### `localTestInternal()` — Raw HTTP SOAP Diagnostics

This endpoint performs three raw HTTP tests against the internal TRIRIGA SOAP service:

1. **Test 1: No auth** — Sends SOAP request without any authentication. Tests basic connectivity.
2. **Test 2: Custom Username/Password headers** — Sends TRIRIGA's custom `Username` and `Password` HTTP headers.
3. **Test 3: HTTP Basic Auth** — Sends `Authorization: Basic base64(username:password)` header.

Each test captures:
- HTTP response code
- `Set-Cookie` header (session cookie)
- Response body

#### `localTestRaw()` — Two-Step SOAP with Session

Mirrors TRIRIGA's two-step SOAP authentication:
1. Send SOAP request without auth → receive `JSESSIONID` cookie
2. Send SOAP request with HTTP Basic Auth + session cookie → authenticated response

This is the actual authentication mechanism TRIRIGA uses.

#### Helper Methods

**`extractEmail(OidcUser oidcUser)`:**
```java
private String extractEmail(OidcUser oidcUser) {
    String email = oidcUser.getEmail();
    if (email == null || email.isBlank()) {
        email = oidcUser.getPreferredUsername();
    }
    if (email == null || email.isBlank()) {
        email = oidcUser.getSubject();
    }
    return email;
}
```
Fallback chain: `email` → `preferred_username` → `sub` (opaque identifier).

**`extractGroupsFromAccessToken(String accessToken)`:**
```java
private List<String> extractGroupsFromAccessToken(String accessToken) {
    String[] parts = accessToken.split("\\.");
    if (parts.length < 2) return List.of();
    String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    JsonNode claims = objectMapper.readTree(payload);
    JsonNode groupsNode = claims.get("groups");
    if (groupsNode != null && groupsNode.isArray()) {
        return objectMapper.convertValue(groupsNode, List.class);
    }
    return List.of();
}
```
Decodes the JWT access token payload (base64 URL-decoded) and extracts the `groups` claim. Used to get user groups when the Entra ID token includes them.

**`buildPage()`:** Generates an HTML status page with user info, groups, and a "Go to TRIRIGA" button. Uses inline CSS with no external dependencies.

**`escapeHtml()`:** XSS prevention — escapes `&`, `<`, `>`, `"`, `'` for user-controlled values in the HTML page.

---

### `LocalMockController.java`

**Role:** Mock endpoints for local testing without a real TRIRIGA/MAS backend.

**File:** `src/main/java/com/ecifm/saml/bridge/controller/LocalMockController.java` (49 lines)

| Endpoint | Purpose |
|----------|---------|
| `GET /local/mock-sso` | Simulates MAS SSOConnect REST call. Accepts `userName` and `adGroupName` params. Logs the call and returns success. |
| `GET /local/mock-redirect` | Simulates redirect behavior. Accepts `userName` and `groups` params. Returns a message indicating where it would redirect. |

These endpoints are useful for testing the bridge in isolation when TRIRIGA/MAS is not available.

---

## 5. Package: `com.ecifm.saml.bridge.service`

### `MasSyncService.java`

**Role:** Orchestrates user group synchronization to MAS.

**File:** `src/main/java/com/ecifm/saml/bridge/service/MasSyncService.java` (42 lines)

```java
@Service
public class MasSyncService {

    private final MasApiClient masApiClient;
    private final EntraIdGroupResolver entraIdGroupResolver;

    public MasSyncService(MasApiClient masApiClient, EntraIdGroupResolver entraIdGroupResolver) {
        this.masApiClient = masApiClient;
        this.entraIdGroupResolver = entraIdGroupResolver;
    }

    public boolean syncUser(String bearerToken, String email, List<String> jwtGroups) {
        log.info("Syncing user: {}", email);

        List<String> groups = jwtGroups;

        if (groups == null || groups.isEmpty()) {
            log.info("No groups in JWT, falling back to Microsoft Graph API");
            groups = entraIdGroupResolver.resolveGroups(bearerToken);
        }

        String groupName = groups.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.joining(","));

        log.info("Resolved groups for {}: {}", email, groupName);

        return masApiClient.syncUserGroups(bearerToken, email, groupName);
    }
}
```

**Group resolution chain:**
1. **JWT groups** — If the Entra ID access token contains a `groups` claim, those are used directly
2. **Microsoft Graph API** — If JWT has no groups (common when >200 groups, or `groupMembershipClaims` not configured), falls back to calling Graph API's `/me/memberOf` endpoint

Groups are joined into a comma-separated string and sent to MAS's SSOConnect REST API.

---

### `MasApiClient.java`

**Role:** Calls MAS/TRIRIGA's SSOConnect REST API to sync user groups.

**File:** `src/main/java/com/ecifm/saml/bridge/service/MasApiClient.java` (110 lines)

```java
@Component
public class MasApiClient {

    @Value("${mas.base-url}")
    private String masBaseUrl;

    @Value("${mas.context}")
    private String masContext;

    @Value("${mas.rest-api}")
    private String masRestApi;

    private final RestTemplate restTemplate;

    public MasApiClient(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
        disableSslVerification();
    }

    private static void disableSslVerification() {
        // Creates a trust-all SSL context that accepts any certificate
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
        log.warn("SSL verification disabled for outbound HTTPS connections to MAS");
    }

    public boolean syncUserGroups(String bearerToken, String userName, String groupName) {
        String url = buildUrl(userName, groupName);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

        // ... checks response status and returns true/false
    }

    private String buildUrl(String userName, String groupName) {
        String urlTemplate = masBaseUrl + masContext + masRestApi;
        return MessageFormat.format(urlTemplate, encodeParam(userName), encodeParam(groupName));
        // Example: https://main.facilities.../tririga/html/en/default/rest/SSOConnect
        //   ?userName=user@domain.com&adGroupName=Group1,Group2
    }
}
```

**SSL verification is disabled globally** because the bridge connects to MAS's internal cluster service which uses a cluster-internal TLS certificate. This is acceptable in a cluster environment but should use proper certificate validation in production.

**URL construction:**
```
{mas.base-url}{mas.context}{mas.rest-api}
→ https://main.facilities.inst1.apps.npos2.ecifmdev.net
  /tririga
  /html/en/default/rest/SSOConnect?userName={0}&adGroupName={1}
```

---

### `EntraIdGroupResolver.java`

**Role:** Resolves user groups via Microsoft Graph API as a fallback when the JWT has no groups.

**File:** `src/main/java/com/ecifm/saml/bridge/service/EntraIdGroupResolver.java` (84 lines)

```java
@Component
public class EntraIdGroupResolver {

    private static final String GRAPH_API_URL = "https://graph.microsoft.com/v1.0/me/memberOf?$select=displayName";

    @Value("${entra-id.graph.api-version:v1.0}")
    private String apiVersion;

    @Value("${entra-id.graph.enabled:true}")
    private boolean enabled;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public List<String> resolveGroups(String bearerToken) {
        List<String> groups = new ArrayList<>();
        if (!enabled) {
            log.warn("Microsoft Graph API group resolution is disabled.");
            return groups;
        }

        // GET https://graph.microsoft.com/v1.0/me/memberOf?$select=displayName
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);

        ResponseEntity<String> response = restTemplate.exchange(GRAPH_API_URL, HttpMethod.GET, ...);
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode value = root.get("value");
        if (value != null && value.isArray()) {
            for (JsonNode node : value) {
                JsonNode displayName = node.get("displayName");
                if (displayName != null && !displayName.asText().isEmpty()) {
                    groups.add(displayName.asText());
                }
            }
        }
        return groups;
    }
}
```

**When it's called:** When `MasSyncService.syncUser()` receives `null` or empty groups from the JWT.

**How it works:**
1. Uses the Entra ID access token (bearer token) to authenticate with Microsoft Graph API
2. Calls `GET /v1.0/me/memberOf` which returns all groups the user is a member of
3. The `$select=displayName` parameter limits response to just the group names
4. Extracts `displayName` from each group entry
5. Returns the list of group names

**When to disable:** If Entra ID is configured with `groupMembershipClaims` and tokens contain all necessary groups, set `entra-id.graph.enabled=false` to avoid unnecessary API calls.

---

### `TririgaWsClient.java`

**Role:** SOAP client for TRIRIGA's web services using Apache CXF.

**File:** `src/main/java/com/ecifm/saml/bridge/service/TririgaWsClient.java` (138 lines)

```java
@Component
public class TririgaWsClient {

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
        TririgaWSPortType port = createPort();
        ApplicationInfo info = port.getApplicationInfo();
        return "Success:\n  apiVersion: " + value(info.getApiVersion()) + ...
    }

    public String getHttpSession() {
        TririgaWSPortType port = createPort();
        var session = port.getHttpSession();
        return "Success:\n  Session ID: " + session.getId();
    }

    private TririgaWSPortType createPort() throws Exception {
        // 1. Build endpoint URL
        String ctx = masContext == null ? "" : masContext.trim().replaceAll("^/+|/+$", "");
        String endpoint = ctx.isEmpty()
            ? masBaseUrl + "/ws/TririgaWS"
            : masBaseUrl + "/" + ctx + "/ws/TririgaWS";

        // 2. Load WSDL from classpath
        URL wsdlUrl = getClass().getResource(WSDL_RESOURCE);
        TririgaWS service = new TririgaWS(wsdlUrl);
        TririgaWSPortType port = service.getTririgaWSPort();

        // 3. Set endpoint address + session maintenance
        BindingProvider bp = (BindingProvider) port;
        bp.getRequestContext().put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, endpoint);
        bp.getRequestContext().put(BindingProvider.SESSION_MAINTAIN_PROPERTY, Boolean.TRUE);

        // 4. Add SOAP BasicChallenge header (custom TRIRIGA auth)
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element challenge = doc.createElementNS(
            "http://soap-authentication.org/basic/2001/10/", "h:BasicChallenge");
        Element userName = doc.createElement("Username");
        userName.setTextContent(tririgaUsername.trim());
        challenge.appendChild(userName);
        Element password = doc.createElement("Password");
        password.setTextContent(tririgaPassword);
        challenge.appendChild(password);
        List<Header> headerList = List.of(new SoapHeader(
            new QName("http://soap-authentication.org/basic/2001/10/", "BasicChallenge", "h"),
            challenge));
        bp.getRequestContext().put(Header.HEADER_LIST, headerList);

        // 5. Configure CXF HTTP conduit
        Client client = ClientProxy.getClient(port);
        HTTPConduit conduit = (HTTPConduit) client.getConduit();
        HTTPClientPolicy policy = new HTTPClientPolicy();
        policy.setConnectionTimeout(30_000);
        policy.setReceiveTimeout(120_000);
        policy.setAllowChunking(false);
        conduit.setClient(policy);

        // 6. Disable SSL verification
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

        return port;
    }
}
```

**TRIRIGA SOAP Authentication:**

TRIRIGA uses a **custom SOAP header-based authentication** (NOT WS-Security, NOT HTTP Basic Auth):

```xml
<SOAP-ENV:Header>
  <h:BasicChallenge xmlns:h="http://soap-authentication.org/basic/2001/10/">
    <Username>tarun.suneja@ecifm.com</Username>
    <Password>****</Password>
  </h:BasicChallenge>
</SOAP-ENV:Header>
```

The bridge constructs this header programmatically using DOM APIs and injects it into the CXF request context via `Header.HEADER_LIST`. TRIRIGA validates the credentials and issues a `JSESSIONID` cookie for subsequent requests.

**CXF Configuration Details:**
- **`SESSION_MAINTAIN_PROPERTY`**: Keeps the JSESSIONID cookie across calls (enables session reuse)
- **`ConnectionTimeout`**: 30 seconds
- **`ReceiveTimeout`**: 120 seconds (SOAP operations can be slow)
- **`AllowChunking(false)`**: Prevents chunked encoding (TRIRIGA may not support it)
- **`DisableCNCheck(true)`**: Skips TLS hostname verification (internal cluster certs don't match hostnames)
- **Trust-all `TrustManager`**: Accepts any TLS certificate

---

## 6. Package: `com.ecifm.saml.bridge.model`

### `UserInfo.java`

**Role:** Simple POJO for passing user data between services.

```java
public class UserInfo {
    private String email;
    private List<String> groups;

    // Constructors, getters, setters
}
```

No JPA/DB backing — purely in-memory data transfer object. Used to bundle `email` and `groups` from Entra ID for downstream processing.

---

## 7. Dependency Summary

**File:** `pom.xml` (129 lines)

| Dependency | GroupId:ArtifactId | Purpose |
|-----------|-------------------|---------|
| Spring Boot Web | `spring-boot-starter-web` | REST controllers, Jackson, embedded Tomcat |
| Spring Security | `spring-boot-starter-security` | Authentication, authorization, filter chains |
| OAuth2 Resource Server | `spring-boot-starter-oauth2-resource-server` | JWT bearer token validation (legacy) |
| OAuth2 Client | `spring-boot-starter-oauth2-client` | OAuth2 client for Entra ID login |
| **OAuth2 Authorization Server** | `spring-security-oauth2-authorization-server` | **Core new dependency** — provides `/oauth2/authorize`, `/oauth2/token`, `/.well-known/openid-configuration`, `/oauth2/jwks` |
| Actuator | `spring-boot-starter-actuator` | Health probes (liveness, readiness) |
| CXF JAX-WS | `cxf-rt-frontend-jaxws` | JAX-WS client for TRIRIGA SOAP |
| CXF HTTP Transport | `cxf-rt-transports-http` | HTTP transport for CXF |
| CXF SOAP | `cxf-rt-bindings-soap` | SOAP binding for CXF |

**Build plugin:**

| Plugin | Purpose |
|--------|---------|
| `spring-boot-maven-plugin` | Packages the executable JAR |
| `cxf-codegen-plugin` | Generates Java classes from `TririgaWS.wsdl` at compile time (`wsdl2java` goal) |

---

## 8. Environment Variable Wiring

All environment variables flow through `application-openshift.yml` to Spring properties:

```yaml
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
  redirect-url: ${MAS_REDIRECT_URL}
  rest-api: ${MAS_REST_API}
  oidc:
    client-id: ${MAS_OIDC_CLIENT_ID:mas-facilities}
    client-secret: ${MAS_OIDC_CLIENT_SECRET}
    redirect-uri: ${MAS_OIDC_REDIRECT_URI}
  oauth:
    client-redirect-uri: ${MAS_OAUTH_CLIENT_REDIRECT_URI:}
```

**Property → Java code mapping:**

| Spring Property | Used By | Java Location |
|----------------|---------|---------------|
| `spring.security.oauth2.authorizationserver.issuer` | OIDC discovery metadata | `AuthServerConfig.java` (implicit — sets `/.well-known/openid-configuration`) |
| `spring.security.oauth2.client.registration.entra-id.client-id` | OAuth2 client for Entra ID | `SecurityConfig.java` Chain 3 (implicit — used by `oauth2Login()`) |
| `spring.security.oauth2.client.registration.entra-id.client-secret` | OAuth2 client for Entra ID | `SecurityConfig.java` Chain 3 (implicit) |
| `spring.security.oauth2.client.provider.entra-id.issuer-uri` | Entra ID token validation | Implicit |
| `mas.oidc.client-id` | OIDC client registration | `AuthServerConfig.registeredClientRepository()` |
| `mas.oidc.client-secret` | OIDC client secret | `AuthServerConfig.registeredClientRepository()` |
| `mas.oidc.redirect-uri` | TRIRIGA callback URI | `AuthServerConfig.registeredClientRepository()` |
| `mas.oauth.client-redirect-uri` | Liberty callback URI | `AuthServerConfig.registeredClientRepository()` |
| `mas.base-url` | TRIRIGA base URL | `AcsHandlerController`, `MasApiClient`, `TririgaWsClient` |
| `mas.context` | TRIRIGA context path | `MasApiClient`, `TririgaWsClient` |
| `mas.redirect-url` | Post-login redirect | `AcsHandlerController.ssoRedirect()` |
| `mas.rest-api` | SSOConnect REST path | `MasApiClient` |
| `tririga.username` | SOAP service account | `TririgaWsClient` |
| `tririga.password` | SOAP service password | `TririgaWsClient` |
| `entra-id.graph.enabled` | Graph API fallback | `EntraIdGroupResolver` |

---

## 9. Complete Authentication Flows

### Flow 1: OIDC Authorization Code Flow (v0.2.0 — The Main Path)

This is the full Single Sign-On flow with zero user clicks after first auth.

```
Browser                    Bridge Security Filters              Entra ID
  │                              │                                │
  │ ① User visits TRIRIGA        │                                │
  │─ ─ ─ → TRIRIGA/Liberty       │                                │
  │                              │                                │
  │← Liberty has no session      │                                │
  │  redirects to bridge         │                                │
  │                              │                                │
  │ ② GET /oauth2/authorize      │                                │
  │    ?client_id=mas-facilities  │                                │
  │    &redirect_uri=auth...      │                                │
  │    /oidcclient/redirect      │                                │
  │    /default-oidc             │                                │
  │    &response_type=code       │                                │
  │    &scope=openid+email+profile                                │
  │    &state=xxx                │                                │
  │                              │                                │
  │         ┌─────────────────────────────────────┐              │
  │         │ SECURITY CHAIN 1: AS ENDPOINTS      │              │
  │         │ OAuth2AuthorizationServerFilter     │              │
  │         │ validates client_id format          │              │
  │         │ validates redirect_uri is registered│              │
  │         │ validates response_type=code        │              │
  │         │ saves original request to RequestCache          │
  │         │ User NOT authenticated → EntryPoint │              │
  │         │ LoginUrlAuthenticationEntryPoint:   │              │
  │         │   302 → /oauth2/authorization/entra-id           │
  │         └─────────────────────────────────────┘              │
  │                              │                                │
  │ ③ ← 302 → /oauth2/authorization/entra-id                    │
  │                              │                                │
  │         ┌─────────────────────────────────────┐              │
  │         │ SECURITY CHAIN 3: OAUTH2 LOGIN      │              │
  │         │ OAuth2LoginAuthenticationFilter     │              │
  │         │ builds authorization URL:           │              │
  │         │ login.microsoftonline.com/{tenant}  │              │
  │         │   /oauth2/v2.0/authorize            │              │
  │         │   ?response_type=code               │              │
  │         │   &client_id={app-client-id}        │              │
  │         │   &redirect_uri={bridge}/login/     │              │
  │         │    oauth2/code/entra-id             │              │
  │         │   &scope=openid+profile+email       │              │
  │         └─────────────────────────────────────┘              │
  │                              │                                │
  │ ④ ← 302 → login.microsoftonline.com/...                     │
  │─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ →                  │
  │                              │                                │
  │ ⑤ User enters credentials   │    ┌───────────────────────┐  │
  │   (or silent SSO if cached)  │    │  Entra ID validates   │  │
  │                              │    │  authenticates user   │  │
  │ ⑥ ← 302 → /login/oauth2/    │    └───────────────────────┘  │
  │    code/entra-id?code=yyy    │                                │
  │                              │                                │
  │         ┌─────────────────────────────────────┐              │
  │         │ SECURITY CHAIN 3: CALLBACK          │              │
  │         │ OAuth2LoginAuthenticationFilter     │              │
  │         │ exchanges code for tokens at        │              │
  │         │   login.microsoftonline.com/{tenant}│              │
  │         │   /oauth2/v2.0/token               │              │
  │         │ receives:                          │              │
  │         │   - id_token (JWT, RS256 signed)   │              │
  │         │   - access_token (JWT, opaque)     │              │
  │         │   - refresh_token                  │              │
  │         │ creates OAuth2AuthenticationToken  │              │
  │         │   principal = OidcUser (from id_token claims)    │
  │         │   authorities = OIDC_USER, SCOPE_*               │
  │         │ saves auth in HTTP session          │              │
  │         │ RequestCache retrieves original     │              │
  │         │   /oauth2/authorize URL             │              │
  │         └─────────────────────────────────────┘              │
  │                              │                                │
  │ ⑦ ← 302 → /oauth2/authorize?continue                        │
  │    (with JSESSIONID cookie set)                             │
  │                              │                                │
  │         ┌─────────────────────────────────────┐              │
  │         │ SECURITY CHAIN 1: AUTHORIZED        │              │
  │         │ User IS authenticated               │              │
  │         │ validates authorization request     │              │
  │         │ AuthServerConfig.tokenCustomizer(): │              │
  │         │   reads OidcUser from authentication│              │
  │         │   maps claims to ID token:          │              │
  │         │     sub → oidcUser.getSubject()     │              │
  │         │     preferred_username → email      │              │
  │         │     email → oidcUser.getEmail()     │              │
  │         │     name → oidcUser.getFullName()   │              │
  │         │ generates authorization code        │              │
  │         │   (5-min TTL, stored in memory)     │              │
  │         └─────────────────────────────────────┘              │
  │                              │                                │
  │ ⑧ ← 302 → auth.../oidcclient/redirect                       │
  │    /default-oidc?code=zzz&state=xxx                          │
  │                              │                                │
  │─ ─ ─ → Liberty receives code │                                │
  │                              │                                │
```

### Flow 2: Token Exchange (Liberty → Bridge)

```
Liberty                        Bridge                              Entra ID
  │                              │                                   │
  │ ① POST /oauth2/token         │                                   │
  │    Authorization: Basic      │                                   │
  │      base64(mas-facilities:  │                                   │
  │        {client_secret})      │                                   │
  │    Content-Type:             │                                   │
  │      application/x-www-form-urlencoded                          │
  │    grant_type=authorization_code                                │
  │    code=zzz                   │                                   │
  │    redirect_uri=https://auth │                                   │
  │      .../oidcclient/redirect │                                   │
  │      /default-oidc          │                                   │
  │                              │                                   │
  │         ┌──────────────────────────────────────┐                │
  │         │ OAuth2TokenEndpointFilter             │                │
  │         │ ClientSecretAuthenticationProvider   │                │
  │         │ validates client_id + client_secret   │                │
  │         │ (matches against in-memory           │                │
  │         │  RegisteredClient)                    │                │
  │         │ validates authorization code          │                │
  │         │ validates redirect_uri matches        │                │
  │         │ generates tokens:                     │                │
  │         │   id_token:                           │                │
  │         │     signed with RSA private key      │                │
  │         │     (RS256 algorithm)                 │                │
  │         │     header: { kid, typ: "JWT",       │                │
  │         │               alg: "RS256" }          │                │
  │         │     payload: { iss, sub, aud,         │                │
  │         │       exp, iat, preferred_username,   │                │
  │         │       email, name }                   │                │
  │         │   access_token: (opaque, for          │                │
  │         │     future use)                       │                │
  │         │   refresh_token: (opaque)             │                │
  │         └──────────────────────────────────────┘                │
  │                              │                                   │
  │ ② ← { access_token,         │                                   │
  │        id_token (RS256),     │                                   │
  │        token_type: "Bearer", │                                   │
  │        expires_in }          │                                   │
  │                              │                                   │
  │ ③ Liberty validates:         │                                   │
  │    - Fetches JWKS from       │                                   │
  │      /oauth2/jwks            │                                   │
  │    - Gets RSA public key     │                                   │
  │    - Verifies ID token       │                                   │
  │      signature               │                                   │
  │    - Verifies iss claim      │                                   │
  │    - Verifies aud claim      │                                   │
  │    - Verifies exp claim      │                                   │
  │    - Extracts claims         │                                   │
  │    - Sets UserPrincipal      │                                   │
  │      from preferred_username │                                   │
  │    - Creates MAS session     │                                   │
  │                              │                                   │
  │ ④ Liberty → TRIRIGA: ✓      │                                   │
  │    User is authenticated     │                                   │
```

### Flow 3: Group Sync (Legacy — via `/redirect`)

```
Browser                    Bridge                              MAS
  │                          │                                  │
  │ GET /redirect            │                                  │
  │─ ─ ─ → AcsHandlerController.ssoRedirect()                   │
  │                          │                                  │
  │ ← 302 → /oauth2/authorization/entra-id (if not auth'd)     │
  │─ ─ ─ → (Entra ID login)  │                                  │
  │                          │                                  │
  │ ← 302 → callback → /redirect                                │
  │─ ─ ─ → AcsHandlerController.ssoRedirect()                   │
  │                          │                                  │
  │  User IS authenticated   │                                  │
  │  extractEmail(oidcUser)  │                                  │
  │                          │                                  │
  │  MasSyncService          │                                  │
  │   .syncUser(token, email, jwtGroups)                        │
  │    │                     │                                  │
  │    ├─ jwtGroups NOT null │                                  │
  │    │  → use as-is        │                                  │
  │    │                     │                                  │
  │    └─ jwtGroups is null  │                                  │
  │       → EntraIdGroupResolver                                │
  │         .resolveGroups(accessToken)                         │
  │          │               │                                  │
  │          │ GET /v1.0/me/memberOf                            │
  │          │   ?$select=displayName                           │
  │          │─ ─ ─ → → → Microsoft Graph API                   │
  │          │               │                                  │
  │          │ ← { value: [  │                                  │
  │          │     { displayName: "MAS_ADMIN" },                │
  │          │     { displayName: "TRIRIGA_USER" }              │
  │          │   ] }         │                                  │
  │          │               │                                  │
  │          └─ returns ["MAS_ADMIN", "TRIRIGA_USER"]           │
  │                          │                                  │
  │  MasApiClient            │                                  │
  │   .syncUserGroups(token, email, "MAS_ADMIN,TRIRIGA_USER")   │
  │    │                     │                                  │
  │    │ GET {masBaseUrl}{masContext}{masRestApi}                │
  │    │   ?userName=email   │                                  │
  │    │   &adGroupName=MAS_ADMIN,TRIRIGA_USER                  │
  │    │   Authorization: Bearer {accessToken}                  │
  │    │─ ─ ─ → → → MAS SSOConnect                              │
  │    │                     │                                  │
  │    │ ← HTTP 200          │                                  │
  │    └─ returns true       │                                  │
  │                          │                                  │
  │ ← 302 → TRIRIGA app URL │                                  │
```

### Flow 4: SOAP Diagnostic Test

```
Admin browser                Bridge                              TRIRIGA SOAP
  │                            │                                  │
  │ GET /local/test-soap       │                                  │
  │─ ─ ─ → AcsHandlerController.localTestSoap()                   │
  │                            │                                  │
  │  TririgaWsClient           │                                  │
  │   .getApplicationInfo()    │                                  │
  │    │                       │                                  │
  │    └─ createPort()         │                                  │
  │       │                    │                                  │
  │       1. Load WSDL:        │                                  │
  │          /wsdl/TririgaWS.wsdl                                  │
  │       2. Build endpoint:   │                                  │
  │          {mas.base-url}    │                                  │
  │          /{mas.context}    │                                  │
  │          /ws/TririgaWS    │                                  │
  │       3. Set SOAP headers: │                                  │
  │          <h:BasicChallenge>                                    │
  │            <Username>...</Username>                           │
  │            <Password>...</Password>                           │
  │          </h:BasicChallenge>                                   │
  │       4. Configure CXF:    │                                  │
  │          - timeout: 30s/120s                                  │
  │          - SSL: trust-all   │                                  │
  │          - CN check: off   │                                  │
  │                            │                                  │
  │       POST /ws/TririgaWS   │                                  │
  │       SOAPAction: ""       │                                  │
  │       <getApplicationInfo> │                                  │
  │       │─ ─ ─ → → → → → → TRIRIGA                              │
  │       │                    │                                  │
  │       ← <applicationInfo>  │                                  │
  │         <apiVersion>...</> │                                  │
  │         <dbBuildNumber>...</>                                 │
  │         <tririgaBuildNumber>...</>                            │
  │                            │                                  │
  │  ← HTTP 200 + plain text   │                                  │
  │    "User: tarun@ecifm.com  │                                  │
  │     SOAP Response:         │                                  │
  │     Success:               │                                  │
  │       apiVersion: 4.7.1.0  │                                  │
  │       dbBuildNumber: ...  │                                  │
  │       tririgaBuildNumber: ..."                                │
```

---

*End of document*
