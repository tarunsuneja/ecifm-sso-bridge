# From-Basics-to-Master Learning Path

This is a complete self-study curriculum designed to take someone with basic Java knowledge to the level where they can independently investigate, debug, and make architectural decisions in a project like the ecifm-saml-bridge. Each stage includes concrete mini-projects, estimated time, and direct references to files in this codebase.

---

## Stage 1: Java & Spring Boot Foundation

**Time:** 3 weeks
**Goal:** Understand how a Spring Boot application is structured, how configuration works, and how components wire together.

### Concepts to Learn

| Concept | Why It Matters for This Project | File Reference |
|---------|--------------------------------|----------------|
| Maven `pom.xml` | Dependencies, plugins (cxf-codegen-plugin), build lifecycle | `pom.xml` |
| `@SpringBootApplication` | Entry point, auto-configuration | `EcifmSamlBridgeApplication.java:11` |
| `@Configuration` + `@Bean` | Programmatic bean definitions | `AuthServerConfig.java:42-139` |
| `@Component` / `@Service` | Spring-managed beans | `LibertySessionClient.java:38` |
| Constructor injection | `final` fields + constructor = clean DI | `LocalMockController.java:39-44` |
| `@Value` + `${...}` | Reading properties/environment variables | `TririgaWsClient.java:47-51` |
| `application.yml` / `application-{profile}.yml` | Profile-based configuration | `application-openshift.yml` |
| `@RestController` + `@GetMapping` | REST endpoints | `AcsHandlerController.java:73-76` |

### Mini-Projects

| # | Project | What You'll Build | What You'll Learn |
|---|---------|-------------------|-------------------|
| 1 | Generate a Spring Boot app with `start.spring.io` | Empty app with Web + Actuator dependencies | How Maven + Spring Boot bootstrap works |
| 2 | Add custom properties in `application.yml`, inject with `@Value` | Controller that reads a config value | Property resolution, `${...}` syntax |
| 3 | Create `@Service` + `@RestController` | `GET /greet?name=X` returns personalized message | DI wiring, request params, JSON response |
| 4 | Create `application-dev.yml` + `application-prod.yml` | Switch profiles with `SPRING_PROFILES_ACTIVE` | Profile-based configuration |
| 5 | Add `@Configuration` class with `@Bean` | Custom bean with initialization logic | Programmatic bean definitions |

### Check Your Understanding

- What happens if you have two `@Bean` methods returning the same type?
- How does `@SpringBootApplication` know which packages to scan?
- Why does the bridge use constructor injection instead of `@Autowired`?

---

## Stage 2: Spring Security

**Time:** 3 weeks
**Goal:** Understand SecurityFilterChain, multiple filter chains, and how authentication works in Spring Security.

### Concepts to Learn

| Concept | Why It Matters | File Reference |
|---------|----------------|----------------|
| `SecurityFilterChain` | The core concept — replaces old WebSecurityConfigurerAdapter | `SecurityConfig.java:20-61` |
| `@Order(N)` | Multiple chains with different URL matchers | `SecurityConfig.java:21,37,48` |
| `.requestMatchers("/path/**")` | URL pattern matching for chains | `SecurityConfig.java:39` |
| `.permitAll()` vs `.authenticated()` | Public vs protected endpoints | `SecurityConfig.java:40,51-52` |
| `.oauth2Login()` | Delegates auth to an external OIDC provider | `SecurityConfig.java:54-55` |
| `.sessionCreationPolicy()` | STATELESS vs IF_REQUIRED | `SecurityConfig.java:41,56-57` |
| `LoginUrlAuthenticationEntryPoint` | Where to redirect unauthenticated requests | `SecurityConfig.java:29` |
| `http.csrf().disable()` | When to disable CSRF (API endpoints) | `SecurityConfig.java:42,58` |

### Mini-Projects

