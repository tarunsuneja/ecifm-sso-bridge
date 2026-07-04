# ecifm-saml-bridge — OIDC IdP Deployment Guide

> **Version:** 0.2.0 — OIDC Identity Provider (IdP) architecture  
> **Date:** 2026-06-29  
> **Cluster:** NPOS2  
> **Target:** MAS/TRIRIGA on OpenShift

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Prerequisites](#2-prerequisites)
3. [Step 1 — Verify Azure AD App Registration](#3-step-1--verify-azure-ad-app-registration)
4. [Step 2 — Generate the MAS OIDC Client Secret](#4-step-2--generate-the-mas-oidc-client-secret)
5. [Step 3 — Build the Bridge JAR](#5-step-3--build-the-bridge-jar)
6. [Step 4 — Build and Push the Docker Image](#6-step-4--build-and-push-the-docker-image)
7. [Step 5 — Update OpenShift Resources](#7-step-5--update-openshift-resources)
8. [Step 6 — Deploy to OpenShift](#8-step-6--deploy-to-openshift)
9. [Step 7 — Verify the Bridge Is Running](#9-step-7--verify-the-bridge-is-running)
10. [Step 8 — Configure MAS Admin UI OIDC Provider](#10-step-8--configure-mas-admin-ui-oidc-provider)
11. [Step 9 — Test the Full Flow](#11-step-9--test-the-full-flow)
12. [Troubleshooting](#12-troubleshooting)
13. [Appendices](#13-appendices)

---

## 1. Architecture Overview

### 1.1 Problem Statement

Before this change, the bridge authenticated users with Entra ID (Azure AD) and then redirected them to MAS/TRIRIGA. However, MAS/Liberty required its own session — the user had to manually click "Microsoft" on the Liberty form login page to create a MAS session. This broke single-sign-on.

### 1.2 Solution: Bridge as OIDC IdP

The bridge is now a **trusted OIDC Identity Provider** for MAS/Liberty. MAS delegates authentication to the bridge, and the bridge proxies authentication to Entra ID. This matches exactly how TRIRIGA's OIDC SSO path works internally.

### 1.3 Request Flow

```
User visits TRIRIGA
     │
     ▼
MAS TRIRIGA SPA loads
     │
     ▼
MAS Liberty checks for session ─── Has session? ──► TRIRIGA home page
     │
     ▼ No session
Liberty redirects to /oidcclient/redirect/facilities
     │
     ▼
Liberty OIDC client sends /authorize request to Bridge
     │
     ▼
Bridge receives GET /oauth2/authorize?response_type=code&client_id=mas-facilities&...
     │
     ▼ (AS filter chain @Order(1) — not authenticated)
LoginUrlAuthenticationEntryPoint → redirect to /oauth2/authorization/entra-id
     │
     ▼
Bridge OAuth2 client chain @Order(3) — redirects browser to Entra ID
     │
     ▼
Entra ID login page (or silent SSO if already authenticated in browser)
     │
     ▼ User authenticates
Entra ID redirects back to bridge: /login/oauth2/code/entra-id?code=...
     │
     ▼
Bridge exchanges code with Entra ID, receives OidcUser
     │
     ▼
AuthenticationSuccessHandler retrieves original saved request from RequestCache
     │
     ▼
Bridge redirects browser back to its own /oauth2/authorize (with cookies now)
     │
     ▼
AS filter chain @Order(1) — user IS authenticated
     │
     ▼
Bridge generates authorization code, redirects to Liberty callback:
   https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities?code=...
     │
     ▼
Liberty receives authorization code
     │
     ▼
Liberty calls bridge POST /oauth2/token with client_assertion (private_key_jwt)
     │
     ▼
Bridge validates client credentials, returns:
   { access_token, id_token (RS256 signed), token_type: "Bearer", expires_in }
     │
     ▼
Liberty validates the id_token signature via bridge /oauth2/jwks
     │
     ▼
Liberty extracts preferred_username claim → sets as UserPrincipal
     │
     ▼
TRIRIGA UserSessionFilter detects WSSubject.getRunAsSubject() is populated
     │
     ▼
TRIRIGA creates session automatically
     │
     ▼
User lands on TRIRIGA home page — zero clicks
```

### 1.4 OIDC Token Claims

The bridge's ID token includes the following claims (customized from Entra ID's OidcUser):

| Claim | Source | Example |
|-------|--------|---------|
| `sub` | Entra ID `sub` | `a1b2c3d4-e5f6-7890-abcd-ef1234567890` |
| `preferred_username` | Entra ID email or `preferred_username` | `tarun.suneja@ecifm.com` |
| `email` | Entra ID `email` | `tarun.suneja@ecifm.com` |
| `name` | Entra ID `name` or `preferred_username` | `Tarun Suneja` |

Liberty maps `preferred_username` to the `UserPrincipal`, which TRIRIGA's `AuthenticationHandler.getUserIdFromRequest()` reads to auto-create the user session.

### 1.5 Key Differences from Old Architecture

| Aspect | Old (v0.1.0) | New (v0.2.0) |
|--------|-------------|-------------|
| Bridge role | Client-only: called MAS SSOConnect | OIDC Identity Provider (IdP) |
| Auth flow | Bridge auth → redirect MAS → user clicks Microsoft | Full OIDC flow: MAS → Bridge → Entra ID → Bridge → MAS |
| User action required | Must click "Microsoft" button on Liberty form login | Zero clicks |
| Security | Bearer token validation | OIDC Authorization Code flow + RS256 signed ID tokens |
| Token format | JWT from MAS SSO | ID token signed by bridge's RSA key |

---

## 2. Prerequisites

### 2.1 Tools

| Tool | Version | Purpose |
|------|---------|---------|
| JDK | 17+ | Compile the bridge JAR |
| Maven | 3.9+ | Build the JAR |
| Docker / Podman | latest | Build container image |
| OpenShift CLI (`oc`) | 4.x | Deploy to OpenShift |
| OpenShift cluster access | admin | Update ConfigMap, Secret, Deployment |
| MAS Admin UI access | admin | Configure OIDC provider |

### 2.2 Environment URLs (NPOS2)

| Component | URL |
|-----------|-----|
| Bridge | `https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net` |
| TRIRIGA | `https://main.facilities.inst1.apps.npos2.ecifmdev.net/app/tririga` |
| MAS Liberty auth | `https://auth.inst1.apps.npos2.ecifmdev.net` |
| Liberty OIDC callback | `https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities` |
| Entra ID tenant | `c99cc570-ba4f-474e-897d-22255a3cecd7` |
| Azure AD app client ID | `cbcea157-2c35-4ce3-b86c-782282e00857` |

---

## 3. Step 1 — Verify Azure AD App Registration

The bridge uses TRIRIGA's existing Azure AD app registration. **No new Azure app is needed.** You only need to verify the redirect URI is registered.

### 3.1 Locate the App

1. Go to **https://portal.azure.com**
2. Navigate to **Microsoft Entra ID** → **App registrations**
3. Search for the app with client ID `cbcea157-2c35-4ce3-b86c-782282e00857` (likely named "TRIRIGA" or "ecifm-tririga")

### 3.2 Verify Redirect URI

1. In the app registration, select **Authentication** (left menu)
2. Under **Web** → **Redirect URIs**, confirm this is present:

   ```
   https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/login/oauth2/code/entra-id
   ```

3. If missing, click **Add URI**, paste the URL, and **Save**

### 3.3 Check Client Secret

1. Select **Certificates & secrets** → **Client secrets**
2. Verify there is a valid client secret (will be used as `AZURE_CLIENT_SECRET`)
3. If expired or missing, create a new one:
   - Click **New client secret**
   - Description: `ecifm-saml-bridge`
   - Expires: Choose appropriate period (recommended: 12 or 24 months)
   - **Copy the secret value immediately** — it will not be shown again
4. You will need this value for the OpenShift Secret (see [Step 5.3](#53-secret))

### 3.4 API Permissions (Optional — for Graph API fallback)

If you use the Microsoft Graph API fallback (for JWT tokens with >200 groups):

1. Select **API permissions**
2. Ensure these delegated permissions are present:
   - `Microsoft Graph / GroupMember.Read.All`
   - `Microsoft Graph / User.Read`
3. Click **Grant admin consent** if needed

### 3.5 Token Configuration (Optional)

If you need group claims in the token:

1. Select **Manifest** (left menu)
2. Find the `groupMembershipClaims` property and set it to:

   ```json
   "groupMembershipClaims": "SecurityGroup"
   ```

---

## 4. Step 2 — Generate the MAS OIDC Client Secret

The bridge acts as an OIDC IdP for MAS/Liberty. MAS needs a shared secret when exchanging authorization codes for tokens at the bridge's `/oauth2/token` endpoint.

### 4.1 Generate a Random Secret

Run this command on any machine with OpenSSL installed:

```bash
openssl rand -base64 32
```

**Example output:** `uF6kR9sJ2nL4pQ7wX1zC5vB8mN3hT0yA`

> **Security note:** This secret is shared between the bridge and MAS. Store it securely. Anyone with this secret can impersonate MAS to the bridge.

### 4.2 Save the Secret

You will need this value in two places:
1. **OpenShift Secret** — `MAS_OIDC_CLIENT_SECRET` (see [Step 5.3](#53-secret))
2. **MAS Admin UI** — OIDC Provider Client Secret (see [Step 8](#8-step-8--configure-mas-admin-ui-oidc-provider))

---

## 5. Step 3 — Build the Bridge JAR

### 5.1 Set JAVA_HOME

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
```

### 5.2 Build

```powershell
mvn clean package -DskipTests
```

**Output:** `target/ecifm-saml-bridge.jar`

### 5.3 Verify the Build

```powershell
# Check the JAR exists and size
ls target/ecifm-saml-bridge.jar

# Quick sanity check — list the Spring Boot auto-configuration classes
jar tf target/ecifm-saml-bridge.jar | findstr "AuthServerConfig"
```

Expected: `AuthServerConfig.class` is listed.

---

## 6. Step 4 — Build and Push the Docker Image

### 6.1 Build the Image

```powershell
docker build -t ecifm-saml-bridge:0.2.0 .
```

### 6.2 Tag and Push (if using external registry)

```powershell
# Tag for your registry
docker tag ecifm-saml-bridge:0.2.0 your-registry/ecifm-saml-bridge:0.2.0
docker tag ecifm-saml-bridge:0.2.0 your-registry/ecifm-saml-bridge:latest

# Push
docker push your-registry/ecifm-saml-bridge:0.2.0
docker push your-registry/ecifm-saml-bridge:latest
```

### 6.3 OpenShift Internal Registry

If using the OpenShift internal image registry:

```powershell
# Login to the OpenShift registry
oc registry login

# Tag for the internal registry
docker tag ecifm-saml-bridge:0.2.0 image-registry.openshift-image-registry.svc:5000/tririga/ecifm-sso-bridge:0.2.0
docker tag ecifm-saml-bridge:0.2.0 image-registry.openshift-image-registry.svc:5000/tririga/ecifm-sso-bridge:latest

# Push
docker push image-registry.openshift-image-registry.svc:5000/tririga/ecifm-sso-bridge:latest
```

---

## 7. Step 5 — Update OpenShift Resources

### 7.1 ConfigMap

Navigate to **Workloads** → **ConfigMaps** → `ecifm-bridge-config` → **YAML** tab → **Edit**

Replace with the full configuration below. The three new entries are `BRIDGE_ISSUER_URL`, `MAS_OIDC_CLIENT_ID`, and `MAS_OIDC_REDIRECT_URI`.

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

**What each ConfigMap entry does:**

| Key | Value | Purpose |
|-----|-------|---------|
| `MAS_BASE_URL` | TRIRIGA route URL | Base URL for TRIRIGA |
| `MAS_CONTEXT` | `/tririga` | TRIRIGA context path |
| `MAS_REDIRECT_URL` | Full TRIRIGA app URL | Where users land after auth |
| `MAS_REST_API` | SSOConnect path | REST endpoint for group sync |
| `JWT_ISSUER_URI` | Entra ID v2.0 issuer | Tells bridge where to validate incoming tokens |
| `BRIDGE_ISSUER_URL` | Bridge's own URL | **NEW** — Tells bridge its own issuer for OIDC discovery |
| `MAS_OIDC_CLIENT_ID` | `mas-facilities` | **NEW** — Client ID that MAS uses to identify itself to the bridge |
| `MAS_OIDC_REDIRECT_URI` | Liberty callback URL | **NEW** — Where MAS Liberty sends the auth code |
| `GRAPH_API_ENABLED` | `true` | Enables Graph API fallback for large groups |
| `SPRING_PROFILES_ACTIVE` | `openshift` | Activates `application-openshift.yml` |
| `AZURE_CLIENT_ID` | Entra ID app client ID | Bridge's identity for Entra ID |
| `TRIRIGA_USERNAME` | Service account email | Used for TRIRIGA SOAP calls |

### 7.2 References in `application-openshift.yml`

The bridge reads these environment variables from the ConfigMap (via `envFrom`). The relevant mappings in `application-openshift.yml` are:

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
mas:
  oidc:
    client-id: ${MAS_OIDC_CLIENT_ID:mas-facilities}
    client-secret: ${MAS_OIDC_CLIENT_SECRET}
    redirect-uri: ${MAS_OIDC_REDIRECT_URI:https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities}
```

### 7.3 Secret

Navigate to **Workloads** → **Secrets** → `ecifm-bridge-secrets` → **YAML** tab → **Edit**

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: ecifm-bridge-secrets
type: Opaque
stringData:
  AZURE_CLIENT_SECRET: "<your-azure-client-secret>"
  TRIRIGA_PASSWORD: "TR@maspassword2!"
  MAS_OIDC_CLIENT_SECRET: "uF6kR9sJ2nL4pQ7wX1zC5vB8mN3hT0yA"
```

**Values to fill in:**

| Key | Value Source |
|-----|-------------|
| `AZURE_CLIENT_SECRET` | From Azure AD app registration (Certificates & Secrets). This is the secret for client ID `cbcea157-2c35-4ce3-b86c-782282e00857`. |
| `TRIRIGA_PASSWORD` | The service account password for `tarun.suneja@ecifm.com` (used for SOAP calls). |
| `MAS_OIDC_CLIENT_SECRET` | The random secret you generated in [Step 2](#4-step-2--generate-the-mas-oidc-client-secret). |

> **Azure Client Secret is external:** You must get the current `AZURE_CLIENT_SECRET` value from the existing deployment or from Azure. It is NOT stored in git. If you don't have it, create a new one in Azure and use that.

### 7.4 Deployment

Navigate to **Workloads** → **Deployments** → `ecifm-sso-bridge` → **YAML** tab → **Edit**

The deployment already has the `MAS_OIDC_CLIENT_SECRET` env var added (from the git changes). Verify it is present under `spec.template.spec.containers[0].env`:

```yaml
            - name: AZURE_CLIENT_SECRET
              valueFrom:
                secretKeyRef:
                  name: ecifm-bridge-secrets
                  key: AZURE_CLIENT_SECRET
            - name: TRIRIGA_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: ecifm-bridge-secrets
                  key: TRIRIGA_PASSWORD
            - name: MAS_OIDC_CLIENT_SECRET
              valueFrom:
                secretKeyRef:
                  name: ecifm-bridge-secrets
                  key: MAS_OIDC_CLIENT_SECRET
```

**Note:** The ConfigMap values (`BRIDGE_ISSUER_URL`, `MAS_OIDC_CLIENT_ID`, `MAS_OIDC_REDIRECT_URI`) do NOT need individual `env` entries — they are injected automatically via the `envFrom: configMapRef` at the top of the container spec:

```yaml
          envFrom:
            - configMapRef:
                name: ecifm-bridge-config
```

**Image reference:** Update the image tag if needed:

```yaml
          image: image-registry.openshift-image-registry.svc:5000/tririga/ecifm-sso-bridge:latest
```

Or point to your external registry:

```yaml
          image: your-registry/ecifm-saml-bridge:0.2.0
```

---

## 8. Step 6 — Deploy to OpenShift

### 8.1 Trigger a Rolling Update

```powershell
oc rollout restart deployment/ecifm-sso-bridge
```

### 8.2 Watch the Rollout

```powershell
oc rollout status deployment/ecifm-sso-bridge -w
```

Expected output: `deployment "ecifm-sso-bridge" successfully rolled out`

### 8.3 Check Pod Status

```powershell
oc get pods -l app=ecifm-sso-bridge
```

Expected: all pods in `Running` state with `1/1` ready.

---

## 9. Step 7 — Verify the Bridge Is Running

### 9.1 Basic Health Check

```powershell
curl https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/test
```

Expected: `ecifm-saml-bridge is running`

### 9.2 OIDC Discovery Endpoint

```powershell
curl https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/.well-known/openid-configuration
```

Expected: A JSON response with OIDC metadata including:

```json
{
  "issuer": "https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net",
  "authorization_endpoint": "https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/oauth2/authorize",
  "token_endpoint": "https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/oauth2/token",
  "jwks_uri": "https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/oauth2/jwks",
  "scopes_supported": ["openid", "profile", "email"],
  "response_types_supported": ["code"],
  "grant_types_supported": ["authorization_code"],
  "subject_types_supported": ["public"],
  "id_token_signing_alg_values_supported": ["RS256"]
}
```

### 9.3 JWKS Endpoint

```powershell
curl https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/oauth2/jwks
```

Expected: A JSON Web Key Set with one RSA public key (2048-bit, RS256):

```json
{
  "keys": [{
    "kty": "RSA",
    "use": "sig",
    "alg": "RS256",
    "kid": "...",
    "n": "...",
    "e": "AQAB"
  }]
}
```

### 9.4 Check Pod Logs

```powershell
oc logs -l app=ecifm-sso-bridge --tail=30
```

Look for the startup marker lines:

```
=== OAuth2 Configuration ===
AZURE_CLIENT_ID: cbcea157-2c35-4ce3-b86c-782282e00857
AZURE_CLIENT_SECRET length: 40
JWT_ISSUER_URI: https://login.microsoftonline.com/c99cc570-ba4f-474e-897d-22255a3cecd7/v2.0
BRIDGE_ISSUER_URL: https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net
MAS_OIDC_CLIENT_ID: mas-facilities
MAS_OIDC_REDIRECT_URI: https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities
```

And later:

```
Registering OIDC client: mas-facilities with redirect URI: https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities
Generating RSA key pair for OIDC token signing
```

### 9.5 Verify Authorization Endpoint (Browser Redirect Test)

Open a browser and visit:

```
https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/oauth2/authorize?response_type=code&client_id=mas-facilities&redirect_uri=https://main.facilities.inst1.apps.npos2.ecifmdev.net/oidcclient/redirect/facilities&scope=openid&state=test
```

You should be redirected to the Entra ID login page (or pass through silently if you already have an active session). After authenticating, you'll be redirected back to an endpoint on the bridge. This confirms the AS filter chain is working.

---

## 10. Step 8 — Configure MAS Admin UI OIDC Provider

### 10.1 Access MAS Admin UI

1. Open `https://admin.inst1.apps.npos2.ecifmdev.net` in a browser
2. Log in with admin credentials (e.g., `maxadmin`)

### 10.2 Navigate to OIDC Configuration

1. Go to **Manage Applications** (or **Access Management** depending on MAS version)
2. Find **IBM MAS** → **Manage Authentication**
3. Or navigate to **Manage Authentication** directly from the left menu

### 10.3 Configure or Edit the OIDC Provider

Look for **OIDC Authentication** or **Identity Providers**.

If there is an existing Entra ID provider (pointing directly to Azure AD):

1. Click **Edit** on the existing provider
2. Update the values below

If creating a new one:

1. Click **Create** or **Add OIDC Provider**

**Provider Settings:**

| Field | Value | Notes |
|-------|-------|-------|
| **Display Name** | `Entra AD SSO` | Or keep existing name |
| **Client ID** | `mas-facilities` | Must match `MAS_OIDC_CLIENT_ID` in ConfigMap |
| **Client Secret** | The secret from [Step 2](#4-step-2--generate-the-mas-oidc-client-secret) | Must match `MAS_OIDC_CLIENT_SECRET` in Secret |
| **Discovery endpoint** | `https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/.well-known/openid-configuration` | Bridge's OIDC discovery URL |
| **Signature Algorithm** | `RS256` | Must match the bridge's token signing algorithm |
| **User Identifier (JWT)** | `preferred_username` | The claim Liberty uses to identify the user |
| **Token Endpoint Authentication Method** | `Client Secret Post` | The bridge supports both `client_secret_basic` and `client_secret_post` |
| **Issuer** | (Leave blank — auto-discovered) | Discovery endpoint provides this |
| **Scope** | `openid profile email` | Requested scopes |

### 10.4 Save and Restart MAS Auth

1. Click **Save**
2. MAS may prompt you to **Restart** the authentication service — if so, confirm the restart
3. Wait 1-2 minutes for the auth pod to restart

### 10.5 Verify MAS OIDC Configuration (Optional)

If you have shell access to the MAS infrastructure:

```bash
# Check the auth pod logs for OIDC provider registration
oc logs -l app=mas-auth --tail=20
```

Look for lines indicating the OIDC provider was configured with the bridge's discovery endpoint.

---

## 11. Step 9 — Test the Full Flow

### 11.1 First-Time Test (Incognito Window)

1. Open an **incognito/private** browser window (to ensure no cached sessions)
2. Navigate to: `https://main.facilities.inst1.apps.npos2.ecifmdev.net/app/tririga`
3. Observe the redirect chain:
   - TRIRIGA SPA loads
   - Redirected to Liberty auth server (`auth.inst1.apps.npos2.ecifmdev.net`)
   - Liberty redirects to bridge (`ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/oauth2/authorize`)
   - Bridge redirects to Entra ID (`login.microsoftonline.com`)
   - **Enter your credentials** (first time only)
   - Redirected back to bridge, then to Liberty, then to TRIRIGA
4. **Result:** You land on the TRIRIGA home page

### 11.2 Subsequent Tests (Should Be Zero Clicks)

1. Open another incognito window
2. Navigate to `https://main.facilities.inst1.apps.npos2.ecifmdev.net/app/tririga`
3. **Result:** Zero clicks — you land directly on the TRIRIGA home page
   - This works because your browser has an active session with Entra ID (or the bridge issues a session cookie after the first successful auth)

### 11.3 Verify in Bridge Logs

```powershell
# Watch the bridge logs in real-time
oc logs -l app=ecifm-sso-bridge --tail=50 -f
```

When a user authenticates, you should see log output like:

```
=== OAuth2 Configuration ===
...
Received authorization request for client: mas-facilities
User authenticated via Entra ID: tarun.suneja@ecifm.com
Issuing authorization code for client: mas-facilities
Token request for client: mas-facilities, grant_type: authorization_code
Generated ID token for user: tarun.suneja@ecifm.com
```

### 11.4 What to Check If It Doesn't Work

If you encounter errors, check:

1. **Bridge logs** — Look for Spring Security debug output (`DEBUG` level logging is enabled)
2. **MAS Liberty logs** — Errors with OIDC token validation (token signature, claims, etc.)
3. **Browser developer tools** → **Network** tab — Follow the redirect chain to see where it breaks
4. See [Troubleshooting](#12-troubleshooting) section below

---

## 12. Troubleshooting

### 12.1 "401 Unauthorized" from MAS Liberty

**Symptoms:** Liberty returns a 401 error after OIDC callback.

**Causes:**
- MAS OIDC client secret mismatch between bridge and MAS Admin UI
- Token signature validation failure (Liberty can't reach bridge JWKS endpoint)
- Clock skew between bridge pod and MAS pod (JWT `iat`/`exp` validation)

**Solutions:**
1. Verify the `MAS_OIDC_CLIENT_SECRET` in the OpenShift Secret matches what is configured in MAS Admin UI
2. Verify Liberty can reach `https://ecifm-sso-bridge-ecifm-sso-bridge.apps.npos2.ecifmdev.net/oauth2/jwks` (network policy may block cross-namespace traffic)
3. Check clock synchronization — ensure all pods use NTP

### 12.2 Infinite Redirect Loop

**Symptoms:** Browser bounces between bridge and Entra ID without landing on TRIRIGA.

**Causes:**
- `RequestCache` isn't properly restoring the original `/oauth2/authorize` request
- Session cookies not being preserved across redirects
- Liberty redirect URI doesn't match exactly

**Solutions:**
1. Check bridge logs — look for `RequestCache` entries
2. Verify the `MAS_OIDC_REDIRECT_URI` exactly matches what Liberty sends in the authorization request (including no trailing slash, exact scheme)
3. Try clearing browser cookies and cache

### 12.3 "No matching client" Error on Token Endpoint

**Symptoms:** Liberty gets an error exchanging the authorization code for a token.

**Causes:**
- `mas-facilities` client is not registered in the bridge's `RegisteredClientRepository`
- Client authentication method mismatch (bridge expects `client_secret_basic` or `client_secret_post`)

**Solutions:**
1. Check bridge logs for "Registering OIDC client: mas-facilities with redirect URI: ..."
2. Verify the `MAS_OIDC_CLIENT_ID` ConfigMap value is `mas-facilities`
3. In MAS Admin UI, try changing "Token Endpoint Authentication Method" to `Client Secret Basic`

### 12.4 Token Has Wrong Claims / User Not Recognized

**Symptoms:** Liberty receives the token, but TRIRIGA doesn't create a session.

**Causes:**
- `preferred_username` claim is missing or has wrong value
- Liberty's "User Identifier (JWT)" field doesn't match the claim name

**Solutions:**
1. Decode the ID token to inspect claims:

   ```powershell
   # Get a sample ID token from bridge logs
   oc logs -l app=ecifm-sso-bridge --tail=100 | findstr "Generated ID token"
   ```

2. Verify `preferred_username` is present and contains the user's email
3. In MAS Admin UI, ensure "User Identifier (JWT)" is set to `preferred_username`

### 12.5 New Pod Can't Validate Tokens from Old Pod

**Symptoms:** After a rolling update, users get token validation errors.

**Cause:** The bridge generates a new RSA key pair on each startup. Tokens signed by pod A cannot be validated by pod B.

**Solutions:**
- **Short-term:** Run 1 replica (`replicas: 1`) until this is addressed
- **Long-term:** Implement persistent JWK Set storage (see [Appendix C — Persistent JWK Set](#appendix-c--persistent-jwk-set))

### 12.6 Debug Logging

The bridge has verbose logging enabled. To increase logging temporarily:

```powershell
# Set logging level dynamically (if actuator is enabled)
oc set env deployment/ecifm-sso-bridge LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY=TRACE
oc set env deployment/ecifm-sso-bridge LOGGING_LEVEL_COM_ECIFM_SAML_BRIDGE=DEBUG
```

Or check the logs in real-time:

```powershell
oc logs -l app=ecifm-sso-bridge -f
```

---

## 13. Appendices

### Appendix A — OpenShift CLI Commands

If you prefer the CLI over the web console, here are all the commands:

```bash
# Login
oc login --token=<token> --server=https://api.npos2.ecifmdev.net:6443
oc project tririga

# Apply ConfigMap
oc apply -f openshift/configmap.yaml

# Apply Secret (WARNING: this replaces the entire Secret!)
oc apply -f openshift/secret.yaml

# Apply Deployment
oc apply -f openshift/deployment.yaml

# Apply Service
oc apply -f openshift/service.yaml

# Apply Route
oc apply -f openshift/route.yaml

# Restart
oc rollout restart deployment/ecifm-sso-bridge

# Watch
oc rollout status deployment/ecifm-sso-bridge -w

# View logs
oc logs -l app=ecifm-sso-bridge --tail=50

# Scale (temporary)
oc scale deployment/ecifm-sso-bridge --replicas=1
```

### Appendix B — Git Commits and Tags

**Relevant tags:**
- `v0.1.0-bridge-client-only` — The old code before the OIDC IdP changes (for reference/revert)

**Changed files in v0.2.0:**

```
 M openshift/configmap.yaml
 M openshift/deployment.yaml
 M openshift/secret.yaml
 M pom.xml
?? src/main/java/com/ecifm/saml/bridge/config/AuthServerConfig.java
 M src/main/java/com/ecifm/saml/bridge/config/SecurityConfig.java
 M src/main/java/com/ecifm/saml/bridge/EcifmSamlBridgeApplication.java
 M src/main/resources/application-openshift.yml
```

### Appendix C — Persistent JWK Set

**Current behavior:** The bridge generates a new RSA 2048-bit key pair at startup. This is a development-quality approach.

**Why this matters:** With 2+ replicas, each pod has a different key. If pod A signs a token and pod B receives the token request, Liberty's JWKS fetch may get pod B's key and fail to validate pod A's signature.

**Recommended production approach:** Store the JWK Set in a file that persists across restarts (e.g., a PVC or ConfigMap):

1. **Generate a JWK Set offline:**

   ```java
   // Use Nimbus JOSE + Jackson to generate a JWK Set once
   RSAKey rsaKey = new RSAKeyGenerator(2048)
       .keyID("bridge-1")
       .algorithm(JWSAlgorithm.RS256)
       .generate();
   JWKSet jwkSet = new JWKSet(rsaKey);
   String json = jwkSet.toString();
   System.out.println(json);
   ```

2. **Store the JSON in a ConfigMap or Secret** named `ecifm-bridge-jwks`
3. **Mount the file** in the pod at `/etc/jwks/jwks.json`

4. **Modify `AuthServerConfig.java`:**

   ```java
   @Value("${bridge.jwks.file:}")
   private String jwksFilePath;

   @Bean
   public JWKSource<SecurityContext> jwkSource() throws Exception {
       if (jwksFilePath != null && !jwksFilePath.isEmpty()) {
           // Load from file
           try (var reader = new java.io.FileReader(jwksFilePath)) {
               JWKSet jwkSet = JWKSet.load(reader);
               log.info("Loaded JWK Set from file: {}", jwksFilePath);
               return new ImmutableJWKSet<>(jwkSet);
           }
       }
       // Fall back to generated key (as implemented now)
       ...
   }
   ```

### Appendix D — Code Reference: `AuthServerConfig.java`

This class configures the OIDC Authorization Server:

- **`registeredClientRepository()`** — Registers `mas-facilities` as a trusted OIDC client with the redirect URI matching Liberty's callback. Uses `{noop}` prefix for the client secret (handled by `DelegatingPasswordEncoder`).
- **`jwkSource()`** — Generates an RSA 2048-bit key pair and exposes it via JWKS endpoint (`/oauth2/jwks`). Liberty fetches this to validate ID token signatures.
- **`tokenCustomizer()`** — Injects claims from the Entra ID `OidcUser` into the bridge's ID token. Maps:
  - `sub` → Entra ID subject
  - `preferred_username` → user email
  - `email` → user email
  - `name` → user display name

### Appendix E — Code Reference: `SecurityConfig.java`

Three security filter chains:

| Order | Matcher | Purpose | Auth mechanism |
|-------|---------|---------|----------------|
| `@Order(1)` | AS endpoints (`/oauth2/*`, `/.well-known/*`) | OIDC Authorization Server | Delegates to Entra ID via `LoginUrlAuthenticationEntryPoint` |
| `@Order(2)` | `/local/**` | Local test endpoints | Permit all (no auth) |
| `@Order(3)` | All other requests | OAuth2 client for Entra ID | `oauth2Login` → Entra ID |

When an unauthenticated request hits an AS endpoint (e.g., `/oauth2/authorize`):
1. `LoginUrlAuthenticationEntryPoint` returns a 302 redirect to `/oauth2/authorization/entra-id`
2. The OAuth2 client chain handles this path and redirects to Entra ID
3. After Entra ID authentication, the `AuthenticationSuccessHandler` (built into Spring Security's OAuth2 login) retrieves the original saved request from the `RequestCache` and redirects back to the AS endpoint
4. This time, the request has an authenticated session, so the AS filter chain proceeds to generate the authorization code

### Appendix F — Environment Variables Reference

| Variable | Required | Source | Purpose |
|----------|----------|--------|---------|
| `AZURE_CLIENT_ID` | Yes | ConfigMap | Entra ID app registration client ID |
| `AZURE_CLIENT_SECRET` | Yes | Secret | Entra ID app registration secret |
| `JWT_ISSUER_URI` | Yes | ConfigMap | Entra ID v2.0 issuer URL |
| `MAS_BASE_URL` | Yes | ConfigMap | TRIRIGA base URL |
| `MAS_CONTEXT` | Yes | ConfigMap | TRIRIGA context path |
| `MAS_REDIRECT_URL` | Yes | ConfigMap | TRIRIGA full app URL |
| `MAS_REST_API` | Yes | ConfigMap | SSOConnect REST path |
| `TRIRIGA_USERNAME` | Yes | ConfigMap | Service account email |
| `TRIRIGA_PASSWORD` | Yes | Secret | Service account password |
| `BRIDGE_ISSUER_URL` | Yes (new) | ConfigMap | Bridge's own URL for OIDC issuer |
| `MAS_OIDC_CLIENT_ID` | Yes (new) | ConfigMap | OIDC client ID for MAS |
| `MAS_OIDC_CLIENT_SECRET` | Yes (new) | Secret | OIDC client secret shared with MAS |
| `MAS_OIDC_REDIRECT_URI` | Yes (new) | ConfigMap | Liberty's OIDC callback URL |
| `GRAPH_API_ENABLED` | No | ConfigMap | Enable/disable Graph API fallback |
| `SPRING_PROFILES_ACTIVE` | Yes | ConfigMap | Must be `openshift` |

---

*End of document*
