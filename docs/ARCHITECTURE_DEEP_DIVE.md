# Architecture Deep Dive — ecifm-saml-bridge

> **Author:** Investigation by AI agent during interactive development session  
> **Date:** 2026-07-05  
> **Cluster:** NPOS2 (`api.npos2.ecifmdev.net:6443`)  
> **Project:** `ecifm-sso-bridge` (namespace: `ecifm-sso-bridge`)

---

## Table of Contents

1. [The Problem Space](#1-the-problem-space)
2. [The Full MAS Architecture](#2-the-full-mas-architecture)
3. [MAS OpenShift Projects Deep Dive](#3-mas-openshift-projects-deep-dive)
4. [The OIDC Chain — How Authentication Flows](#4-the-oidc-chain)
5. [How the Bridge Fits In](#5-how-the-bridge-fits-in)
6. [Decision Log: Every Approach Tried](#6-decision-log)
7. [Source Code Map — Every File Explained](#7-source-code-map)
8. [TRIRIGA Auth Internals (from Decompiled Code)](#8-tririga-auth-internals)
9. [OpenShift Resource Reference](#9-openshift-resource-reference)
10. [Glossary](#10-glossary)

---

## 1. The Problem Space

### 1.1 The Legacy System (Before)

```
Browser → WebSphere (ecifmSaml WAR) → ADFS (SAML) → LTPA token → TRIRIGA
```

The old system was a SAML bridge deployed as a WAR on WebSphere. It used:
- **WebSphere proprietary APIs** — `WSSubject.getCallerSubject()` to extract the authenticated user
- **LTPA tokens** — `Cookie: LtpaToken2=...` for session propagation across WebSphere cells
- **XML configuration** — `ibm-*-bnd.xml` files for WebSphere bindings
- **ADFS** — on-prem Active Directory Federation Services for SAML

### 1.2 The New Environment

The customer migrated from on-prem WebSphere to **MAS (Maximo Application Suite)** on **OpenShift**, and from ADFS to **Microsoft Entra ID** (cloud). Everything changed:

| Component | Before (On-Prem) | After (OpenShift) |
|-----------|------------------|-------------------|
| App server | WebSphere ND | WebSphere Liberty (containers) |
| Auth protocol | SAML (ADFS) | OIDC (Entra ID + MAS Core IDP) |
| Session token | LTPA | JSESSIONID + LTPA (dual) |
| Deployment | WAR on WAS | Docker container on OpenShift |
| Config | XML files | ConfigMap + Secrets |
| User store | On-prem AD | Entra ID (Azure AD) |

### 1.3 The Gap

MAS has its own SSO infrastructure (Core IDP, Keycloak-based), but the customer has a custom **SSOConnect plugin** (IConnect plugin in TRIRIGA) that needs to be called via REST API to sync user groups. The bridge fills this gap: it validates the Entra ID JWT, resolves groups (from JWT or Graph API), calls the SSOConnect API, then redirects the user to TRIRIGA.

---

## 2. The Full MAS Architecture

### 2.1 High-Level Diagram

```
                          ┌──────────────────────────────────────────┐
                          │          Internet / Users                │
                          └──────────┬───────────────────────────────┘
                                     │
                            OpenShift Router (HAProxy)
                          (Edge TLS termination for all routes)
                                     │
          ┌──────────────────────────┼──────────────────────────┐
          │                          │                          │
          ▼                          ▼                          ▼
┌──────────────────┐   ┌──────────────────────┐   ┌──────────────────────┐
│   mas-inst1-core  │   │ mas-inst1-facilities │   │  mas-inst1-manage   │
│  (MAS Core IDP)   │   │   (TRIRIGA/Liberty)  │   │   (Maximo/Manage)   │
│                   │   │                      │   │                     │
│ Routes:           │   │ Routes:              │   │ Routes:             │
│  auth.inst1...    │   │  main.facilities...  │   │  main.manage...     │
│  api.inst1...     │   │  multiagents.main... │   │  maxinst.manage...  │
│  admin.inst1...   │   │  pod-0.main...       │   │  main-foundation... │
│  home.inst1...    │   │                      │   │                     │
└──────────────────┘   └──────────────────────┘   └──────────────────────┘
         │                        │                         │
         │                        │                         │
         └────────────────────────┼─────────────────────────┘
                                  │
                     ┌────────────▼────────────┐
                     │    Shared Services      │
                     │  ┌──────────────────┐   │
                     │  │   MongoDB         │   │
                     │  │   (mas-mongo)     │   │
                     │  ├──────────────────┤   │
                     │  │   DB2             │   │
                     │  │   (db2u)          │   │
                     │  ├──────────────────┤   │
                     │  │   SLS (licensing) │   │
                     │  └──────────────────┘   │
                     └─────────────────────────┘
```

### 2.2 The Three MAS Projects

Each MAS application (suite) gets its own OpenShift namespace:

| Namespace | Application | Purpose |
|-----------|-------------|---------|
| `mas-inst1-core` | MAS Core | Identity provider, APIs, admin dashboard, licensing, user sync |
| `mas-inst1-facilities` | TRIRIGA | Facilities management (the main app users access) |
| `mas-inst1-manage` | Maximo Manage | Asset management, work orders |
| `mas-inst1-pipelines` | CI/CD | DevOps pipelines for MAS updates |

The `inst1` prefix comes from the MAS instance name — the cluster can host multiple MAS instances (inst1, inst2, etc.).

---

## 3. MAS OpenShift Projects Deep Dive

### 3.1 `mas-inst1-core` — The Core Infrastructure

```
oc get all -n mas-inst1-core
```

#### Key Pods

| Pod | Image | Purpose |
|-----|-------|---------|
| `ibm-mas-operator` | IBM MAS operator | Manages MAS resources (Custom Resource controllers) |
| `ibm-truststore-mgr` | IBM truststore manager | Manages TLS truststores across MAS components |
| `inst1-coreidp` | `cp.icr.io/cp/mas/coreidp` | **Central OIDC Provider** — Liberty-based, handles all auth |
| `inst1-coreidp-login` | `cp.icr.io/cp/mas/coreidp-login` | **Login SPA** — the "mas-login" React app users see |
| `inst1-coreapi` (x3) | MAS Core API | REST APIs for MAS internal services |
| `inst1-entitymgr-*` (x15) | Various | Entity managers for configuration (Mongo, Kafka, JDBC, SMTP, object storage, etc.) |
| `inst1-admin-dashboard` | Admin UI | MAS Admin web console |
| `inst1-internalapi` | Internal API | mTLS-protected internal APIs used by TRIRIGA |
| `inst1-navigator` | Navigator | Main MAS application navigator/homepage |

#### Routes

| Route Host | Target Service | TLS |
|------------|---------------|-----|
| `auth.inst1.apps.npos2.ecifmdev.net` | `coreidp` | reencrypt |
| `auth.inst1.apps.npos2.ecifmdev.net/login` | `coreidp-login` | reencrypt |
| `api.inst1.apps.npos2.ecifmdev.net` | `coreapi` | reencrypt |
| `admin.inst1.apps.npos2.ecifmdev.net` | `admin-dashboard` | reencrypt |
| `home.inst1.apps.npos2.ecifmdev.net` | `homepage` | reencrypt |

#### How the Core IDP is Configured

The `inst1-coreidp` deployment (from `oc describe deployment inst1-coreidp -n mas-inst1-core`):

**Environment variables:**
```yaml
DOMAIN: inst1.apps.npos2.ecifmdev.net
ISSUER_BASE_HOSTNAME: https://auth.inst1.apps.npos2.ecifmdev.net
CUSTOM_LOGIN_PAGE: https://auth.inst1.apps.npos2.ecifmdev.net/login
DEFAULT_IDP: local
LOCAL_IDP_ENABLED: "True"
JWT_KEYSTORE_PATH: /tmp/writeable/home/wiotp/ca/jwt.p12
SESSION_EXPIRATION_TIME: 12h
```

**Mounts (configuration sources):**
```
/etc/mas/oidc/config          → ConfigMap mas-multi-oidc     (OIDC provider configs)
/etc/mas/oidc/secrets/...     → Secret inst1-usersupplied-oidc-*-creds-system
/etc/mas/saml                 → ConfigMap mas-multi-saml-sp  (SAML SP configs)
/etc/mas/ltpa                 → Secret inst1-keys-ltpa       (LTPA keys)
/etc/mas/certs/mas-jwt        → Secret inst1-keys-jwt        (JWT signing keys)
```

#### OIDC Provider Configuration (`mas-multi-oidc` ConfigMap)

```yaml
oidc:
  - discoveryEndpointUrl: "https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/.well-known/openid-configuration"
    authorizationEndpointUrl: ""
    tokenEndpointUrl: ""
    userIdentifier: "preferred_username"
    issuerIdentifier: ""
    jwkEndpointUrl: ""
    tokenEndpointAuthMethod: "post"
    tokenEndpointAuthSigningAlgorithm: "RS256"
    signatureAlgorithm: "RS256"
    credentials: "inst1-usersupplied-oidc-bridge-creds-system"
    configId: "default-oidc"
```

This is the CRITICAL finding. The MAS Core IDP is configured to use the bridge as an OIDC identity provider (configId: "default-oidc"). When a user logs in via the "default-oidc" provider, the Core IDP redirects to:

```
https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/.well-known/openid-configuration
```

...and uses the bridge's OIDC authorization server for authentication.

**OIDC Bridge Credentials** (from `inst1-usersupplied-oidc-bridge-creds-system`):
```yaml
clientId: "mas-facilities"       # The OIDC client registered in the bridge
clientSecret: "fMkWmZx8qZ8d9R/f+40lWrZJlTCzpwprxGqoWxXURU4="
```

**Default OIDC Credentials** (from `inst1-usersupplied-oidc-default-creds-system`):
```yaml
clientId: "cbcea157-2c35-4ce3-b86c-782282e00857"  # Same as AZURE_CLIENT_ID
clientSecret: "..."                                   # Entra ID client secret
```

#### What This Means

The Core IDP has MULTIPLE identity sources:
1. **OIDC: "default-oidc"** → The Bridge → Entra ID (for users)
2. **SAML** → Possibly another Entra ID or ADFS connection
3. **Local** → Username/password login page

The "default-oidc" configId is the one used by the "Microsoft" button on the mas-login SPA.

### 3.2 `mas-inst1-facilities` — TRIRIGA

```
oc get all -n mas-inst1-facilities
```

#### Key Pods

| Pod | Type | Purpose |
|-----|------|---------|
| `inst1-main-appserver-0` | StatefulSet | **The TRIRIGA Liberty server** — runs the main application |
| `inst1-main-multiagents` | Deployment | TRIRIGA multi-agent (background jobs, workflows) |
| `inst1-entitymgr-ws` | Deployment | Entity manager for the workspace |
| `inst1-usersyncagent` | Deployment | User sync agent |
| `ibm-mas-facilities-operator` | Deployment | MAS operator specific to facilities |

#### Routes

| Route Host | Target | TLS |
|------------|--------|-----|
| `main.facilities.inst1.apps.npos2.ecifmdev.net` | `inst1-main-appserver` | reencrypt |
| `pod-0.main.facilities.inst1.apps.npos2.ecifmdev.net` | `inst1-main-appserver-0` | reencrypt |
| `multiagents.main.facilities.inst1.apps.npos2.ecifmdev.net` | `inst1-main-multiagents` | reencrypt |

#### The TRIRIGA Liberty Server (StatefulSet)

From `oc describe statefulset inst1-main-appserver -n mas-inst1-facilities`:

**Environment:**
```yaml
FRONT_END_SERVER: main.facilities.inst1.apps.npos2.ecifmdev.net
BIRT_SVC: inst1-main-birt.mas-inst1-facilities.svc.cluster.local
DOMAIN_NAME: inst1.apps.npos2.ecifmdev.net
MAS_INTERNAL_API_HOST: internalapi.mas-inst1-core.svc
MAS_INTERNAL_API_PORT: 443
TRIRIGA_MODE: main
MIN_CONN_POOL_SIZE: 10
MAX_CONN_POOL_SIZE: 200
```

**Mounts:**
```
/etc/db-secret              → inst1-main-jdbccfg-workspace-application-binding (DB2 credentials)
/etc/sso-config             → inst1-main-credentials-oauth-facilities-liberty (Liberty OIDC client config)
/etc/server-xml-ext         → inst1-facilities-lexml--sn (Liberty server.xml extensions)
/etc/services-tls           → inst1-main-liberty-facilities-tls (TLS certs)
/etc/integration-truststore → inst1-main-truststore (Truststore for integration)
/etc/smtp-secret            → inst1-main-smtpcfg-system-binding
/etc/re-vault               → inst1-facilities-vs--sn (Vault for secrets)
```

#### Liberty OIDC Client Configuration

This is the MOST IMPORTANT finding. The secret `inst1-main-credentials-oauth-facilities-liberty` contains the Liberty OIDC client configuration as an XML file:

```xml
<openidConnectClient
  clientId="facilities"
  clientSecret="YxuA5JuDOnIFamGqtIhguFfqsCDnIRSo"
  id="facilities"
  issuerIdentifier="https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite"
  tokenEndpointUrl="https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite/token"
  jwkEndpointUrl="https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite/jwk"
  authorizationEndpointUrl="https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite/authorize"
  scope="openid"
  signatureAlgorithm="RS256"
  headerName="x-access-token"
  includeIdTokenInSubject="true"
  isClientSideRedirectSupported="false"
  accessTokenInLtpaCookie="true"
  mapIdentityToRegistryUser="false"
>
</openidConnectClient>
```

**Critical observations:**

| Attribute | Value | Meaning |
|-----------|-------|---------|
| `issuerIdentifier` | `auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite` | The issuer is the MAS Core IDP, **NOT** the bridge |
| `authorizationEndpointUrl` | `.../authorize` | Auth requests go to the Core IDP |
| `tokenEndpointUrl` | `.../token` | Token exchanges go to the Core IDP |
| `jwkEndpointUrl` | `.../jwk` | JWKS is fetched from the Core IDP |
| `clientId` | `facilities` | The client ID used by TRIRIGA Liberty |
| `accessTokenInLtpaCookie` | `true` | OIDC access token is propagated as LTPA cookie |
| `headerName` | `x-access-token` | Access token is passed in this HTTP header |
| `includeIdTokenInSubject` | `true` | ID token claims are included in the WSSubject |

**Why our approach failed:**

When we generated an authorization code from the bridge (at `ecifm-sso-bridge.../oauth2/authorize`) and passed it to TRIRIGA Liberty's `/oidcclient/redirect/facilities?code=...&state=...`, Liberty tried to:
1. Look up the `state` in its session → failed (no session cookie)
2. Exchange the `code` at `tokenEndpointUrl` (which is `auth.inst1.../token`, NOT the bridge)
3. Validate the `iss` claim against `issuerIdentifier` (which is `auth.inst1...`)

The bridge's code was never going to be accepted by Liberty because Liberty's OIDC client is configured to talk to the MAS Core IDP, not the bridge.

### 3.3 `mas-inst1-manage` — Maximo Manage

```
oc get all -n mas-inst1-manage
```

#### Key Pods

| Pod | Purpose |
|-----|---------|
| `inst1-main-foundation` | Maximo foundation — the core Manage application |
| `inst1-main-manage-maxinst` | Maximo instance — handles specific Manage instance logic |
| `inst1-entitymgr-*` | Entity managers for Manage configuration |
| `inst1-monitoragent` | Monitoring agent |
| `ibm-mas-manage-operator` | Manage-specific operator |
| `ibm-mas-imagestitching-operator` | Image stitching for Manage |

#### Routes

| Route Host | Target |
|------------|--------|
| `main.manage.inst1.apps.npos2.ecifmdev.net` | `inst1-main-foundation` |
| `main-foundation.manage.inst1.apps.npos2.ecifmdev.net` | `inst1-main-foundation` |
| `maxinst.manage.inst1.apps.npos2.ecifmdev.net` | `inst1-main-maxinst` (ERD and tools) |

Manage is a separate MAS application that shares the same Core IDP for authentication. A separate OIDC client configuration would exist for Manage, pointing to the same `auth.inst1.../oidc/endpoint/MaximoAppSuite` issuer.

---

## 4. The OIDC Chain

### 4.1 Full Authentication Flow (Browser)

```
Step 1: User visits TRIRIGA
        GET https://main.facilities.inst1.apps.npos2.ecifmdev.net/app/tririga
        ↓
        TRIRIGA returns React SPA (HTTP 200) — JavaScript executes window.doLogin()
        ↓
Step 2: JavaScript redirects to /login
        GET https://main.facilities.inst1.apps.npos2.ecifmdev.net/login
        ↓
        Liberty OIDC filter intercepts → 302 redirect
        ↓
Step 3: Liberty sends OIDC authorization request to MAS Core IDP
        302 → https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite/authorize
              ?scope=openid
              &response_type=code
              &client_id=facilities
              &redirect_uri=https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities
              &state=<liberty-state>
        ↓
        (Liberty also sets cookies: JSESSIONID, WASOidcState..., WASReqURLOidc...)
        ↓
Step 4: MAS Core IDP receives the request, checks for session
        If no session → serves the mas-login SPA
        ↓
Step 5: User clicks "Microsoft" button (OIDC default-oidc provider)
        ↓
Step 6: MAS Core IDP redirects to the configured OIDC provider (the bridge)
        302 → https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/oauth2/authorize
              ?response_type=code
              &client_id=mas-facilities
              &redirect_uri=https://auth.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/default-oidc
              &scope=openid+profile+email
        ↓
Step 7: Bridge receives the request, checks for session
        If no session → 302 redirect to Entra ID
        ↓
Step 8: User authenticates with Microsoft Entra ID
        ↓
Step 9: Entra ID redirects back to bridge:
        POST /login/oauth2/code/entra-id?code=...
        ↓
Step 10: Bridge exchanges code, creates OidcUser, redirects back to authorization endpoint
         ↓
Step 11: Bridge generates authorization code, redirects to Core IDP:
         302 → https://auth.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/default-oidc?code=...&state=...
         ↓
Step 12: Core IDP exchanges code at bridge's /oauth2/token, gets id_token
         ↓
Step 13: Core IDP creates local session, generates ITS OWN authorization code
         302 → https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities?code=...&state=...
         ↓
Step 14: Liberty (TRIRIGA) exchanges code at Core IDP's /token, validates id_token
         ↓
Step 15: Liberty creates TRIRIGA session, sets JSESSIONID + LTPA cookies
         ↓
Step 16: User lands on TRIRIGA home page, authenticated
```

### 4.2 The Chain as a Sequence Diagram

```
User          TRIRIGA SPA     Liberty(Tri)    MAS Core IDP        Bridge          Entra ID
 │                │               │                │                 │               │
 │──GET /app─────▶│               │                │                 │               │
 │◀──SPA HTML─────│               │                │                 │               │
 │──JS: /login───▶│               │                │                 │               │
 │                │──GET /login─▶│                │                 │               │
 │                │              │──authorize───▶│                 │               │
 │                │              │                │──mas-login SPA  │               │
 │◀──mas-login────│              │                │                 │               │
 │──"Microsoft"──▶│              │                │                 │               │
 │                │              │                │──authorize────▶│               │
 │                │              │                │                 │──authorize──▶│
 │                │              │                │                 │◀────auth─────│
 │                │              │                │◀──code+state───│               │
 │                │              │◀──code+state──│                 │               │
 │                │              │◀──session────│                 │               │
 │◀──JSESSIONID──│              │                │                 │               │
```

### 4.3 The Two OIDC Client Configurations

There are TWO distinct OIDC client configurations in this chain:

| Client ID | Owner | Configured In | Points To |
|-----------|-------|---------------|-----------|
| `facilities` | TRIRIGA Liberty | Secret: `inst1-main-credentials-oauth-facilities-liberty` | MAS Core IDP (`auth.inst1...`) |
| `mas-facilities` | Bridge OAuth2 AS | `AuthServerConfig.java` in bridge code | Bridge itself (self) |
| `mas-facilities` (also) | MAS Core IDP | ConfigMap: `mas-multi-oidc` (as provider) | Bridge (`ecifm-sso-bridge...`) |

**This is the critical insight:** The `facilities` client in Liberty points to the Core IDP, not the bridge. The bridge's `mas-facilities` client is used by the Core IDP (as an upstream OIDC provider), not by Liberty directly.

---

## 5. How the Bridge Fits In

### 5.1 The Bridge's Two Roles

The bridge plays TWO roles in the architecture:

**Role 1: OIDC Authorization Server** — Issues tokens that Liberty/Core IDP can validate
- Endpoints: `/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/.well-known/openid-configuration`
- Registered client: `mas-facilities`
- Token customizer: sets `sub`, `preferred_username`, `uniqueSecurityName` from Entra ID OidcUser
- Used by: MAS Core IDP (as upstream OIDC provider at configId "default-oidc")

**Role 2: SAML Bridge + SSOConnect Client** — The original purpose
- Validates Entra ID JWTs (`/redirect` endpoint)
- Resolves groups via Microsoft Graph API (if JWT has >200 groups)
- Calls TRIRIGA's SSOConnect API via SOAP-obtained JSESSIONID
- Redirects user to TRIRIGA after sync

### 5.2 Role 1 Flow (OIDC Authorization Server)

```
MAS Core IDP ──GET /oauth2/authorize──▶ Bridge (AS filter chain)
                                            │ No session?
                                            ▼
                                     Redirect to Entra ID (via OAuth2 client chain)
                                            │
                                     User authenticates
                                            │
                                     Back to /oauth2/authorize with session
                                            │
                                     Bridge generates auth code
                                            │
                             302 ───────────▶ Core IDP's /oidcclient/redirect/default-oidc
                                            │
                                     Core IDP exchanges code at bridge
                                     POST /oauth2/token?code=...
                                            │
                                     Bridge returns id_token + access_token
                                            │
                                     Core IDP validates, creates session
```

### 5.3 Role 2 Flow (SSOConnect Client)

```
User ──GET / ──▶ Bridge
                     │ 302 to /redirect
                     ▼
                /redirect checks OAuth2 login
                     │
                Spring Security redirects to Entra ID
                     │
                User authenticates
                     │
                Back to /redirect with OidcUser + OAuth2AuthorizedClient
                     │
                1. Extract email from OidcUser
                2. Extract groups from access_token JWT
                3. If no groups → call Microsoft Graph API /me/memberOf
                4. Call MasSyncService.syncUser()
                     │
                     ▼
                TririgaWsClient.getAuthenticatedSessionId()
                     │ SOAP + HTTP Basic
                     ▼
                TRIRIGA returns JSESSIONID
                     │
                     ▼
                MasApiClient.syncUserGroups(email, groupName)
                     │ Cookie: JSESSIONID=...
                     ▼
                TRIRIGA SSOConnect API → syncs user groups
                     │
                User redirected to TRIRIGA home page
```

### 5.4 Why the Bridge's OIDC Authorization Server Can't Directly Give Liberty a Session

The bridge generates OIDC tokens for the MAS Core IDP. For TRIRIGA Liberty to accept a session, the flow MUST go through:

1. Liberty's OIDC client → Core IDP → Bridge → Entra ID → Bridge → Core IDP → Liberty

The bridge cannot skip the Core IDP because:
- Liberty's `openidConnectClient` has `issuerIdentifier` = Core IDP, not bridge
- Liberty's token exchange URL = Core IDP's `/token`, not bridge's
- The `state` parameter validation requires Liberty's session cookie (which is scoped to `*.facilities.*` domain)

---

## 6. Decision Log

### 6.1 Approach A: Hardcoded Credentials (Working — Current State)

| Aspect | Detail |
|--------|--------|
| **What** | Store TRIRIGA username/password in OpenShift Secrets, use HTTP Basic auth on SOAP calls |
| **How** | `TririgaWsClient.createPort()` sends `Authorization: Basic <base64>` header |
| **Why it works** | TRIRIGA's SOAP endpoint (`/ws/TririgaWS`) accepts HTTP Basic auth natively — the AuthenticationFilter reads `req.getHeader("Authorization")` |
| **Pros** | Simple, reliable, proven (HTTP 200 with every call) |
| **Cons** | Requires storing credentials; password rotation requires Secret update |
| **Status** | ✅ **CURRENTLY IN USE** |

### 6.2 Approach B: OIDC Client Credentials Grant (Failed)

| Aspect | Detail |
|--------|--------|
| **What** | Use `client_credentials` grant on bridge's `/oauth2/token` to get a Bearer token, pass to Liberty |
| **How** | `OidcTokenClient.java` — created id_token via bridge's OAuth2 AS, POSTed to Liberty |
| **Why it failed** | `client_credentials` produces tokens with `sub=mas-facilities` (the client ID), not the user's email. Liberty returned `CWOAU0073E` — can't map `sub` to TRIRIGA user |
| **Lesson** | `client_credentials` is for machine-to-machine, not user auth. Need `authorization_code` flow |
| **Status** | ❌ REMOVED (`OidcTokenClient.java` deleted) |

### 6.3 Approach C: Bridge-Generated Auth Code (Failed)

| Aspect | Detail |
|--------|--------|
| **What** | Bridge creates an authorization code via `OAuth2AuthorizationService`, passes to Liberty's `/oidcclient/redirect/facilities?code=...&state=...` |
| **How** | `LibertySessionClient.java` (original) — full OIDC redirect simulation |
| **Why it failed** | 1. Liberty validates `state` against its own JSESSIONID cookie (scoped to `*.facilities.*` domain) — can't set from bridge<br>2. Liberty's `tokenEndpointUrl` is Core IDP (`auth.inst1.../token`), not bridge — so Liberty tries to redeem the code at the wrong endpoint |
| **Lesson** | Liberty's OIDC client is hard-configured to talk to Core IDP, not bridge. **Architecture constraint** |
| **Status** | ❌ REMOVED (`LibertySessionClient.java` simplified to SOAP-only) |

### 6.4 Approach D: SOAP Auth Only (Decision)

| Aspect | Detail |
|--------|--------|
| **What** | Use SOAP HTTP Basic auth for all TRIRIGA calls. Accept that credentials are needed. |
| **How** | `TririgaWsClient.getAuthenticatedSessionId()` — SOAP call → extract JSESSIONID → use for REST |
| **Why chosen** | It's the only approach that works reliably. The OIDC chain is too complex and architecturally constrained. |
| **Cost** | Credentials in Secrets; rotation process needed |
| **Future** | Could be replaced if Liberty is reconfigured to trust the bridge's OIDC tokens directly (requires changing Liberty's `issuerIdentifier` to the bridge) |
| **Status** | ✅ **CURRENTLY IN USE** (encapsulated behind `LibertySessionClient.getSessionId()`) |

### 6.5 Key Architecture Constraints Discovered

| Discovery | How We Found It | Impact |
|-----------|-----------------|--------|
| TRIRIGA returns SPA (200), not 302 redirect | `curl -v https://main.facilities.../app/tririga` | Redirect simulation must start at `/login`, not `/app/tririga` |
| Liberty OIDC client configured for Core IDP, not bridge | `oc get secret inst1-main-credentials-oauth-facilities-liberty -o yaml` → decoded `oidc.xml` | Bridge-generated codes will never work at Liberty |
| Core IDP has bridge as OIDC provider | `oc get configmap mas-multi-oidc -o yaml` | Bridge's OIDC AS is used by Core IDP, not Liberty directly |
| `auth.inst1...` is a separate server, not the bridge | `curl -v https://auth.inst1...` → `x-masidp: true` header | There are TWO Liberty instances: Core IDP + Facilities |
| Liberty validates state in its own session | Error logs: "No redirect from Liberty to bridge" + cookie analysis | State parameter can't be injected from outside the TRIRIGA domain |

---

## 7. Source Code Map — Every File Explained

### 7.1 Package: `com.ecifm.saml.bridge`

```
src/main/java/com/ecifm/saml/bridge/
├── EcifmSamlBridgeApplication.java    # Spring Boot entry point, logs OAuth2 config at startup
│
├── config/
│   ├── SecurityConfig.java            # 3 SecurityFilterChain beans (AS, local, OAuth2 login)
│   ├── AuthServerConfig.java          # OAuth2 Authorization Server config (RSA keys, client reg, token customizer)
│   └── CxfConfig.java                 # CXF HTTP transport factory registration
│
├── controller/
│   ├── AcsHandlerController.java      # Main SSO flow: /redirect, /test, /test-soap, debug endpoints
│   └── LocalMockController.java       # Local testing: /local/mock-sso, /local/test-oslc, /local/test-liberty-session
│
├── service/
│   ├── MasSyncService.java            # Orchestrates group sync (JWT groups → Graph API fallback → MAS)
│   ├── MasApiClient.java              # REST client for SSOConnect API (uses JSESSIONID from SOAP)
│   ├── TririgaWsClient.java           # CXF SOAP client for TririgaWS (HTTP Basic auth, JSESSIONID extraction)
│   ├── EntraIdGroupResolver.java      # Microsoft Graph API /me/memberOf group resolver
│   └── LibertySessionClient.java      # Thin wrapper → delegates to TririgaWsClient (was OIDC simulator)
│
├── model/
│   └── UserInfo.java                  # Simple POJO: email + List<String> groups
│
└── resources/
    ├── application-openshift.yml      # OpenShift profile config (env var bindings)
    ├── logback-spring.xml             # Logging configuration
    └── wsdl/TririgaWS.wsdl            # TRIRIGA SOAP web service WSDL
```

### 7.2 Detailed File Breakdown

#### `SecurityConfig.java` — 3 Filter Chains

```java
@Order(1) // OAuth2 Authorization Server
- Matches: /oauth2/*, /.well-known/*
- Uses: OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http)
- Auth entry point: /oauth2/authorization/entra-id (redirects to Entra ID)

@Order(2) // Local test endpoints
- Matches: /local/**
- Auth: permitAll (no authentication required)
- Session: IF_REQUIRED (needed for OAuth2 auth code flow)

@Order(3) // Everything else
- Matches: all other requests
- Auth: authenticated (redirects to Entra ID via OAuth2 login)
- Public: /actuator/health/**, /test, /test-soap
```

#### `AuthServerConfig.java` — OIDC Authorization Server

| Bean | Purpose | Detail |
|------|---------|--------|
| `registeredClientRepository()` | Creates the `mas-facilities` OIDC client | Client ID: `${mas.oidc.client-id}`, Secret: `{noop}${mas.oidc.client-secret}`, Grants: `authorization_code` + `client_credentials`, Redirect URIs: TRIRIGA redirect + OAuth client redirect |
| `jwkSource()` | RSA 2048-bit key pair for signing tokens | Generated at startup (ephemeral — changes on pod restart) |
| `authorizationService()` | In-memory OAuth2 authorization storage | Stores auth codes, access tokens, etc. |
| `tokenCustomizer()` | Customizes id_token claims | Sets `sub` = user email, adds `preferred_username`, `uniqueSecurityName` |

**Critical:** The `tokenCustomizer` has a fallback for when `getPrincipal()` is not an `OAuth2AuthenticationToken` — it uses `principal.getName()` as `sub`.

#### `TririgaWsClient.java` — SOAP Client

Key methods:

- **`createPort(String jsessionId)`** — Creates CXF SOAP port with:
  - `Authorization: Basic <base64(user:pass)>`
  - Optional `Cookie: JSESSIONID=...`
  - TLS: trust-all certs, CN-check disabled
  - Timeout: 30s connect, 120s receive

- **`getAuthenticatedSessionId()`** — Authentication flow:
  1. Calls `createPort()` (no pre-existing session)
  2. Invokes `getApplicationInfo()` — Liberty authenticates via HTTP Basic
  3. Reads `Set-Cookie` from CXF response context
  4. Extracts `JSESSIONID` via regex
  5. Returns JSESSIONID (or null)

- **`getApplicationInfo(String preAuthJsessionId)`** — Verify a session works by calling SOAP with existing cookie

#### `MasApiClient.java` — SSOConnect REST Client

- **`syncUserGroups(userName, groupName)`**:
  1. Calls `tririgaWsClient.getAuthenticatedSessionId()` to get fresh JSESSIONID
  2. Builds URL: `${mas.base-url}${mas.context}${mas.rest-api}` (e.g., `.../html/en/default/rest/SSOConnect?userName=...&adGroupName=...`)
  3. Sends `Cookie: JSESSIONID=...` header
  4. Uses Spring `RestTemplate` with SSL disabled globally

#### `EntraIdGroupResolver.java` — Graph API Fallback

Calls `GET https://graph.microsoft.com/v1.0/me/memberOf?$select=displayName` with the user's Bearer token. Parses JSON response to extract group display names. Only called when JWT has no groups (or overage indicator).

#### `MasSyncService.java` — Orchestrator

```java
syncUser(bearerToken, email, jwtGroups):
  groups = jwtGroups (from JWT) ?? entraIdGroupResolver.resolveGroups(bearerToken)
  groupName = groups.stream().join(",")
  masApiClient.syncUserGroups(email, groupName)
```

#### `AcsHandlerController.java` — Main SSO Flow

| Endpoint | Method | Description |
|----------|--------|-------------|
| `GET /` | `defaultLanding()` | 302 redirect to `/redirect` |
| `GET /redirect` | `ssoRedirect()` | Validates OidcUser, extracts email/groups, calls MasSyncService, builds HTML status page with redirect to TRIRIGA |
| `GET /test` | `test()` | Returns "ecifm-saml-bridge is running" |
| `GET /test-soap` | `testSoap()` | (Requires auth) Calls `tririgaWsClient.getApplicationInfo()` |
| `GET /local/test-soap` | `localTestSoap()` | (No auth) Same as above |
| `GET /local/test-internal` | `localTestInternal()` | Tests 3 auth methods: no auth, custom headers, HTTP Basic |
| `GET /local/test-raw` | `localTestRaw()` | Two-step: get cookie, then SOAP with auth |

---

## 8. TRIRIGA Auth Internals

Based on analysis of decompiled TRIRIGA classes from `ibm-tririga.jar`:

### 8.1 Authentication Flow

```
HTTP Request arrives
        │
        ▼
WebPageAuthenticationInterceptor (intercepts all web requests)
        │
        ▼
Checks: Does session have "userSession" attribute?
        │
        ├── YES → Proceed to page
        │
        └── NO  → UserSessionFilter.populateUserIdInSession()
                    │
                    ▼
               Checks WSSubject.getRunAsSubject()
                    │
                    ├── SET (OIDC path) → Creates user session from WSSubject
                    │
                    └── NOT SET → Checks Authorization header
                                   │
                                   ├── Basic → MASSignonService.signOn()
                                   │            → MASInternalAPI.isUserAuthenticated()
                                   │              (mTLS call to Core IDP)
                                   │
                                   ├── Bearer → OAuthService.validateToken()
                                   │
                                   └── None → 302 redirect to login
```

### 8.2 Key Decompiled Classes

| Class | Location | Purpose |
|-------|----------|---------|
| `MASSignonService.getUserIdFromRequest()` | tririga WS | Reads user from session → WSSubject → Authorization header (in that order) |
| `OAuthService` | tririga WS | Handles the Liberty OIDC callback, validates tokens |
| `WebPageAuthenticationInterceptor` | tririga WS | Intercepts all web requests, checks session |
| `AuthenticationHandler.handleXAccessXRefreshTokens()` | tririga WS | Sets `x-access-token` and `x-refresh-token` cookies after successful auth |
| `UserSessionFilter` | tririga WS | Populates user session from WSSubject |
| `MASInternalAPI.checkAuthentication()` | MAS Core API | mTLS call to Core IDP at `/v3/users/checkauthentication` |

### 8.3 Why SOAP HTTP Basic Works

The SOAP endpoint (`/ws/TririgaWS`) has a different security filter than the REST endpoints. TRIRIGA's `AuthenticationFilter` reads the HTTP `Authorization` header directly:

```java
String auth = req.getHeader("Authorization");  // Reads "Basic ..."
```

And delegates to `MASSignonService.signOn()`, which makes an mTLS call to the Core IDP's internal API (`internalapi.mas-inst1-core.svc:443/v3/users/checkauthentication`). The Core IDP validates the credentials and returns success. TRIRIGA then creates a session and returns `Set-Cookie: JSESSIONID=...`.

The SOAP endpoint bypasses Liberty's OIDC filter because:
1. The OIDC filter is configured for specific URL patterns (typically HTML/REST pages)
2. The SOAP endpoint (`/ws/TririgaWS`) has its own `AuthenticationFilter` registered in web.xml
3. HTTP Basic auth on SOAP was the original authentication mechanism before OIDC was added

---

## 9. OpenShift Resource Reference

### 9.1 Bridge's Resources in `ecifm-sso-bridge` Namespace

#### ConfigMap (`openshift/configmap.yaml`)

```yaml
MAS_BASE_URL: "https://main.facilities.inst1.apps.npos2.ecifmdev.net"
JWT_ISSUER_URI: "https://login.microsoftonline.com/c99cc570-ba4f-474e-897d-22255a3cecd7/v2.0"
BRIDGE_ISSUER_URL: "https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net"
MAS_OIDC_CLIENT_ID: "mas-facilities"
MAS_OIDC_REDIRECT_URI: "https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities"
MAS_OAUTH_CLIENT_REDIRECT_URI: "https://auth.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/default-oidc"
TRIRIGA_USERNAME: "tarun.suneja@ecifm.com"
```

**Key observation about redirect URIs:**
- `MAS_OIDC_REDIRECT_URI` = Facilities Liberty's OIDC callback — this is what the bridge's registered client uses for authorization_code flow
- `MAS_OAUTH_CLIENT_REDIRECT_URI` = Core IDP's OIDC callback — this is the URI used when Core IDP redirects back to itself after the bridge issues a code

#### Secrets (`openshift/secret.yaml`)

```yaml
AZURE_CLIENT_SECRET: ""  # Must be set from Azure AD
TRIRIGA_PASSWORD: "TR@maspassword2!"
MAS_OIDC_CLIENT_SECRET: "fMkWmZx8qZ8d9R/f+40lWrZJlTCzpwprxGqoWxXURU4="
```

#### Deployment (`openshift/deployment.yaml`)

```
Replicas: 2
Probes: Liveness (/actuator/health/liveness, 60s delay, 30s period)
        Readiness (/actuator/health/readiness, 30s delay, 10s period)
        Startup (same as readiness, 0s delay, 60s period, 150 failures)
Resources: Request 256Mi/250m, Limit 1Gi/500m
```

#### Route (`openshift/route.yaml`)

```yaml
Host: ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net
TLS: Edge termination (OpenShift router terminates HTTPS)
```

Edge TLS means:
- HTTPS between user and OpenShift router
- HTTP between router and pod (the pod sees plain HTTP)
- `server.forward-headers-strategy: framework` in `application-openshift.yml` tells Spring to trust the `X-Forwarded-*` headers from the router

### 9.2 How to Inspect OpenShift Resources

```powershell
# Find all projects
oc get projects

# List all resources in a namespace
oc get all -n <namespace>

# Describe a specific resource (shows env vars, volumes, labels, etc.)
oc describe deployment/<name> -n <namespace>
oc describe statefulset/<name> -n <namespace>
oc describe pod/<name> -n <namespace>

# Get YAML output of a resource
oc get <resource> <name> -n <namespace> -o yaml

# View logs
oc logs deployment/<name> -n <namespace> --tail=50

# Decode a base64 secret value
oc get secret <name> -n <namespace> -o jsonpath="{.data.<key>}" | base64 -d

# Watch pods
oc get pods -n <namespace> -w

# Check routes
oc get routes -n <namespace>

# Check ConfigMaps
oc get configmaps -n <namespace>
```

### 9.3 TLS Termination Types Used

| Route | Termination | How It Works |
|-------|-------------|--------------|
| Bridge route | **Edge** | Router terminates HTTPS, sends HTTP to pod. Pod must trust `X-Forwarded-*` headers. |
| MAS Core routes | **Reencrypt** | Router terminates HTTPS, establishes NEW HTTPS connection to pod. Pod needs its own TLS cert. |
| Facilities routes | **Reencrypt** | Same — pod runs Liberty with its own TLS cert (`inst1-main-liberty-facilities-tls`). |

Edge TLS is simpler (no pod TLS needed), which is why the bridge uses it. The MAS routes use reencrypt because Liberty manages its own TLS.

---

## 10. Glossary

| Term | Definition |
|------|------------|
| **AS** | Authorization Server — in OAuth2, the server that issues tokens |
| **Core IDP** | The MAS Core Identity Provider at `auth.inst1.apps.npos2.ecifmdev.net`, running on WebSphere Liberty |
| **Edge TLS** | TLS termination at the router — traffic is decrypted at the router, sent as HTTP to the pod |
| **Entra ID** | Microsoft's cloud identity service (formerly Azure AD) |
| **IConnect** | TRIRIGA's plugin framework for custom business logic |
| **JSESSIONID** | Java Servlet session cookie — used by Liberty to track HTTP sessions |
| **Liberty** | WebSphere Liberty — the Java application server running TRIRIGA and the Core IDP |
| **LTPA** | Lightweight Third Party Authentication — IBM's proprietary single sign-on token format |
| **MAS** | Maximo Application Suite — IBM's enterprise asset management platform |
| **mTLS** | Mutual TLS — both client and server present certificates |
| **OIDC** | OpenID Connect — authentication layer on top of OAuth2 |
| **OIDC RP** | OIDC Relying Party — the client that relies on an OIDC provider for authentication |
| **OP** | OIDC Provider — the server that issues ID tokens |
| **OSLC** | Open Services for Lifecycle Collaboration — REST API used by TRIRIGA for integrations |
| **Reencrypt TLS** | TLS termination at the router, then re-encrypted to the pod |
| **SPA** | Single Page Application — React frontend that handles navigation client-side |
| **SSOConnect** | Custom IConnect plugin that syncs user groups from Entra ID to TRIRIGA |
| **WSSubject** | WebSphere's subject API — `getRunAsSubject()` returns the authenticated user |
| **X-Forwarded-*** | HTTP headers set by reverse proxies to pass original client info (proto, host, IP) |

---

## Appendix A: TRIRIGA Liberty OIDC XML

Decoded from the `inst1-main-credentials-oauth-facilities-liberty` secret:

```xml
<openidConnectClient
  clientId="facilities"
  clientSecret="YxuA5JuDOnIFamGqtIhguFfqsCDnIRSo"
  id="facilities"
  issuerIdentifier="https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite"
  tokenEndpointUrl="https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite/token"
  jwkEndpointUrl="https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite/jwk"
  authorizationEndpointUrl="https://auth.inst1.apps.npos2.ecifmdev.net/oidc/endpoint/MaximoAppSuite/authorize"
  scope="openid" signatureAlgorithm="RS256"
  headerName="x-access-token"
  includeIdTokenInSubject="true"
  isClientSideRedirectSupported="false"
  accessTokenInLtpaCookie="true"
  mapIdentityToRegistryUser="false"
/>
```

## Appendix B: MAS Core IDP OIDC Provider Config

From `mas-multi-oidc` ConfigMap:

```yaml
oidc:
- discoveryEndpointUrl: "https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/.well-known/openid-configuration"
  userIdentifier: "preferred_username"
  tokenEndpointAuthMethod: "post"
  signatureAlgorithm: "RS256"
  credentials: "inst1-usersupplied-oidc-bridge-creds-system"
  configId: "default-oidc"
```

## Appendix C: Bridge OIDC Registration

From the bridge code (`AuthServerConfig.java`):

```yaml
RegisteredClient:
  clientId: "mas-facilities"
  clientSecret: "{noop}${MAS_OIDC_CLIENT_SECRET}"
  authMethods: CLIENT_SECRET_BASIC, CLIENT_SECRET_POST
  grantTypes: AUTHORIZATION_CODE, CLIENT_CREDENTIALS
  redirectUris:
    - "https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities"
    - "https://auth.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/default-oidc"
  scopes: openid, profile, email
  PKCE: disabled
  consent: not required
  idTokenSignature: RS256
  authCodeTtl: 5 minutes
  accessTokenTtl: 15 minutes
```