| # | Project | What You'll Learn |
|---|---------|-------------------|
| 1 | Single filter chain — lock everything except `/public/**` | `.requestMatchers()`, `.permitAll()`, `.authenticated()` |
| 2 | Two `@Order` chains — `/admin/**` requires auth, `/public/**` is open | Multiple chains with different rules |
| 3 | Add form login with custom login page | `formLogin()`, login page customization |
| 4 | Add in-memory user with roles | `UserDetailsManager`, role-based access |
| 5 | Replace form login with OAuth2 login to GitHub | `oauth2Login()`, OAuth2 client config |
| 6 | Add actuator endpoints as public health checks | `.requestMatchers("/actuator/health/**").permitAll()` |

### Debugging Exercise

Look at `SecurityConfig.java:20-61`. There are 3 chains with `@Order(1)`, `@Order(2)`, `@Order(3)`.

- A request to `/local/test` → which chain handles it? → **Chain 2** (matches `/local/**`)
- A request to `/oauth2/authorize` → which chain? → **Chain 1** (matches via OAuth2AuthorizationServer)
- A request to `/redirect` → which chain? → **Chain 3** (last resort)

**Key Insight:** Spring Security evaluates chains in ascending `@Order`. The first chain whose `securityMatcher` matches the request path handles it. If no chain matches, you get a 404.

### Check Your Understanding

- What happens if two filter chains match the same URL? → Only the first (lowest `@Order`) executes
- Why is `/actuator/health/**` in Chain 3 instead of Chain 2? → Chain 2 only matches `/local/**`
- What would happen if `@Order(2)` matched `/oauth2/**`? → The AS chain would never execute
- Why does Chain 2 use `sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)` while Chain 3 also uses it? → The OIDC authorization code flow needs HTTP session to store the temporary auth state between redirects

---

## Stage 3: OAuth2 & OpenID Connect Protocol

**Time:** 3 weeks
**Goal:** Understand the OAuth2 authorization code flow, JWT structure, and how to build an Authorization Server.

### Concepts to Learn

| Concept | Why It Matters | File Reference |
|---------|----------------|----------------|
| Authorization code flow | The bridge uses this — user → AS → code → token | `AuthServerConfig.java:57` |
| `authorization_code` grant type | User-facing authentication | `AuthServerConfig.java:57` |
| `client_credentials` grant type | Machine-to-machine (no user) | `AuthServerConfig.java:58` |
| `RegisteredClient` | Who is allowed to request tokens | `AuthServerConfig.java:52-78` |
| `JWKSource` + RSA keys | Token signing — prove tokens came from us | `AuthServerConfig.java:81-95` |
| `OAuth2TokenCustomizer` | Modify id_token claims (sub, email, etc.) | `AuthServerConfig.java:103-139` |
| JWT header.payload.signature | Three base64 segments | Decode one on `jwt.io` |
| RS256 vs HS256 | Symmetric vs asymmetric signing | Bridge uses RS256 (RSA key pair) |
| JWKS endpoint | Public keys for token validation | `/oauth2/jwks` endpoint |
| `.well-known/openid-configuration` | Auto-discovery for OIDC providers | Bridge exposes this |

### Mini-Projects

| # | Project | What You'll Learn |
|---|---------|-------------------|
| 1 | Go to `jwt.io`, paste a JWT, decode it | See the header, payload, signature structure |
| 2 | Run Keycloak in Docker, create a realm + OIDC client | See a production OAuth2 AS up close |
| 3 | Build a Resource Server that validates JWTs | `spring-boot-starter-oauth2-resource-server` |
| 4 | Build an Authorization Server (AS) with one registered client | `spring-security-oauth2-authorization-server` |
| 5 | Add a `tokenCustomizer` that sets custom claims | Modify id_token content |
| 6 | Add `client_credentials` grant to your AS | See the difference in `sub` claim |

### Deep Dive: Why client_credentials Failed

When we used `client_credentials` to get a token from the bridge:
```json
// Token from client_credentials
{
  "sub": "mas-facilities",   // ← The CLIENT ID, not the user!
  "aud": "mas-facilities",
  "iss": "https://ecifm-sso-bridge-..."
}
```

Liberty expected `sub` to be a user email (like `tarun.suneja@ecifm.com`). It returned `CWOAU0073E` — can't map the subject to a TRIRIGA user.

The fix required the `authorization_code` grant, which involves a real user authentication:
```json
// Token from authorization_code
{
  "sub": "tarun.suneja@ecifm.com",  // ← The USER
  "preferred_username": "tarun.suneja@ecifm.com",
  "uniqueSecurityName": "tarun.suneja@ecifm.com"
}
```

### Check Your Understanding

- What's the difference between `sub` in `client_credentials` vs `authorization_code`?
- Why does the bridge generate a new RSA key pair at startup? (And why is this a problem for 2+ replicas?)
- What would happen if the `tokenCustomizer` didn't set `uniqueSecurityName`?
- Why does `MAS_OIDC_CLIENT_SECRET` use `{noop}` prefix? (Spring Security's password encoding)

---

## Stage 4: OpenShift & Kubernetes

**Time:** 4 weeks
**Goal:** Deploy, configure, and debug containerized applications on OpenShift.

### Concepts to Learn

| Concept | Why It Matters | File Reference |
|---------|----------------|----------------|
| **Pod** | Smallest deployable unit — runs one or more containers | `oc get pods` |
| **Deployment** | Manages replica count, rolling updates, self-healing | `openshift/deployment.yaml` |
| **StatefulSet** | Like Deployment but with stable network identity + persistent storage | `inst1-main-appserver` in facilities |
| **Service** | Stable network endpoint (ClusterIP) for pods | `openshift/service.yaml` |
| **Route** | External HTTPS URL → Service | `openshift/route.yaml` |
| **ConfigMap** | Non-sensitive configuration (env vars) | `openshift/configmap.yaml` |
| **Secret** | Sensitive data (passwords, tokens) | `openshift/secret.yaml` |
| **Probes** | Liveness (is app dead?), Readiness (can it serve traffic?), Startup (is it initialized?) | `openshift/deployment.yaml:49-60` |
| **Edge TLS** | Router terminates HTTPS, sends HTTP to pod | `openshift/route.yaml:14` |
| **Reencrypt TLS** | Router terminates HTTPS, establishes new HTTPS to pod | MAS routes |
| **`oc logs`** | View application logs | Debugging the bridge |

### Mini-Projects

| # | Project | What You'll Learn |
|---|---------|-------------------|
| 1 | `oc new-app` a public nginx image | Basic deployment from CLI |
| 2 | Write a Deployment YAML: 2 replicas, resource limits, liveness probe | YAML structure, probe config |
| 3 | Create a Service + Route manually | Exposing an app externally |
| 4 | Create a ConfigMap + mount as env vars, update and `oc rollout restart` | ConfigMap lifecycle |
| 5 | Create a Secret, reference in Deployment, read with `@Value` | Secret injection |
| 6 | `oc scale deployment X --replicas=5` + `oc delete pod X` | Scaling and self-healing |
| 7 | Port-forward a pod to localhost: `oc port-forward pod/X 8080:8080` | Local debugging of remote apps |
| 8 | Check logs: `oc logs deployment/X --tail=50 -f` | Real-time log tailing |

### Deep Dive: TLS Termination

**Edge TLS** (bridge route):
```
User ──HTTPS──▶ Router ──HTTP──▶ Pod
```
- Pod receives plain HTTP on port 8080
- `X-Forwarded-Proto: https` header tells Spring the original scheme
- `server.forward-headers-strategy: framework` in `application-openshift.yml` makes Spring read these headers

**Reencrypt TLS** (MAS routes):
```
User ──HTTPS──▶ Router ──HTTPS──▶ Pod
```
- Pod receives HTTPS on port 9443
- Pod must have its own TLS certificate (Liberty's `keystore.p12`)
- Liberty handles TLS directly

**Edge vs Reencrypt Decision:**

| Factor | Edge | Reencrypt |
|--------|------|-----------|
| Pod needs TLS cert? | No | Yes |
| Simpler for new apps | ✅ | ❌ |
| End-to-end encryption | ❌ (HTTP inside cluster) | ✅ |
| MAS compliance | ❌ | ✅ (MAS requires reencrypt) |

The bridge uses Edge because it's simpler and doesn't need pod-level TLS. MAS apps use Reencrypt because MAS mandates it.

### Check Your Understanding

- What happens if Readiness probe fails? Pod is removed from Service (no traffic)
- What happens if Liveness probe fails? Pod is restarted (killed and recreated)
- What happens if both probes fail? Liveness kills it, but it's already out of Service from Readiness
- Why does the bridge need `server.forward-headers-strategy: framework` for Edge TLS? Without it, Spring generates redirect URLs with `http://` instead of `https://`, breaking OIDC

---

## Stage 5: MAS Architecture

**Time:** 3 weeks
**Goal:** Understand how MAS is structured on OpenShift, how Core IDP works, and how applications are configured.

### Concepts to Learn

| Concept | Why It Matters | How to Find It |
|---------|----------------|----------------|
| MAS instance naming | `inst1` = instance 1 | `oc get projects` → `mas-inst1-*` |
| Core IDP | Central OIDC provider for all MAS apps | `oc describe deployment inst1-coreidp -n mas-inst1-core` |
| Core IDP Login | The "mas-login" React SPA | `oc describe deployment inst1-coreidp-login -n mas-inst1-core` |
| MAS OIDC config | ConfigMap with upstream OIDC provider definitions | `oc get configmap mas-multi-oidc -n mas-inst1-core -o yaml` |
| SAML config | ConfigMap with SAML provider definitions | `oc get configmap mas-multi-saml-sp -n mas-inst1-core` |
| Facilities appserver | TRIRIGA Liberty server | `oc describe statefulset inst1-main-appserver -n mas-inst1-facilities` |
| Liberty OIDC client | How TRIRIGA talks to Core IDP | Decode the `oidc.xml` from the credentials secret |
| Entity managers | Configuration managers for DB, Mongo, Kafka, etc. | `oc get pods -n mas-inst1-core` (15+ `inst1-entitymgr-*` pods) |
| MAS internal API | mTLS-protected APIs | `MAS_INTERNAL_API_HOST: internalapi.mas-inst1-core.svc` |

### Practical Exercises

```powershell
# 1. Map the full MAS environment
oc get routes --all-namespaces | Select-String "inst1"

# 2. Trace the OIDC chain by looking at each component's config
#    a. Find Facilities Liberty's OIDC config
#    b. Find Core IDP's OIDC provider config
#    c. Find the bridge's registered client

# 3. Follow the redirect chain manually with curl
curl -skv "https://main.facilities.<instance>.<cluster>/login"
# → 302 to auth.<instance>.<cluster>/oidc/endpoint/MaximoAppSuite/authorize?state=...

curl -skv "https://auth.<instance>.<cluster>/oidc/endpoint/MaximoAppSuite/authorize?scope=openid&..."
# → 302 to /login/ (mas-login SPA) or 302 to bridge (if configured)

curl -skv "https://auth.<instance>.<cluster>/login/"
# → "mas-login" React SPA HTML

# 4. Decode the Liberty OIDC client config
$secret = oc get secret -n mas-inst1-facilities | findstr "credentials-oauth"
$b64 = (oc get secret $secret[0] -n mas-inst1-facilities -o jsonpath="{.data.oidc\.xml}")
[System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($b64))
# → See <openidConnectClient issuerIdentifier="..."> in XML
```

### The Critical Discovery

When we decoded the Liberty OIDC client config, we found:
```xml
issuerIdentifier="https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite"
```

This told us: **TRIRIGA Liberty does NOT talk to the bridge directly.** It talks to the MAS Core IDP. The bridge is one of Core IDP's upstream identity providers.

**This killed our entire approach of generating auth codes from the bridge and passing them to Liberty.** Liberty would try to redeem those codes at the Core IDP's token endpoint (not the bridge's).

### Check Your Understanding

- How many Liberty servers are in a standard MAS deployment with Facilities + Manage? (At least 3: Core IDP, Facilities appserver, Manage foundation)
- What's the purpose of the 15+ `entitymgr-*` pods in `mas-inst1-core`?
- Why does Facilities use a StatefulSet while the bridge uses a Deployment?
- How would you find the Core IDP's issuer URL without looking at the Liberty config? (Hint: `oc describe ... coreidp` shows env vars)

---

## Stage 6: CXF SOAP & TRIRIGA Auth Internals

**Time:** 2 weeks
**Goal:** Understand SOAP web services, how TRIRIGA authenticates, and why SOAP HTTP Basic is the most reliable method.

### Concepts to Learn

| Concept | Why It Matters | File Reference |
|---------|----------------|----------------|
| WSDL → Java codegen | CXF generates Java interfaces from WSDL | `pom.xml:89-126` (cxf-codegen-plugin) |
| CXF Service class | `new TririgaWS(wsdlUrl).getTririgaWSPort()` | `TririgaWsClient.java:138-140` |
| `BindingProvider.ENDPOINT_ADDRESS_PROPERTY` | Override the SOAP endpoint URL | `TririgaWsClient.java:190` |
| `Message.PROTOCOL_HEADERS` | Set HTTP headers (Authorization, Cookie) | `TririgaWsClient.java:195-200` |
| `SESSION_MAINTAIN_PROPERTY` | Tell CXF to track cookies | `TririgaWsClient.java:191` |
| `HTTPConduit` | Configure CXF transport (TLS, timeouts) | `TririgaWsClient.java:203-221` |
| `HttpURLConnection` | Raw HTTP client for REST calls | `LocalMockController.java:129-153` |
| `RestTemplate` | Spring's HTTP client | `MasApiClient.java:43` |

### How TRIRIGA Auth Works (From Decompiled Code)

We decompiled `ibm-tririga.jar` (approx 42 classes) to find the auth logic:

```
1. HTTP Request arrives at /ws/TririgaWS
2. AuthenticationFilter reads: req.getHeader("Authorization")
   → "Basic <base64-encoded-username:password>"
3. Decodes base64 → username:password
4. Calls MASSignonService.signOn(username, password)
5. MASSignonService calls MASInternalAPI.isUserAuthenticated(credentials)
   → This is an mTLS (mutual TLS) call to:
     https://internalapi.mas-inst1-core.svc:443/v3/users/checkauthentication
6. Core IDP validates credentials, returns success + user info
7. TRIRIGA creates HTTP session
8. Response includes Set-Cookie: JSESSIONID=<session-id>
```

**Why other auth methods failed:**

| Method | What We Sent | What Happened |
|--------|-------------|---------------|
| SOAP `BasicChallenge` header | `<h:BasicChallenge>...` inside SOAP body | TRIRIGA ignores it — reads HTTP header only |
| `Authorization: Bearer` on REST | Bearer token in header | Liberty OIDC filter intercepts → redirect to login |
| `Authorization: Basic` on REST | Basic auth on non-SOAP URL | Liberty OIDC filter intercepts → redirect to login |
| `Authorization: Basic` on SOAP | Basic auth on `/ws/TririgaWS` | **Works** — SOAP endpoint has own auth filter |
| Plain cookie (no auth) | `Cookie: JSESSIONID=...` | Works IF session is already authenticated |

### Mini-Projects

| # | Project | What You'll Learn |
|---|---------|-------------------|
| 1 | Download a public WSDL (e.g., from a weather service), generate Java with CXF | WSDL codegen |
| 2 | Write a SOAP client that calls a public service | CXF Service class, port creation |
| 3 | Add HTTP Basic auth to your SOAP client | `Message.PROTOCOL_HEADERS` |
| 4 | Extract JSESSIONID from `Set-Cookie` after SOAP call | Response header parsing |
| 5 | Use the JSESSIONID to make an authenticated REST call | Cookie header in REST client |

### Check Your Understanding

- Why does SOAP auth work while REST auth fails? → Different URL patterns, different filter configurations
- What does `SESSION_MAINTAIN_PROPERTY` do? → Tells CXF to automatically include cookies from previous responses
- Why can't we use `HttpsURLConnection` for the SOAP call? → The TRIRIGA WSDL is complex and requires proper XML namespace handling — CXF handles this
- What would happen if we called the SOAP endpoint without HTTP Basic? → It would return the SOAP fault equivalent of "401 Unauthorized" or a redirect

---

## Stage 7: Entra ID & Microsoft Graph API

**Time:** 1 week
**Goal:** Configure Azure AD app registrations, handle token claims, and use Microsoft Graph API as a fallback.

### Concepts to Learn

| Concept | Why It Matters | File Reference |
|---------|----------------|----------------|
| App Registration | Identifies the bridge to Entra ID | `AZURE_CLIENT_ID` in ConfigMap |
| Client Secret | Password for the app registration | `AZURE_CLIENT_SECRET` in Secret |
| Redirect URI | Where Entra ID sends users after auth | `application-openshift.yml:15` |
| Token claims | `groups`, `preferred_username`, `email` | `AcsHandlerController.java:428-444` |
| 200-group limit | JWT can only hold 200 groups | Why Graph API fallback exists |
| `_claim_names` overage | Indicator for >200 groups | Triggers Graph API fallback |
| Graph API `/me/memberOf` | Resolve all groups server-side | `EntraIdGroupResolver.java:54-58` |

### Mini-Projects

| # | Project | What You'll Learn |
|---|---------|-------------------|
| 1 | Create an App Registration in Azure Portal | Navigate Azure AD, understand app types |
| 2 | Add a redirect URI + enable ID tokens | OIDC configuration |
| 3 | Add Microsoft Graph API permissions | Delegated vs application permissions |
| 4 | Set `groupMembershipClaims: SecurityGroup` | JWT group claims |
| 5 | Decode a JWT from Azure AD on jwt.io | See the claims structure |
| 6 | Call Graph API `/me/memberOf` with a Bearer token | REST + Bearer auth |

### Check Your Understanding

- What's the difference between `groupMembershipClaims: SecurityGroup` and `groupMembershipClaims: All`?
- Why does the bridge need both `GroupMember.Read.All` AND `User.Read` permissions?
- What happens when a user is in 300 groups and the JWT only has 200 groups?
- Which grant type is used for the Graph API call, and where does the Bearer token come from?

---

## Stage 8: Debugging Methodology

**Time:** Ongoing (applied across all previous stages)
**Goal:** Develop a systematic approach to investigating and fixing issues in distributed systems.

### The Four-Step Investigation Loop

```
┌─────────────────────────────────────────┐
│  1. STATE A HYPOTHESIS                  │
│     "I think Liberty will accept an     │
│      auth code from the bridge"         │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│  2. TEST THE HYPOTHESIS                 │
│     curl -skv "https://.../redirect/    │
│         facilities?code=X&state=Y"      │
│     → HTTP 500 CWOAU0073E              │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│  3. COMPARE WITH EXPECTATION           │
│     Expected: HTTP 302 with JSESSIONID │
│     Got: HTTP 500 with error           │
│     → Hypothesis is WRONG              │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│  4. INVESTIGATE WHY                     │
│     oc get secret ... oidc.xml         │
│     → Liberty's issuer = Core IDP      │
│     → Bridge's issuer ≠ Core IDP       │
│     → "Ah, Liberty expects codes FROM  │
│        Core IDP, not from bridge"      │
└────────────────┬────────────────────────┘
                 │
                 ▼
          Back to Step 1
          with new hypothesis
```

### Real Example from Our Investigation

| Step | What Happened | What We Did |
|------|--------------|-------------|
| 1 | "TRIRIGA redirects to Liberty on GET /app/tririga" | `curl -skv "https://main.facilities.../app/tririga"` |
| 2 | Got HTTP 200 with React SPA, not 302 | Contradicted hypothesis |
| 3 | New hypothesis: "SPA JavaScript redirects" | Looked at HTML, found `window.doLogin()` → redirects to `/login` |
| 4 | "`/login` will redirect to Liberty" | `curl -skv "https://main.facilities.../login"` → Got 302 with `state` parameter ✅ |
| 5 | "Liberty's OIDC endpoint is the bridge's /oauth2/authorize" | Followed the 302 → found `auth.inst1...` NOT bridge |
| 6 | "auth.inst1... is an alias for the bridge" | `curl -skv "https://auth.inst1..."` → Got `x-masidp: true` — separate server |
| 7 | "Bridge code will work at Liberty's /oidcclient/redirect" | Generated code at bridge, posted to Liberty → HTTP 500 |
| 8 | "Why did Liberty reject our code?" | Decoded `oidc.xml` from secret → Liberty's issuer is Core IDP, not bridge |
| 9 | New understanding: Bridge can only feed tokens TO Core IDP, not directly TO Liberty | Changed approach to SOAP HTTP Basic |

### Essential Debugging Tools

```powershell
# 1. Verbose curl — see EVERYTHING (HTTP, TLS, redirects)
curl -skv "https://host/path" 2>&1

# 2. Show only critical headers
curl -sk -D - "https://host/path" 2>&1 | Select-String "HTTP/|location:|Set-Cookie:|< HTTP"

# 3. Follow redirects AND show headers
curl -skL -D - "https://host/path" 2>&1

# 4. Send a specific header
curl -sk -H "Cookie: JSESSIONID=abc123" "https://host/path"

# 5. Check OpenShift pod logs
oc logs deployment/ecifm-sso-bridge --tail=50
oc logs deployment/ecifm-sso-bridge --tail=20 -f  (follow mode)

# 6. Check logs matching a pattern
oc logs deployment/ecifm-sso-bridge --tail=100 | Select-String "Liberty|Step|Error"

# 7. Describe a resource — see env, volumes, probes
oc describe deployment/ecifm-sso-bridge
oc describe statefulset/inst1-main-appserver -n mas-inst1-facilities

# 8. Get YAML output of a specific field
oc get secret inst1-main-credentials-oauth-facilities-liberty -n mas-inst1-facilities `
    -o jsonpath="{.data.oidc\.xml}"

# 9. Decode base64 from command output
$b64 = "PG9wZW5pZENv..."; [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($b64))

# 10. Port-forward for local testing
oc port-forward deployment/ecifm-sso-bridge 8080:8080
# Then in another terminal: curl http://localhost:8080/test
```

### The Most Important Skill

**Learn to ask the right question by reading the error message carefully.**

- `CWOAU0073E` → "The **uniqueSecurityName** cannot be mapped to a TRIRIGA user" → The `sub` claim doesn't match any TRIRIGA person record → Need to set `uniqueSecurityName` in the token
- `HTTP 500` on `/oidcclient/redirect` → "Internal server error" → Look at Liberty's logs (but we don't have access) → Infer from architecture: state validation fails because we don't have Liberty's session cookie
- `No redirect from TRIRIGA` → "TRIRIGA returned 200, not 302" → TRIRIGA is serving a SPA, not doing server-side redirect → Need to hit different URL

---

## Learning Roadmap Summary

| Stage | Topic | Time | Key Skill You'll Have |
|-------|-------|------|----------------------|
| 1 | Spring Boot Foundation | 3 weeks | Build a configurable REST API |
| 2 | Spring Security | 3 weeks | Configure multi-chain security |
| 3 | OAuth2 & OIDC | 3 weeks | Build an OAuth2 Authorization Server |
| 4 | OpenShift & Kubernetes | 4 weeks | Deploy and debug containerized apps |
| 5 | MAS Architecture | 3 weeks | Navigate and document a MAS cluster |
| 6 | CXF SOAP & TRIRIGA Auth | 2 weeks | Authenticate via SOAP HTTP Basic |
| 7 | Entra ID & Graph API | 1 week | Configure Azure AD integration |
| 8 | Debugging Methodology | Ongoing | Systematic investigation approach |
| **Total** | | **~19 weeks** | |

## How to Use This Codebase for Learning

1. **Read the files in order:** `application-openshift.yml` → `SecurityConfig.java` → `AuthServerConfig.java` → `AcsHandlerController.java` → `TririgaWsClient.java` → `MasApiClient.java` → `LibertySessionClient.java`

2. **Trace a request end-to-end:** Start from `GET /redirect` in `AcsHandlerController.java:306`, follow every method call through `MasSyncService`, `MasApiClient`, `TririgaWsClient`

3. **Reproduce each discovery:** Run the `curl` commands from Stage 5 Practical Exercises to see the redirect chain yourself

4. **Modify and rebuild:** Change a log message, add a new `/local/test-*` endpoint, deploy with `oc start-build ecifm-sso-bridge --from-dir=. --wait`, and see your change live

5. **Break it deliberately:** Remove the `tokenCustomizer` bean and see what error Liberty returns. Change the `registeredClientRepository` redirect URI and see the flow break. **Then fix it.**
